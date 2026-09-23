package com.shop.marketing.activity.support;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀 Redis 原子库存（design.md 4.1.3：独立秒杀库存，售罄快速失败）。
 *
 * <p>Lua 单脚本完成「检查 + 扣减」，避免 DECR 后回补的并发窗口；DB 库存作为最终一致与对账来源。
 *
 * <p>P1-5：多 SKU 下单走 {@link #tryAcquireBatch}，单条 Lua 原子预占——
 * 全部 key 都存在且余量充足才统一扣减，任一不足整体不动，杜绝前 N-1 个 key 已扣而 DB 回滚的泄漏。
 *
 * <p>P2-4：{@link #release} 在 Lua 内先判 key 存在性，键缺失（乱序消息 / Redis 被 flush）
 * 绝不 INCRBY 凭空造库存，只计数告警，由调用方按 DB 为准重建；RedissonClient 缺失的降级路径一律 warn。
 */
@Slf4j
@Component
public class SeckillStockClient {

    /** Redis key：mk:seckill:stock:{activityId}:{skuId} */
    private static final String KEY_PREFIX = "mk:seckill:stock:";
    /** 库存键未初始化（调用方应从 DB 初始化后重试） */
    public static final long NOT_INITIALIZED = Long.MIN_VALUE;
    /** 已售罄 */
    public static final long SOLD_OUT = -1L;
    /** 回补时键缺失：未 INCR，调用方需按 DB 重建/告警 */
    public static final long RELEASE_KEY_MISSING = Long.MIN_VALUE;

    private static final String ACQUIRE_LUA = """
            local v = redis.call('GET', KEYS[1])
            if (v == false) then return -9223372036854775808 end
            local stock = tonumber(v)
            local need = tonumber(ARGV[1])
            if (stock < need) then return -1 end
            return redis.call('DECRBY', KEYS[1], need)
            """;

    /**
     * 多 key 原子预占（P1-5）：
     * 先遍历全部 key 做存在性 + 余量检查，任一不满足立即返回且不触碰任何 key；
     * 全部满足才逐条 DECRBY（Lua 单脚本天然原子，执行期间不会插入其他命令）。
     * 返回所有 key 扣减后的最小剩余量。
     */
    private static final String ACQUIRE_BATCH_LUA = """
            local n = #KEYS
            for i = 1, n do
              local v = redis.call('GET', KEYS[i])
              if (v == false) then return -9223372036854775808 end
              if (tonumber(v) < tonumber(ARGV[i])) then return -1 end
            end
            local minRemain
            for i = 1, n do
              local left = redis.call('DECRBY', KEYS[i], ARGV[i])
              if (i == 1) then minRemain = left elseif left < minRemain then minRemain = left end
            end
            return minRemain
            """;

    /**
     * 回补 Lua（P2-4）：键不存在直接返回哨兵，不做 INCRBY，
     * 防止乱序 RELEASE / flush 后凭空造库存。
     */
    private static final String RELEASE_GUARDED_LUA = """
            local v = redis.call('GET', KEYS[1])
            if (v == false) then return -9223372036854775808 end
            return redis.call('INCRBY', KEYS[1], ARGV[1])
            """;

    /**
     * W4-4/B4：对账自愈强制覆盖 Lua——无条件 SET 余量键（区别于 setIfAbsent 的 initStock）。
     * 仅对账 Job 缺键重建 / DB 权威覆盖 / 停售置 SOLD_OUT 哨兵时调用，业务链路禁用。
     */
    private static final String FORCE_SET_LUA = """
            return redis.call('SET', KEYS[1], ARGV[1])
            """;

    /** 连续偏差计数 key：mk:seckill:recon:deviation:{activityId}:{skuId} */
    private static final String DEVIATION_KEY_PREFIX = "mk:seckill:recon:deviation:";
    /** 「已按 DB 覆盖重建、下一周期再偏即停售」标记 key */
    private static final String REBUILT_KEY_PREFIX = "mk:seckill:recon:rebuilt:";
    /** 偏差计数/标记保留期：自愈结束后自然清理 */
    private static final java.time.Duration DEVIATION_TTL = java.time.Duration.ofDays(7);

    private final ObjectProvider<RedissonClient> redissonProvider;

    /** release 命中键缺失的累计次数（监控/对账用） */
    private final AtomicLong releaseKeyMissingCount = new AtomicLong();
    /** RedissonClient 缺失走 DB 降级的累计次数（监控用） */
    private final AtomicLong redisUnavailableCount = new AtomicLong();

    public SeckillStockClient(ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
    }

    private static String key(long activityId, long skuId) {
        return KEY_PREFIX + activityId + ":" + skuId;
    }

    /** 初始化库存（仅键不存在时生效，防止活动配置被重复重置）。 */
    public void initStock(long activityId, long skuId, int totalStock) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            // P2-4：降级必须留痕，不能静默
            log.warn("RedissonClient 不可用，秒杀库存初始化跳过，依赖 DB 条件更新兜底 activityId={} skuId={}",
                    activityId, skuId);
            redisUnavailableCount.incrementAndGet();
            return;
        }
        client.getBucket(key(activityId, skuId), StringCodec.INSTANCE).setIfAbsent(String.valueOf(totalStock));
    }

    /**
     * 原子预占库存。
     * @return 扣减后剩余库存；{@link #SOLD_OUT} 售罄；{@link #NOT_INITIALIZED} 键不存在
     */
    public long tryAcquire(long activityId, long skuId, int qty) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            // Redis 不可用时不阻塞下单，由 DB 条件更新兜底；P2-4：必须 warn + 计数
            log.warn("RedissonClient 不可用，秒杀预占降级为仅 DB 条件更新 activityId={} skuId={} qty={}",
                    activityId, skuId, qty);
            redisUnavailableCount.incrementAndGet();
            return 0L;
        }
        RScript script = client.getScript(StringCodec.INSTANCE);
        Long ret = script.eval(RScript.Mode.READ_WRITE, ACQUIRE_LUA, RScript.ReturnType.INTEGER,
                Collections.singletonList(key(activityId, skuId)), String.valueOf(qty));
        return ret == null ? NOT_INITIALIZED : ret;
    }

    /**
     * 多 SKU 原子预占（P1-5）：所有 key 同一条 Lua 内校验 + 扣减。
     *
     * @param activityId 活动 ID
     * @param skuIds     SKU ID（与 qtys 一一对应，非空且等长）
     * @param qtys       各 SKU 预占数量
     * @return 全部扣减成功返回各 key 扣减后最小剩余；{@link #SOLD_OUT} 任一不足（所有 key 均不变）；
     *         {@link #NOT_INITIALIZED} 任一键不存在（所有 key 均不变，调用方初始化后整批重试）
     */
    public long tryAcquireBatch(long activityId, List<Long> skuIds, List<Integer> qtys) {
        if (skuIds == null || qtys == null || skuIds.isEmpty() || skuIds.size() != qtys.size()) {
            throw new IllegalArgumentException("skuIds/qtys 必须非空且等长");
        }
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            log.warn("RedissonClient 不可用，秒杀批量预占降级为仅 DB 条件更新 activityId={} skuCount={}",
                    activityId, skuIds.size());
            redisUnavailableCount.incrementAndGet();
            return 0L;
        }
        List<Object> keys = new ArrayList<>(skuIds.size());
        List<Object> args = new ArrayList<>(qtys.size());
        for (int i = 0; i < skuIds.size(); i++) {
            keys.add(key(activityId, skuIds.get(i)));
            args.add(String.valueOf(qtys.get(i)));
        }
        Long ret = client.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                ACQUIRE_BATCH_LUA, RScript.ReturnType.INTEGER, keys, args.toArray());
        return ret == null ? NOT_INITIALIZED : ret;
    }

    /**
     * 取消/超时回补库存（P2-4）：键缺失不 INCR，返回 {@link #RELEASE_KEY_MISSING} 并计数，
     * 由调用方按 DB 可售量重建；Redisson 缺失返回 {@link #RELEASE_KEY_MISSING}（无 Redis 可回补，DB 为准）。
     */
    public long release(long activityId, long skuId, int qty) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            log.warn("RedissonClient 不可用，秒杀库存回补跳过，以 DB 库存为准 activityId={} skuId={} qty={}",
                    activityId, skuId, qty);
            redisUnavailableCount.incrementAndGet();
            return RELEASE_KEY_MISSING;
        }
        Long ret = client.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                RELEASE_GUARDED_LUA, RScript.ReturnType.INTEGER,
                Collections.singletonList(key(activityId, skuId)), String.valueOf(qty));
        if (ret != null && ret == RELEASE_KEY_MISSING) {
            releaseKeyMissingCount.incrementAndGet();
            log.warn("秒杀回补跳过：Redis 库存键不存在（乱序消息或键被 flush），未凭空 INCR，"
                    + "等待按 DB 重建 activityId={} skuId={} qty={} missingTotal={}",
                    activityId, skuId, qty, releaseKeyMissingCount.get());
        }
        return ret == null ? RELEASE_KEY_MISSING : ret;
    }

    /** 读取 Redis 当前可售库存，键不存在返回 null（对账用）。 */
    public Long currentStock(long activityId, long skuId) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        Object v = client.getBucket(key(activityId, skuId), StringCodec.INSTANCE).get();
        return v == null ? null : Long.valueOf(v.toString());
    }

    /**
     * W4-4/B4：以 DB 为权威强制重建/覆盖 Redis 余量（Lua 原子 SET）。
     * 仅对账 Job 调用：缺键重建、连续两周期偏差覆盖均走本方法，不与业务 INCR/DECR 混用。
     *
     * @return true 已写入；false Redis 不可用（已 warn + 计数，下周期重试）
     */
    public boolean forceRebuild(long activityId, long skuId, long dbRemaining) {
        return forceSet(activityId, skuId, dbRemaining);
    }

    /**
     * W4-4/B2/B4：秒杀场次收口——余量键强制置 {@link #SOLD_OUT} 哨兵，
     * 此后 ACQUIRE Lua 恒返回 SOLD_OUT，新下单被快速失败。
     */
    public boolean markSoldOut(long activityId, long skuId) {
        return forceSet(activityId, skuId, SOLD_OUT);
    }

    private boolean forceSet(long activityId, long skuId, long value) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            log.warn("RedissonClient 不可用，秒杀库存强制覆盖跳过，等待下个对账周期 activityId={} skuId={} value={}",
                    activityId, skuId, value);
            redisUnavailableCount.incrementAndGet();
            return false;
        }
        client.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                FORCE_SET_LUA, RScript.ReturnType.STATUS,
                java.util.Collections.singletonList(key(activityId, skuId)), String.valueOf(value));
        return true;
    }

    /** 连续偏差计数 +1（带 7 天 TTL）；Redis 不可用时返回 0（本周期只告警，不升级处置）。 */
    public long bumpDeviation(long activityId, long skuId) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return 0L;
        }
        org.redisson.api.RAtomicLong counter = client.getAtomicLong(deviationKey(activityId, skuId));
        long n = counter.incrementAndGet();
        counter.expire(DEVIATION_TTL);
        return n;
    }

    /** Redis/DB 一致或处置完成后清零连续偏差计数。 */
    public void clearDeviation(long activityId, long skuId) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return;
        }
        client.getAtomicLong(deviationKey(activityId, skuId)).delete();
        client.getBucket(rebuiltKey(activityId, skuId), StringCodec.INSTANCE).delete();
    }

    /** 连续两周期偏差已按 DB 覆盖重建：打标，下一周期仍偏即升级停售。 */
    public void markRebuilt(long activityId, long skuId) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return;
        }
        org.redisson.api.RBucket<String> bucket = client.getBucket(rebuiltKey(activityId, skuId),
                StringCodec.INSTANCE);
        bucket.set("1");
        bucket.expire(DEVIATION_TTL);
    }

    public boolean isRebuilt(long activityId, long skuId) {
        RedissonClient client = redissonProvider.getIfAvailable();
        if (client == null) {
            return false;
        }
        return client.getBucket(rebuiltKey(activityId, skuId), StringCodec.INSTANCE).isExists();
    }

    private static String deviationKey(long activityId, long skuId) {
        return DEVIATION_KEY_PREFIX + activityId + ":" + skuId;
    }

    private static String rebuiltKey(long activityId, long skuId) {
        return REBUILT_KEY_PREFIX + activityId + ":" + skuId;
    }

    public long getReleaseKeyMissingCount() {
        return releaseKeyMissingCount.get();
    }

    public long getRedisUnavailableCount() {
        return redisUnavailableCount.get();
    }
}
