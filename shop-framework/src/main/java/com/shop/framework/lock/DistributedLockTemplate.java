package com.shop.framework.lock;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 看门狗的分布式锁模板，用于库存扣减、余额变动、状态机推进等强互斥场景。
 */
@Component
public class DistributedLockTemplate {

    /** 默认等待 3 秒 */
    private static final long DEFAULT_WAIT_SECONDS = 3L;
    /**
     * 租约 -1：启用 Redisson 看门狗自动续期（每 lockWatchdogTimeout/3 续一次，默认 30s 超时）。
     * P1-3 修复：此前显式传 30 秒租约会禁用看门狗，业务（含 GC/慢 SQL）执行超过 30s 时
     * 锁自动释放而事务未提交，第二个线程进入临界区。显式租约仅在明确需要限时兜底的场景使用。
     */
    private static final long WATCHDOG_LEASE = -1L;

    private final RedissonClient redissonClient;

    /** O6：锁获取路径 Redis 故障计数；字段注入可空（单测构造不受影响）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shop.framework.metrics.BizMetrics bizMetrics;

    public DistributedLockTemplate(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public <T> T execute(String key, Supplier<T> action) {
        return execute(key, DEFAULT_WAIT_SECONDS, WATCHDOG_LEASE, action);
    }

    public void execute(String key, Runnable action) {
        execute(key, () -> {
            action.run();
            return null;
        });
    }

    public <T> T execute(String key, long waitSeconds, long leaseSeconds, Supplier<T> action) {
        RLock lock = redissonClient.getLock(key);
        boolean locked;
        try {
            locked = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYSTEM_ERROR, "获取分布式锁被中断", e);
        } catch (RuntimeException e) {
            // O6：Redis 不可用导致的锁获取故障计数（fail-closed：原样上抛，不放行业务）。
            if (bizMetrics != null) {
                bizMetrics.redisFailure("lock");
            }
            throw e;
        }
        if (!locked) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "操作过于频繁，请稍后重试");
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
