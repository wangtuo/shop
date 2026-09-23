package com.shop.framework.ratelimit;

import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link RateLimiter} Lua 结果 → 放行语义 + R-B4 Redis 故障策略矩阵。 */
class RateLimiterTest {

    @SuppressWarnings("unchecked")
    private RedissonClient clientWith(long luaResult) {
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), any(String.class), eq(RScript.ReturnType.INTEGER),
                anyList(), any(String.class), any(String.class), any(String.class))).thenReturn(luaResult);
        return client;
    }

    @SuppressWarnings("unchecked")
    private RedissonClient clientThrowing(RuntimeException e) {
        RedissonClient client = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(client.getScript(any(Codec.class))).thenReturn(script);
        when(script.eval(any(RScript.Mode.class), any(String.class), eq(RScript.ReturnType.INTEGER),
                anyList(), any(String.class), any(String.class), any(String.class))).thenThrow(e);
        return client;
    }

    @Test
    void lua返回1_放行() {
        RateLimiter limiter = new RateLimiter(clientWith(1L));
        assertTrue(limiter.tryAcquire("rl:x:1", 3, Duration.ofSeconds(60)));
        assertEquals(RateLimiter.Outcome.ALLOWED,
                limiter.tryAcquireWithPolicy("rl:x:1", 3, Duration.ofSeconds(60),
                        RedisFailurePolicy.FAIL_CLOSE));
    }

    @Test
    void lua返回0_拒绝() {
        RateLimiter limiter = new RateLimiter(clientWith(0L));
        assertFalse(limiter.tryAcquire("rl:x:1", 3, Duration.ofSeconds(60)));
        assertEquals(RateLimiter.Outcome.REJECTED_LIMIT,
                limiter.tryAcquireWithPolicy("rl:x:1", 3, Duration.ofSeconds(60),
                        RedisFailurePolicy.FAIL_OPEN));
    }

    @Test
    void 非法额度直接拒绝() {
        RateLimiter limiter = new RateLimiter(clientWith(1L));
        assertFalse(limiter.tryAcquire("rl:x:1", 0, Duration.ofSeconds(60)));
        assertFalse(limiter.tryAcquire("rl:x:1", -1, Duration.ofSeconds(60)));
    }

    @Test
    void redis故障_failClosed_拒绝不抛原生异常() {
        RateLimiter limiter = new RateLimiter(clientThrowing(new RedisException("ERR connection refused")));
        RateLimiter.Outcome outcome = limiter.tryAcquireWithPolicy(
                "rl:order:create:u:1", 5, Duration.ofSeconds(1), RedisFailurePolicy.FAIL_CLOSE);
        assertEquals(RateLimiter.Outcome.REDIS_DOWN_DENY, outcome);
        // 历史签名等价于 FAIL_CLOSE：false 而非让异常裸奔
        assertFalse(limiter.tryAcquire("rl:order:create:u:1", 5, Duration.ofSeconds(1)));
    }

    @Test
    void redis故障_资金读NoStale_同样拒绝禁降级() {
        RateLimiter limiter = new RateLimiter(clientThrowing(new RedisException("timeout")));
        assertEquals(RateLimiter.Outcome.REDIS_DOWN_DENY,
                limiter.tryAcquireWithPolicy("rl:funds:read:u:1", 1, Duration.ofSeconds(1),
                        RedisFailurePolicy.FAIL_CLOSE_NO_STALE));
    }

    @Test
    void redis故障_failOpen_降级放行() {
        RateLimiter limiter = new RateLimiter(clientThrowing(new RedisException("down")));
        assertEquals(RateLimiter.Outcome.REDIS_DOWN_ALLOW,
                limiter.tryAcquireWithPolicy("rl:product:cache:1", 10, Duration.ofSeconds(60),
                        RedisFailurePolicy.FAIL_OPEN));
    }

    @Test
    void 非redis类异常_原样抛出不吞bug() {
        RateLimiter limiter = new RateLimiter(clientThrowing(new IllegalStateException("bug")));
        assertThrows(IllegalStateException.class,
                () -> limiter.tryAcquireWithPolicy("rl:x:1", 3, Duration.ofSeconds(60),
                        RedisFailurePolicy.FAIL_CLOSE));
    }
}
