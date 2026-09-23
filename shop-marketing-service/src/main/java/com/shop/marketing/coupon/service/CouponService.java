package com.shop.marketing.coupon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.marketing.enums.CouponIssueWays;
import com.shop.api.marketing.enums.CouponStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.entity.UserCoupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 券生命周期服务（design.md 4.3/4.3.1）：领取（五种发放方式）→ 未使用/锁定 → 已使用/过期/作废。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final ObjectProvider<RedissonClient> redissonProvider;

    /** 无客户端 requestNo 时的防连点窗口（P2-1：短 TTL，不再 24h 挡住第 N 张合法领取） */
    static final long DEBOUNCE_TTL_SECONDS = 3L;
    /** 客户端 requestNo 的幂等窗口（与框架 @Idempotent 默认一致） */
    private static final long REQUEST_NO_TTL_SECONDS = 24 * 3600L;

    /** C 端领券中心主动领取（无客户端请求号；仅 3s 短 TTL 防连点）。 */
    @Transactional(rollbackFor = Exception.class)
    public Long claim(Long userId, Long couponId) {
        return claim(userId, couponId, null);
    }

    /**
     * C 端领券中心主动领取（P2-1 修复）。
     * <ul>
     *   <li>带客户端 requestNo：幂等键为 user:coupon:requestNo（24h），DB UK 兜底重复请求；</li>
     *   <li>无 requestNo：仅 user:coupon 维度 3s 短 TTL 防连点，requestNo 用
     *       {@code claim-{userId}-{couponId}-{第N张}}，保证 perUserLimit&gt;1 的券可领多张，
     *       与 t_user_coupon UK(user_id,coupon_id,issue_way,request_no) 不再矛盾。</li>
     * </ul>
     */
    /**
     * C 端领券中心主动领取（P2-1 修复）。
     * <ul>
     *   <li>带客户端 requestNo：幂等键为 user:coupon:requestNo（24h），DB UK 兜底重复请求；</li>
     *   <li>无 requestNo：仅 user:coupon 维度 3s 短 TTL 防连点，requestNo 用
     *       {@code claim-{userId}-{couponId}-{第N张}}，保证 perUserLimit&gt;1 的券可领多张，
     *       与 t_user_coupon UK(user_id,coupon_id,issue_way,request_no) 不再矛盾。</li>
     * </ul>
     * 注意：@Transactional 必须标在控制器经代理进入的三参方法上（两参重载为自调用，
     * 其注解不会单独生效；两参方法保留注解仅为兼容未来经代理直接调用它的场景）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long claim(Long userId, Long couponId, String requestNo) {
        String clientRequestNo = requestNo == null ? null : requestNo.strip();
        String redisKey;
        Duration ttl;
        String dbRequestNo;
        if (clientRequestNo != null && !clientRequestNo.isEmpty()) {
            redisKey = "idem:marketing:coupon:claim:" + userId + ":" + couponId + ":" + clientRequestNo;
            ttl = Duration.ofSeconds(REQUEST_NO_TTL_SECONDS);
            dbRequestNo = clientRequestNo;
        } else {
            redisKey = "marketing:coupon:debounce:" + userId + ":" + couponId;
            ttl = Duration.ofSeconds(DEBOUNCE_TTL_SECONDS);
            // 第 N 张：以当前已持记录数 + 1 编号；并发同号由 DB UK 挡住，前端重试取到下一号
            dbRequestNo = "claim-" + userId + "-" + couponId + "-" + (countHeld(userId, couponId) + 1);
        }

        RedissonClient client = redissonProvider.getIfAvailable();
        RBucket<String> bucket = null;
        if (client != null) {
            bucket = client.getBucket(redisKey);
            if (!bucket.setIfAbsent("1", ttl)) {
                throw new BizException(ErrorCode.REPEAT_SUBMIT, "请求正在处理中，请勿重复提交");
            }
        } else {
            log.warn("RedissonClient 不可用，领券幂等/防连点降级为仅依赖 DB UK userId={} couponId={}",
                    userId, couponId);
        }
        try {
            return issue(userId, couponId, CouponIssueWays.ACTIVE_CLAIM.getCode(), dbRequestNo);
        } catch (RuntimeException ex) {
            // 与 IdempotentAspect 语义对齐：业务失败立即放行重试
            if (bucket != null) {
                try {
                    bucket.delete();
                } catch (RuntimeException cleanEx) {
                    log.warn("领券幂等标记清理失败 key={} err={}", redisKey, cleanEx.toString());
                }
            }
            throw ex;
        }
    }

    /**
     * 发券（活动发放/新人礼包/系统补偿/积分兑换复用）。
     * requestNo 为发放幂等流水；新人礼包每人 1 次，主动领取/活动按模板 perUserLimit，补偿/兑换不限。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long issue(Long userId, Long couponId, int issueWay, String requestNo) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getStatus() != 1) {
            throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券不存在或已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getReceiveStartTime()) || now.isAfter(coupon.getReceiveEndTime())) {
            throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "不在券领取时间内");
        }
        // 限领校验
        if (issueWay == CouponIssueWays.NEW_USER.getCode() && countHeld(userId, couponId) > 0) {
            throw new BizException(ErrorCode.COUPON_LIMIT, "新人礼包仅可领取一次");
        }
        Integer limit = coupon.getPerUserLimit();
        boolean unlimited = issueWay == CouponIssueWays.COMPENSATE.getCode()
                || issueWay == CouponIssueWays.POINTS_EXCHANGE.getCode();
        if (!unlimited && limit != null && limit > 0 && countHeld(userId, couponId) >= limit) {
            throw new BizException(ErrorCode.COUPON_LIMIT, "超出该优惠券每人限领数量");
        }
        // 库存条件扣减
        if (couponMapper.increaseReceived(couponId) == 0) {
            throw new BizException(ErrorCode.COUPON_LIMIT, "优惠券已领完");
        }
        UserCoupon uc = new UserCoupon();
        uc.setUserId(userId);
        uc.setCouponId(couponId);
        uc.setStatus(CouponStatuses.UNUSED.getCode());
        uc.setIssueWay(issueWay);
        uc.setRequestNo(requestNo);
        LocalDateTime start;
        LocalDateTime end;
        if (coupon.getValidType() == 2) {
            start = now;
            end = now.plusDays(coupon.getValidDays() == null ? 0 : coupon.getValidDays());
        } else {
            start = coupon.getValidStartTime();
            end = coupon.getValidEndTime();
        }
        uc.setValidStartTime(start);
        uc.setValidEndTime(end);
        try {
            userCouponMapper.insert(uc);
        } catch (DuplicateKeyException e) {
            couponMapper.decreaseReceived(couponId);
            throw new BizException(ErrorCode.REPEAT_SUBMIT, "请勿重复领券");
        }
        return uc.getId();
    }

    private long countHeld(Long userId, Long couponId) {
        return userCouponMapper.selectCount(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getCouponId, couponId));
    }

    /** 我的券列表（状态：0未使用 1已使用 2已过期 3已作废，null 全部）。 */
    public List<UserCoupon> myCoupons(Long userId, Integer status) {
        return userCouponMapper.selectList(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(status != null, UserCoupon::getStatus, status)
                .orderByDesc(UserCoupon::getId));
    }

    /** 领券中心：当前可领取的上架券（由 C 端按用户是否已领自行置灰）。 */
    public List<Coupon> claimCenter() {
        LocalDateTime now = LocalDateTime.now();
        return couponMapper.selectList(new LambdaQueryWrapper<Coupon>()
                .eq(Coupon::getStatus, 1)
                .le(Coupon::getReceiveStartTime, now)
                .ge(Coupon::getReceiveEndTime, now)
                .orderByDesc(Coupon::getId));
    }

    /** 试算用：批量加载用户券。 */
    public List<UserCoupon> loadByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userCouponMapper.selectBatchIds(ids);
    }

    /** 下单预核销：逐张条件更新，任一张失败整体回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public void lockCoupons(Long userId, String orderNo, List<Long> userCouponIds) {
        if (userCouponIds == null || userCouponIds.isEmpty()) {
            return;
        }
        for (Long id : userCouponIds) {
            if (userCouponMapper.lockCoupon(id, userId, orderNo) == 0) {
                throw new BizException(ErrorCode.COUPON_NOT_AVAILABLE, "优惠券不可用或已被使用：" + id);
            }
        }
    }

    /** 支付成功核销。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmCoupons(String orderNo, List<Long> userCouponIds) {
        if (userCouponIds == null) {
            return;
        }
        for (Long id : userCouponIds) {
            userCouponMapper.useCoupon(id, orderNo);
        }
    }

    /** 取消/超时释放：锁定券退回未使用（条件更新天然幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public void releaseCoupons(String orderNo, List<Long> userCouponIds) {
        if (userCouponIds == null) {
            return;
        }
        for (Long id : userCouponIds) {
            userCouponMapper.releaseCoupon(id, orderNo);
        }
    }

    /** 定时过期扫描，返回本次作废张数。 */
    @Transactional(rollbackFor = Exception.class)
    public int expireScanned() {
        return userCouponMapper.expireUnused(LocalDateTime.now());
    }
}
