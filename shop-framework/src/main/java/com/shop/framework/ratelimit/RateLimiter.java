package com.shop.framework.ratelimit;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis ZSET 滑动窗口限流器（原子 Lua）：
 * <ol>
 *   <li>ZREMRANGEBYSCORE 清理窗口外请求；</li>
 *   <li>ZCARD 已达 permits → 拒绝（0），不写入；</li>
 *   <li>否则 ZADD 当前请求（成员为 时间戳-INCR 序号，天然唯一）并 EXPIRE 续期。</li>
 * </ol>
 * 时间戳由调用方传入（毫秒）；序号 key 跟随主 key 过期，避免长期残留。
 *
 * <p>Redis 故障行为（R-B4）：不再让 Redisson 原生异常裸奔（历史上直接冒泡成 10009 裸 500），
 * 由 {@link #tryAcquireWithPolicy(String, long, Duration, RedisFailurePolicy)} 按策略返回
 * {@link Outcome}：fail-closed → {@link Outcome#REDIS_DOWN_DENY}，fail-open →
 * {@link Outcome#REDIS_DOWN_ALLOW}。非 Redis 类编程错误仍原样抛出，避免吞 bug。
 */
@Component
public class RateLimiter {

    private static final String LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local permits = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            if tonumber(redis.call('ZCARD', key)) >= permits then
                return 0
            end
            local seq = redis.call('INCR', key .. ':seq')
            redis.call('ZADD', key, now, tostring(now) .. '-' .. tostring(seq))
            redis.call('EXPIRE', key, math.ceil(window / 1000) + 1)
            redis.call('EXPIRE', key .. ':seq', math.ceil(window / 1000) + 1)
            return 1
            """;

    private final RedissonClient redissonClient;

    public RateLimiter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试在窗口内占用一次额度（历史签名，等价于
     * {@link RedisFailurePolicy#FAIL_CLOSE} 策略：Redis 故障时返回 false 而非抛出原生异常）。
     *
     * @param redisKey      完整 Redis key
     * @param permits       窗口允许次数
     * @param windowSize    窗口长度
     * @return true=放行；false=已达上限或 Redis 故障（fail-closed）
     */
    public boolean tryAcquire(String redisKey, long permits, Duration windowSize) {
        return tryAcquireWithPolicy(redisKey, permits, windowSize, RedisFailurePolicy.FAIL_CLOSE)
                == Outcome.ALLOWED;
    }

    /**
     * 策略化占用额度（R-B4）。
     *
     * @return {@link Outcome} 四态：放行 / 触达窗口上限 / Redis 故障拒绝 / Redis 故障降级放行
     */
    public Outcome tryAcquireWithPolicy(String redisKey, long permits, Duration windowSize,
                                        RedisFailurePolicy policy) {
        if (permits <= 0) {
            return Outcome.REJECTED_LIMIT;
        }
        Long allowed;
        try {
            allowed = redissonClient.getScript(StringCodec.INSTANCE).eval(
                    RScript.Mode.READ_WRITE,
                    LUA,
                    RScript.ReturnType.INTEGER,
                    List.of(redisKey),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(windowSize.toMillis()),
                    String.valueOf(permits));
        } catch (RuntimeException e) {
            if (isRedisFailure(e)) {
                // fail-closed（含资金读 NO_STALE）→ 拒绝；仅显式 FAIL_OPEN 的读缓存类才降级放行。
                return policy == RedisFailurePolicy.FAIL_OPEN
                        ? Outcome.REDIS_DOWN_ALLOW
                        : Outcome.REDIS_DOWN_DENY;
            }
            throw e;
        }
        return allowed != null && allowed == 1L ? Outcome.ALLOWED : Outcome.REJECTED_LIMIT;
    }

    /** 判定异常（含 cause 链）是否为 Redis 基础设施故障，而非脚本参数等编程错误。 */
    private boolean isRedisFailure(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 5 && cur != null; i++) {
            // RedisConnectionException/RedisTimeoutException/RedisLoadingException 等均为其子类
            if (cur instanceof RedisException) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /** 限流判定结果四态。 */
    public enum Outcome {
        /** 桶内放行。 */
        ALLOWED,
        /** 触达窗口上限，拒绝（10007 + 注解 message）。 */
        REJECTED_LIMIT,
        /** Redis 故障且策略 fail-closed：拒绝（抛可配 redisDownCode，默认 10007）。 */
        REDIS_DOWN_DENY,
        /** Redis 故障且策略 fail-open：降级放行（仅读缓存类允许，切面照常执行业务）。 */
        REDIS_DOWN_ALLOW
    }
}
