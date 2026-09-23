package com.shop.marketing.inner;

import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.api.marketing.dto.PromotionConfirmCommand;
import com.shop.api.marketing.dto.PromotionLockCommand;
import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.marketing.engine.PriceEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 营销对内应用服务（MarketingClient 4 方法落地）。
 *
 * <p>lock/confirm/release 全部以 orderNo 幂等：重复 lock 直接成功；
 * confirm/release 找不到锁定记录或状态已推进也按成功返回（订单侧可安全重试）。
 *
 * <p>P1-6 修复：本类只负责「分布式锁包裹事务」——先获取 orderNo 维度的 Redisson 锁，
 * 再经 Spring 代理调用 {@link MarketingTxOps} 的 @Transactional 方法，事务提交/回滚完成后
 * 才在 finally 释放锁。秒杀等关键路径因此不存在「锁释放早于事务提交」窗口，同 orderNo 的
 * 并发重复下单/消费在锁上串行，后到者读到已提交的 t_marketing_lock 占坑行后幂等返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketingAppService {

    /** orderNo 维度锁：lock/confirm/release 互斥，覆盖秒杀等全部活动类型的关键路径 */
    private static final String ORDER_LOCK_PREFIX = "mk:lock:promo:";
    /** 等锁 10s：MQ 重复投递/补偿重试在首单提交后应很快拿到锁 */
    private static final long LOCK_WAIT_SECONDS = 10L;
    /** 租约 -1：启用 Redisson 看门狗自动续期，避免长事务期间锁过期 */
    private static final long WATCHDOG_LEASE = -1L;

    private final PriceEngine priceEngine;
    private final MarketingTxOps txOps;
    private final ObjectProvider<RedissonClient> redissonProvider;

    /** 试算：只读，不落库不锁资源。 */
    public PriceCalcResult calculate(PriceCalcCommand cmd) {
        return priceEngine.calculate(cmd);
    }

    /** 下单：orderNo 分布式锁内开事务，预核销券 + 锁活动资源。 */
    public void lock(PromotionLockCommand cmd) {
        runWithOrderLock(cmd.getOrderNo(), () -> txOps.lockInTx(cmd));
    }

    /** 支付成功：orderNo 锁内开事务推进资源状态。 */
    public void confirm(PromotionConfirmCommand cmd) {
        runWithOrderLock(cmd.getOrderNo(), () -> txOps.confirmInTx(cmd));
    }

    /** 取消/超时：orderNo 锁内开事务释放资源。 */
    public void release(PromotionReleaseCommand cmd) {
        runWithOrderLock(cmd.getOrderNo(), () -> txOps.releaseInTx(cmd));
    }

    /**
     * 锁包裹事务：获取锁 → 执行业务事务（代理方法内部完成提交/回滚）→ 释放锁。
     * RedissonClient 缺失时降级为无锁执行并 warn（正确性退化为 DB 条件更新/UK 兜底）。
     */
    private void runWithOrderLock(String orderNo, Runnable action) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            log.warn("RedissonClient 不可用，营销编排降级为无分布式锁执行（依赖 DB 条件更新/UK 兜底） orderNo={}",
                    orderNo);
            action.run();
            return;
        }
        RLock lock = client.getLock(ORDER_LOCK_PREFIX + orderNo);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, WATCHDOG_LEASE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, "获取营销分布式锁被中断 orderNo=" + orderNo, e);
        }
        if (!locked) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试 orderNo=" + orderNo);
        }
        try {
            // 此处 action 内部为独立 @Transactional 代理调用，返回时事务已提交完成，随后才 unlock
            action.run();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
