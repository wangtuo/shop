package com.shop.marketing.activity.bargain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.api.marketing.enums.ActivityTypes;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.SkuDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.framework.feign.FeignResults;
import com.shop.marketing.activity.bargain.dto.BargainDetailVO;
import com.shop.marketing.activity.bargain.dto.HelpCutVO;
import com.shop.marketing.activity.bargain.entity.BargainHelp;
import com.shop.marketing.activity.bargain.mapper.BargainHelpMapper;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.BargainRecord;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.BargainRecordMapper;
import com.shop.marketing.activity.support.ActivityRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 砍价玩法（design §4.1.3，卡 B3）。
 * 状态机：0 砍价中 --好友帮砍(current 单调下行至 floor)--&gt; 达底价 --普通下单 markDealt--&gt; 1 已成交；
 * 0 --超过 expire_time--&gt; 2 已失效（BargainExpireJob 条件更新，成交与过期互斥）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BargainService {

    /** 规则缺省：砍价有效期 24h */
    static final int DEFAULT_EXPIRE_HOURS = 24;
    /** 规则缺省：帮砍人数上限 */
    static final int DEFAULT_HELP_LIMIT = 10;
    /** 规则缺省：单刀最小 1 分 */
    static final long DEFAULT_CUT_MIN_FEN = 1L;

    private static final String LOCK_PREFIX = "mk:lock:bargain:";

    private final ActivityMapper activityMapper;
    private final BargainRecordMapper recordMapper;
    private final BargainHelpMapper helpMapper;
    private final BargainTxOps txOps;
    private final ProductClient productClient;
    private final ObjectProvider<RedissonClient> redissonProvider;

    /**
     * 发起砍价：每用户每活动 uk_user_activity 仅一条；存在进行中记录时幂等返回旧记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long startBargain(Long userId, Long activityId) {
        Activity activity = validateOngoing(activityId, ActivityTypes.BARGAIN);
        BargainRecord existed = recordMapper.selectOne(new LambdaQueryWrapper<BargainRecord>()
                .eq(BargainRecord::getUserId, userId)
                .eq(BargainRecord::getActivityId, activityId));
        if (existed != null) {
            if (existed.getStatus() == 0 && existed.getExpireTime().isAfter(LocalDateTime.now())) {
                return existed.getId();
            }
            throw new BizException(ErrorCode.CONFLICT, "您已参与过该砍价活动，不能重复发起");
        }
        ActivityRule rule = parseRule(activity);
        Long origin = rule.getOriginPriceFen();
        Long skuId = rule.getBargainSkuId();
        if (origin == null && skuId != null) {
            SkuDTO sku = FeignResults.unwrap(productClient.getSku(skuId));
            origin = sku == null ? null : sku.getSalePriceFen();
        }
        Long floor = rule.getFloorPriceFen();
        if (origin == null || origin <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "砍价原价未配置或非法");
        }
        if (floor == null || floor <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "砍价底价未配置或非法");
        }
        if (floor >= origin) {
            throw new BizException(ErrorCode.PARAM_INVALID, "砍价底价必须低于原价");
        }
        int hours = rule.getBargainExpireHours() != null && rule.getBargainExpireHours() > 0
                ? rule.getBargainExpireHours() : DEFAULT_EXPIRE_HOURS;

        BargainRecord record = new BargainRecord();
        record.setActivityId(activityId);
        record.setSkuId(skuId);
        record.setUserId(userId);
        record.setOriginPriceFen(origin);
        record.setFloorPriceFen(floor);
        record.setCurrentPriceFen(origin);
        record.setHelpCount(0);
        record.setStatus(0);
        record.setExpireTime(LocalDateTime.now().plusHours(hours));
        record.setVersion(0);
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            BargainRecord raced = recordMapper.selectOne(new LambdaQueryWrapper<BargainRecord>()
                    .eq(BargainRecord::getUserId, userId)
                    .eq(BargainRecord::getActivityId, activityId));
            if (raced != null && raced.getStatus() == 0 && raced.getExpireTime().isAfter(LocalDateTime.now())) {
                return raced.getId();
            }
            throw new BizException(ErrorCode.CONFLICT, "您已参与过该砍价活动，不能重复发起");
        }
        return record.getId();
    }

    /**
     * 好友帮砍：分布式锁串行化同一记录；红锁不可用时降级为 DB 乐观锁 + uk 兜底。
     *
     * @return 砍后价等结果
     */
    public HelpCutVO helpCut(Long helperUserId, Long recordId) {
        BargainRecord first = recordMapper.selectById(recordId);
        if (first == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "砍价记录不存在");
        }
        if (first.getUserId().equals(helperUserId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能给自己砍价");
        }
        return runWithLock(LOCK_PREFIX + recordId, () -> {
            BargainRecord record = recordMapper.selectById(recordId);
            LocalDateTime now = LocalDateTime.now();
            if (record.getStatus() != 0 || !record.getExpireTime().isAfter(now)) {
                throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "砍价已结束或已失效");
            }
            ActivityRule rule = parseRule(validateOngoing(record.getActivityId(), ActivityTypes.BARGAIN));
            if (record.getCurrentPriceFen() <= record.getFloorPriceFen()) {
                throw new BizException(ErrorCode.CONFLICT, "已砍到底价，无需再砍");
            }
            int limit = rule.getBargainHelpLimit() != null && rule.getBargainHelpLimit() > 0
                    ? rule.getBargainHelpLimit() : DEFAULT_HELP_LIMIT;
            if (record.getHelpCount() >= limit) {
                throw new BizException(ErrorCode.LIMIT_PURCHASE, "帮砍人数已达上限");
            }
            long min = rule.getBargainCutMinFen() != null && rule.getBargainCutMinFen() > 0
                    ? rule.getBargainCutMinFen() : DEFAULT_CUT_MIN_FEN;
            long max = rule.getBargainCutMaxFen() != null && rule.getBargainCutMaxFen() >= min
                    ? rule.getBargainCutMaxFen() : defaultMaxCut(record, limit, min);
            long cut = deterministicCut(recordId, record.getHelpCount(), min, max);
            long remaining = record.getCurrentPriceFen() - record.getFloorPriceFen();
            long effective = Math.min(cut, remaining);
            long newPrice = record.getCurrentPriceFen() - effective;

            BargainHelp help = new BargainHelp();
            help.setRecordId(recordId);
            help.setHelperUserId(helperUserId);
            help.setCutFen(effective);
            txOps.applyCut(record, help, newPrice);

            return new HelpCutVO(effective, newPrice, record.getFloorPriceFen(),
                    record.getHelpCount() + 1, newPrice <= record.getFloorPriceFen());
        });
    }

    /** 砍价详情（C 端分享页）。 */
    public BargainDetailVO detail(Long recordId) {
        BargainRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "砍价记录不存在");
        }
        int helpLimit = DEFAULT_HELP_LIMIT;
        Activity activity = activityMapper.selectById(record.getActivityId());
        if (activity != null) {
            ActivityRule rule = parseRule(activity);
            if (rule.getBargainHelpLimit() != null && rule.getBargainHelpLimit() > 0) {
                helpLimit = rule.getBargainHelpLimit();
            }
        }
        List<BargainHelp> helps = helpMapper.selectList(new LambdaQueryWrapper<BargainHelp>()
                .eq(BargainHelp::getRecordId, recordId)
                .orderByAsc(BargainHelp::getId));
        BargainDetailVO vo = new BargainDetailVO();
        vo.setRecordId(record.getId());
        vo.setActivityId(record.getActivityId());
        vo.setSkuId(record.getSkuId());
        vo.setUserId(record.getUserId());
        vo.setOriginPriceFen(record.getOriginPriceFen());
        vo.setFloorPriceFen(record.getFloorPriceFen());
        vo.setCurrentPriceFen(record.getCurrentPriceFen());
        vo.setHelpCount(record.getHelpCount());
        vo.setHelpLimit(helpLimit);
        vo.setStatus(record.getStatus());
        vo.setOrderNo(record.getOrderNo());
        vo.setReachedFloor(record.getCurrentPriceFen() <= record.getFloorPriceFen());
        long remain = Duration.between(LocalDateTime.now(), record.getExpireTime()).getSeconds();
        vo.setRemainSeconds(Math.max(remain, 0L));
        vo.setHelps(helps.stream()
                .map(h -> new BargainDetailVO.HelpView(h.getHelperUserId(), h.getCutFen()))
                .toList());
        return vo;
    }

    /**
     * 达底价成交预占（C 端普通下单 orderType=1 的营销锁定阶段调用，orderNo 幂等锚点）。
     * 仅 status=0、未过期且 current&lt;=floor 可 CAS 为 1；取消单不回滚（记录保持已成交）。
     *
     * @return 是否预占成功
     */
    public boolean markDealt(String orderNo, Long userId, Long activityId) {
        int rows = recordMapper.update(null, new LambdaUpdateWrapper<BargainRecord>()
                .eq(BargainRecord::getUserId, userId)
                .eq(BargainRecord::getActivityId, activityId)
                .eq(BargainRecord::getStatus, 0)
                .gt(BargainRecord::getExpireTime, LocalDateTime.now())
                .apply("current_price_fen <= floor_price_fen")
                .set(BargainRecord::getStatus, 1)
                .set(BargainRecord::getOrderNo, orderNo));
        if (rows == 0) {
            log.warn("砍价成交预占失败：未达底价/已过期/已成交 userId={} activityId={} orderNo={}",
                    userId, activityId, orderNo);
        }
        return rows > 0;
    }

    /** 过期扫描：status 0→2 条件更新，与 markDealt 成交 CAS 互斥。返回过期条数。 */
    public int expireScan() {
        return recordMapper.update(null, new LambdaUpdateWrapper<BargainRecord>()
                .eq(BargainRecord::getStatus, 0)
                .lt(BargainRecord::getExpireTime, LocalDateTime.now())
                .set(BargainRecord::getStatus, 2)
                .last("LIMIT 200"));
    }

    /**
     * 确定性伪随机砍额（seed = recordId + helpCount）：同一记录第 N 刀重算结果一致，可单测钉死；
     * SplitMix64 混洗保证不同记录/刀次分布均匀，结果落在 [minFen, maxFen] 闭区间。
     */
    static long deterministicCut(long recordId, int helpCount, long minFen, long maxFen) {
        if (maxFen <= minFen) {
            return minFen;
        }
        long seed = recordId * 1_000_003L + helpCount + 0x9E3779B97F4A7C15L;
        seed = (seed ^ (seed >>> 30)) * 0xbf58476d1ce4e5b9L;
        seed = (seed ^ (seed >>> 27)) * 0x94d049bb133111ebL;
        long z = seed ^ (seed >>> 31);
        z &= Long.MAX_VALUE;
        long span = maxFen - minFen + 1;
        return minFen + z % span;
    }

    private long defaultMaxCut(BargainRecord record, int limit, long min) {
        long avg = (record.getOriginPriceFen() - record.getFloorPriceFen()) / Math.max(limit, 1);
        return Math.max(avg, min);
    }

    private Activity validateOngoing(Long activityId, ActivityTypes expect) {
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null
                || activity.getType() != expect.getCode()
                || activity.getStatus() != 1) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "活动不存在或未在进行中");
        }
        LocalDateTime now = LocalDateTime.now();
        if (activity.getStartTime() != null && now.isBefore(activity.getStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "活动尚未开始");
        }
        if (activity.getEndTime() != null && now.isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "活动已结束");
        }
        return activity;
    }

    private ActivityRule parseRule(Activity activity) {
        ActivityRule rule = JsonUtils.fromJson(activity.getRuleJson(), ActivityRule.class);
        return rule != null ? rule : new ActivityRule();
    }

    private <T> T runWithLock(String key, Supplier<T> action) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            log.warn("RedissonClient 不可用，砍价降级为 DB 乐观锁 + uk 兜底执行 key={}", key);
            return action.get();
        }
        RLock lock = client.getLock(key);
        boolean locked;
        try {
            locked = lock.tryLock(3, -1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, "获取砍价锁被中断 recordId=" + key, e);
        }
        if (!locked) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "帮砍人数过多，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
