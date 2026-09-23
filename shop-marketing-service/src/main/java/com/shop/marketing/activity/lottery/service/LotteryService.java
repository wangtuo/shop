package com.shop.marketing.activity.lottery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.marketing.enums.ActivityTypes;
import com.shop.api.marketing.enums.CouponIssueWays;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsLockCommand;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.framework.feign.FeignResults;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.LotteryRecord;
import com.shop.marketing.activity.lottery.dto.DrawResult;
import com.shop.marketing.activity.lottery.dto.PrizeStockVO;
import com.shop.marketing.activity.lottery.entity.LotteryPrizeStock;
import com.shop.marketing.activity.lottery.mapper.LotteryPrizeStockMapper;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.LotteryRecordMapper;
import com.shop.marketing.activity.support.ActivityRule;
import com.shop.marketing.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 积分抽奖（design §2.2.2/§4.1，卡 B3）。
 *
 * <p>跨服务一致性：积分 lock → deduct 两阶段（bizNo 幂等），deduct 成功后才抽奖；
 * 抽奖落库/发奖任何一步失败，按 points/refund（独立幂等号）补偿，绝不"先扣后不回滚"。
 * 不使用跨服务长事务。奖品库存以 {@code occupy} 条件 UPDATE 为唯一防超发闸门。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryService {

    /** 奖品类型 */
    public static final int PRIZE_POINTS = 1;
    public static final int PRIZE_COUPON = 2;
    public static final int PRIZE_THANKS = 3;

    /** 规则缺省：每人每日 1 次 */
    static final int DEFAULT_DAILY_LIMIT = 1;

    private static final String LOCK_PREFIX = "mk:lock:lottery:";
    private static final String DAILY_PREFIX = "mk:lottery:daily:";

    private final ActivityMapper activityMapper;
    private final LotteryRecordMapper recordMapper;
    private final LotteryPrizeStockMapper prizeStockMapper;
    private final UserClient userClient;
    private final CouponService couponService;
    private final IdGenerator idGenerator;
    private final ObjectProvider<RedissonClient> redissonProvider;

    /** 可替换随机源（单测钉权重分布用种子随机） */
    Random random = new Random();

    /**
     * 抽奖一次。
     * 幂等号：积分 lock/deduct 共用 {@code LOTTERY:{activityId}:{recordId}}；
     * 积分奖品发放 {@code LOTTERY-POINT:{recordId}}；券发放 requestNo {@code LOTTERY:{recordId}}；
     * 失败退积分 {@code LOTTERY-REFUND:{activityId}:{recordId}}。
     */
    public DrawResult draw(Long userId, Long activityId) {
        Activity activity = validateOngoing(activityId);
        ActivityRule rule = parseRule(activity);
        int cost = rule.getLotteryCostPoints() != null && rule.getLotteryCostPoints() >= 0
                ? rule.getLotteryCostPoints() : 0;
        int dailyLimit = rule.getLotteryDailyLimit() != null && rule.getLotteryDailyLimit() > 0
                ? rule.getLotteryDailyLimit() : DEFAULT_DAILY_LIMIT;

        RedissonClient redis = redissonProvider.getIfAvailable();
        String dailyKey = dailyKey(activityId, userId);
        Integer redisCount = redis == null ? null : redis.<Integer>getBucket(dailyKey).get();
        if (redisCount != null && redisCount >= dailyLimit) {
            throw new BizException(ErrorCode.LIMIT_PURCHASE, "今日抽奖次数已达上限");
        }
        // Redis 丢键时 DB 当日计数兜底
        Long todayCount = recordMapper.selectCount(new LambdaQueryWrapper<LotteryRecord>()
                .eq(LotteryRecord::getActivityId, activityId)
                .eq(LotteryRecord::getUserId, userId)
                .ge(LotteryRecord::getCreateTime, LocalDate.now().atStartOfDay()));
        if (todayCount != null && todayCount >= dailyLimit) {
            throw new BizException(ErrorCode.LIMIT_PURCHASE, "今日抽奖次数已达上限");
        }

        long recordId = idGenerator.nextId();
        String bizNo = "LOTTERY:" + activityId + ":" + recordId;
        return runWithUserLock(redis, activityId, userId, () -> {
            boolean deducted = false;
            if (cost > 0) {
                lockPoints(userId, cost, bizNo);
                try {
                    FeignResults.unwrap(userClient.deductPoints(
                            new PointsDeductCommand(userId, bizNo)));
                    deducted = true;
                } catch (RuntimeException e) {
                    safeRelease(userId, bizNo);
                    throw e;
                }
            }
            LotteryPrizeStock won = null;
            boolean occupied = false;
            boolean finished = false;
            try {
                won = pickAndOccupy(activityId);
                occupied = won != null && won.getTotalStock() != null && won.getTotalStock() > 0;
                grantPrize(userId, recordId, won);

                LotteryRecord record = new LotteryRecord();
                record.setId(recordId);
                record.setActivityId(activityId);
                record.setUserId(userId);
                record.setCostPoints(cost);
                record.setPrizeCode(won == null ? null : won.getPrizeCode());
                record.setPrizeName(won == null ? "谢谢参与" : won.getPrizeName());
                recordMapper.insert(record);
                finished = true;

                if (redis != null) {
                    bumpDailyCounter(redis, dailyKey, dailyLimit);
                }
                Integer prizeType = won == null ? PRIZE_THANKS : won.getPrizeType();
                String prizeName = won == null ? "谢谢参与" : won.getPrizeName();
                return new DrawResult(recordId, prizeType,
                        won == null ? null : won.getPrizeCode(), prizeName, cost);
            } catch (RuntimeException e) {
                if (!finished) {
                    // 抽奖失败补偿：回补库存 + 退积分（独立幂等号），禁止扣积分后不回滚
                    if (occupied) {
                        safeReleaseStock(won.getId());
                    }
                    if (deducted) {
                        safeRefund(userId,
                                "LOTTERY-REFUND:" + activityId + ":" + recordId, (long) cost);
                    }
                }
                throw e;
            }
        });
    }

    /** 奖品库存视图（谢谢参与/不限量不返回余量数字）。 */
    public List<PrizeStockVO> prizes(Long activityId) {
        List<LotteryPrizeStock> list = prizeStockMapper.selectList(
                new LambdaQueryWrapper<LotteryPrizeStock>()
                        .eq(LotteryPrizeStock::getActivityId, activityId)
                        .orderByAsc(LotteryPrizeStock::getId));
        List<PrizeStockVO> result = new ArrayList<>(list.size());
        for (LotteryPrizeStock p : list) {
            Integer remain = null;
            if (p.getPrizeType() != null && p.getPrizeType() != PRIZE_THANKS
                    && p.getTotalStock() != null && p.getTotalStock() > 0) {
                int issued = p.getIssuedCount() == null ? 0 : p.getIssuedCount();
                remain = Math.max(p.getTotalStock() - issued, 0);
            }
            result.add(new PrizeStockVO(p.getPrizeCode(), p.getPrizeName(), p.getPrizeType(), remain));
        }
        return result;
    }

    /** 我的中奖记录（当日/全部由 createTime 索引支持）。 */
    public List<LotteryRecord> myPrizes(Long userId, Long activityId) {
        LambdaQueryWrapper<LotteryRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LotteryRecord::getUserId, userId).orderByDesc(LotteryRecord::getId);
        if (activityId != null) {
            wrapper.eq(LotteryRecord::getActivityId, activityId);
        }
        return recordMapper.selectList(wrapper);
    }

    /**
     * 加权命中：在候选列表上按 weight 选下标，hit ∈ [0,totalWeight)，累计权重跨过 hit 即命中。
     * weight=0 的奖品永不会被命中。
     */
    static int weightedIndex(List<LotteryPrizeStock> candidates, int totalWeight, Random rnd) {
        int hit = rnd.nextInt(totalWeight);
        int acc = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int w = candidates.get(i).getWeight() == null ? 0 : candidates.get(i).getWeight();
            acc += w;
            if (hit < acc) {
                return i;
            }
        }
        return candidates.size() - 1;
    }

    /**
     * 加权随机 + 有量奖品库存 CAS；并发抽走/罄尽时剔除该奖品降级重抽，
     * 全部有量奖品罄尽或无配置时返回 null（谢谢参与）。
     */
    private LotteryPrizeStock pickAndOccupy(Long activityId) {
        List<LotteryPrizeStock> candidates = new ArrayList<>(prizeStockMapper.selectList(
                new LambdaQueryWrapper<LotteryPrizeStock>()
                        .eq(LotteryPrizeStock::getActivityId, activityId)));
        while (true) {
            int totalWeight = candidates.stream()
                    .mapToInt(p -> p.getWeight() == null ? 0 : p.getWeight()).sum();
            if (candidates.isEmpty() || totalWeight <= 0) {
                return null;
            }
            int idx = weightedIndex(candidates, totalWeight, random);
            LotteryPrizeStock cand = candidates.remove(idx);
            if (cand.getTotalStock() == null || cand.getTotalStock() == 0) {
                return cand;
            }
            if (prizeStockMapper.occupy(cand.getId()) > 0) {
                return cand;
            }
            // 0 行：最后一件被并发抽走，剔除后降级重抽
            log.info("抽奖奖品已罄，降级重抽 activityId={} prizeCode={}", activityId, cand.getPrizeCode());
        }
    }

    /** 三类型发奖，全部带独立幂等号；谢谢参与无动作。 */
    private void grantPrize(Long userId, long recordId, LotteryPrizeStock won) {
        if (won == null) {
            return;
        }
        int type = won.getPrizeType() == null ? PRIZE_THANKS : won.getPrizeType();
        if (type == PRIZE_POINTS) {
            int points = won.getPoints() == null ? 0 : won.getPoints();
            if (points > 0) {
                FeignResults.unwrap(userClient.grantPoints(GrantPointsCommand.builder()
                        .userId(userId)
                        .bizNo("LOTTERY-POINT:" + recordId)
                        .points((long) points)
                        .scene(PointsScene.COMPENSATE)
                        .build()));
            }
        } else if (type == PRIZE_COUPON) {
            if (won.getCouponId() == null) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                        "抽奖券奖品未配置 couponId，prizeCode=" + won.getPrizeCode());
            }
            couponService.issue(userId, won.getCouponId(),
                    CouponIssueWays.ACTIVITY.getCode(), "LOTTERY:" + recordId);
        }
    }

    private void lockPoints(Long userId, int cost, String bizNo) {
        try {
            FeignResults.unwrap(userClient.lockPoints(PointsLockCommand.builder()
                    .userId(userId)
                    .bizNo(bizNo)
                    .points((long) cost)
                    .deductFen(0L)
                    .scene(PointsScene.CONSUME)
                    .build()));
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.DEPENDENCY_FAIL.getCode()
                    || e.getCode() == ErrorCode.DEPENDENCY_TIMEOUT.getCode()
                    || e.getCode() == ErrorCode.SYSTEM_ERROR.getCode()) {
                throw e;
            }
            throw new BizException(ErrorCode.POINTS_NOT_ENOUGH, "积分不足或锁定失败：" + e.getMessage());
        }
    }

    private void safeRelease(Long userId, String bizNo) {
        try {
            FeignResults.unwrap(userClient.releasePoints(new PointsReleaseCommand(userId, bizNo)));
        } catch (RuntimeException e) {
            log.error("抽奖积分 lock 后 deduct 失败，release 补偿异常 userId={} bizNo={}", userId, bizNo, e);
        }
    }

    private void safeRefund(Long userId, String bizNo, Long points) {
        try {
            FeignResults.unwrap(userClient.refundPoints(PointsRefundCommand.builder()
                    .userId(userId).bizNo(bizNo).points(points).build()));
        } catch (RuntimeException e) {
            // 退款幂等号独立，依赖用户域重试/对账兜底；此处仅告警不掩盖原异常
            log.error("抽奖失败积分退还补偿异常 userId={} bizNo={} points={}", userId, bizNo, points, e);
        }
    }

    private void safeReleaseStock(Long prizeId) {
        try {
            prizeStockMapper.releaseOne(prizeId);
        } catch (RuntimeException e) {
            log.error("抽奖失败库存回补异常 prizeId={}", prizeId, e);
        }
    }

    private void bumpDailyCounter(RedissonClient redis, String key, int dailyLimit) {
        try {
            RBucket<Integer> bucket = redis.getBucket(key);
            Integer cur = bucket.get();
            int next = cur == null ? 1 : cur + 1;
            bucket.set(next, Duration.between(LocalDateTime.now(),
                    LocalDate.now().plusDays(1).atStartOfDay()));
        } catch (RuntimeException e) {
            // DB 当日计数仍兜底，不因此让抽奖失败
            log.warn("抽奖日计数 Redis 写入失败，DB count 兜底 key={}", key, e);
        }
    }

    private String dailyKey(Long activityId, Long userId) {
        return DAILY_PREFIX + activityId + ":" + userId + ":" + LocalDate.now();
    }

    private DrawResult runWithUserLock(RedissonClient redis, Long activityId, Long userId,
                                       java.util.function.Supplier<DrawResult> action) {
        if (redis == null) {
            return action.get();
        }
        RLock lock = redis.getLock(LOCK_PREFIX + activityId + ":" + userId);
        boolean locked;
        try {
            locked = lock.tryLock(0, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, "获取抽奖锁被中断", e);
        }
        if (!locked) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "抽奖请求过于频繁，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Activity validateOngoing(Long activityId) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null
                || activity.getType() != ActivityTypes.LOTTERY.getCode()
                || activity.getStatus() != 1) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "抽奖活动不存在或未在进行中");
        }
        LocalDateTime now = LocalDateTime.now();
        if (activity.getStartTime() != null && now.isBefore(activity.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "抽奖活动尚未开始");
        }
        if (activity.getEndTime() != null && now.isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "抽奖活动已结束");
        }
        return activity;
    }

    private ActivityRule parseRule(Activity activity) {
        ActivityRule rule = JsonUtils.fromJson(activity.getRuleJson(), ActivityRule.class);
        return rule != null ? rule : new ActivityRule();
    }
}
