package com.shop.marketing.activity.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 Redis（本地 docker shop-redis 127.0.0.1:6379）验证批量预占 Lua 的原子性。
 *
 * <p>无 Redis 环境（如离线 CI）整类跳过，不破坏构建。
 */
class SeckillStockClientLuaTest {

    private static final long ACTIVITY_ID = 990000001L;

    private static RedissonClient redis;
    private static SeckillStockClient stockClient;

    @BeforeAll
    static void setUp() {
        try {
            Config config = new Config();
            config.useSingleServer()
                    .setAddress("redis://127.0.0.1:6379")
                    .setConnectTimeout(500)
                    .setTimeout(500);
            redis = Redisson.create(config);
            redis.getBucket("mk:seckill:probe").get();
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "本地 Redis 不可用，跳过 Lua 原子性集成测试: " + e);
        }
        @SuppressWarnings("unchecked")
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        stockClient = new SeckillStockClient(provider);
        // 清场，避免历史数据干扰
        deleteKeys();
    }

    @AfterAll
    static void tearDown() {
        if (redis != null) {
            deleteKeys();
            redis.shutdown();
        }
    }

    private static String key(long skuId) {
        return "mk:seckill:stock:" + ACTIVITY_ID + ":" + skuId;
    }

    // 与 SeckillStockClient 一致，必须用 StringCodec：默认 JSON codec 会把 "10" 存成带引号的 "10"，
    // Lua tonumber() 将得到 nil，INCRBY 也会报 not an integer
    private static RBucket<String> bucket(long skuId) {
        return redis.getBucket(key(skuId), StringCodec.INSTANCE);
    }

    private static void deleteKeys() {
        for (long skuId = 1L; skuId <= 2L; skuId++) {
            bucket(skuId).delete();
        }
    }

    private void setStock(long skuId, long value) {
        bucket(skuId).set(String.valueOf(value));
    }

    private Long stock(long skuId) {
        Object v = bucket(skuId).get();
        return v == null ? null : Long.valueOf(v.toString());
    }

    @Test
    @DisplayName("P1-5 混合库存不足：所有 key 余量不变（Lua 内先全检后扣减）")
    void batch_someShort_allKeysUnchanged() {
        setStock(1L, 10L);
        setStock(2L, 1L);

        long ret = stockClient.tryAcquireBatch(ACTIVITY_ID, List.of(1L, 2L), List.of(2, 2));

        assertEquals(SeckillStockClient.SOLD_OUT, ret);
        assertEquals(10L, stock(1L), "充足 key 不应被扣减");
        assertEquals(1L, stock(2L), "不足 key 余量不变");
    }

    @Test
    @DisplayName("全部充足：多 key 同脚本扣减，返回最小剩余")
    void batch_allEnough_atomicDecrement() {
        setStock(1L, 10L);
        setStock(2L, 1L);

        long ret = stockClient.tryAcquireBatch(ACTIVITY_ID, List.of(1L, 2L), List.of(2, 1));

        assertEquals(0L, ret);
        assertEquals(8L, stock(1L));
        assertEquals(0L, stock(2L));
    }

    @Test
    @DisplayName("任一键不存在：返回未初始化哨兵，已存在的 key 余量不变")
    void batch_oneMissing_nothingTouched() {
        setStock(1L, 10L);
        bucket(2L).delete();

        long ret = stockClient.tryAcquireBatch(ACTIVITY_ID, List.of(1L, 2L), List.of(1, 1));

        assertEquals(SeckillStockClient.NOT_INITIALIZED, ret);
        assertEquals(10L, stock(1L));
        assertNull(stock(2L));
    }

    @Test
    @DisplayName("P2-4 回补缺失键：不凭空造库存，计数 +1；存在键正常回补")
    void release_missingKey_notCreated() {
        bucket(1L).delete();
        setStock(2L, 3L);
        long before = stockClient.getReleaseKeyMissingCount();

        long missingRet = stockClient.release(ACTIVITY_ID, 1L, 5);

        assertEquals(SeckillStockClient.RELEASE_KEY_MISSING, missingRet);
        assertNull(stock(1L), "缺失键绝不能被 INCRBY 凭空创建");
        assertEquals(before + 1, stockClient.getReleaseKeyMissingCount());

        assertEquals(8L, stockClient.release(ACTIVITY_ID, 2L, 5));
        assertEquals(8L, stock(2L));
    }

    @Test
    @DisplayName("initStock 仅在键不存在时生效，重复初始化不覆盖余量")
    void initStock_setIfAbsent() {
        redis.getBucket("mk:seckill:stock:" + ACTIVITY_ID + ":1").delete();
        stockClient.initStock(ACTIVITY_ID, 1L, 7);
        assertEquals(7L, stock(1L));
        stockClient.initStock(ACTIVITY_ID, 1L, 99);
        assertEquals(7L, stock(1L), "已存在键不能被初始化覆盖");
    }
}
