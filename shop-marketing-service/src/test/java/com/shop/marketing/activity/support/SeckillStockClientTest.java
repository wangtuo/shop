package com.shop.marketing.activity.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** SeckillStockClient 降级告警/计数与 Lua 入参契约（真实 Lua 原子性见 SeckillStockClientLuaIT）。 */
@ExtendWith(MockitoExtension.class)
class SeckillStockClientTest {

    @Mock private ObjectProvider<RedissonClient> redissonProvider;
    @Mock private RedissonClient client;
    @Mock private RScript script;

    private SeckillStockClient stockClient;

    @BeforeEach
    void setUp() {
        stockClient = new SeckillStockClient(redissonProvider);
    }

    @Test
    @DisplayName("批量预占：全部 key/qty 按序传入同一条 Lua，返回最小剩余")
    void batch_passesAllKeys() {
        when(redissonProvider.getIfAvailable()).thenReturn(client);
        when(client.getScript(any(StringCodec.class))).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), any(String.class), eq(RScript.ReturnType.INTEGER),
                anyList(), any(Object[].class))).thenReturn(5L);

        long ret = stockClient.tryAcquireBatch(10L, List.of(1L, 2L), List.of(3, 4));

        assertEquals(5L, ret);
        ArgumentCaptor<List<Object>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(script).eval(eq(RScript.Mode.READ_WRITE), any(String.class), eq(RScript.ReturnType.INTEGER),
                keysCaptor.capture(), any(Object[].class));
        List<Object> keys = keysCaptor.getValue();
        assertEquals(List.of("mk:seckill:stock:10:1", "mk:seckill:stock:10:2"), keys);
    }

    @Test
    @DisplayName("批量 Lua 脚本先全量校验后统一扣减，任一不足不扣任何 key（脚本要点）")
    void batch_luaChecksBeforeDecrement() {
        when(redissonProvider.getIfAvailable()).thenReturn(client);
        when(client.getScript(any(StringCodec.class))).thenReturn(script);
        ArgumentCaptor<String> luaCaptor = ArgumentCaptor.forClass(String.class);
        when(script.eval(eq(RScript.Mode.READ_WRITE), luaCaptor.capture(), eq(RScript.ReturnType.INTEGER),
                anyList(), any(Object[].class))).thenReturn(-1L);

        stockClient.tryAcquireBatch(10L, List.of(1L, 2L), List.of(1, 1));

        String lua = luaCaptor.getValue();
        // 两个循环分离：先遍历检查（含 GET 与 -1 售罄返回），后遍历 DECRBY
        int firstCheck = lua.indexOf("if (tonumber(v) < tonumber(ARGV[i])) then return -1 end");
        int firstDecr = lua.indexOf("DECRBY");
        assertTrue(firstCheck > 0 && firstCheck < firstDecr,
                "Lua 必须先完成全部 key 的余量检查再 DECRBY，实际脚本：" + lua);
    }

    @Test
    @DisplayName("P2-4 回补 Lua 先判键存在，缺失返回哨兵而非 INCR")
    void release_guardedLua() {
        when(redissonProvider.getIfAvailable()).thenReturn(client);
        when(client.getScript(any(StringCodec.class))).thenReturn(script);
        ArgumentCaptor<String> luaCaptor = ArgumentCaptor.forClass(String.class);
        when(script.eval(eq(RScript.Mode.READ_WRITE), luaCaptor.capture(), eq(RScript.ReturnType.INTEGER),
                anyList(), any(Object[].class))).thenReturn(SeckillStockClient.RELEASE_KEY_MISSING);

        long ret = stockClient.release(10L, 1L, 2);

        assertEquals(SeckillStockClient.RELEASE_KEY_MISSING, ret);
        String lua = luaCaptor.getValue();
        assertTrue(lua.contains("GET") && lua.indexOf("GET") < lua.indexOf("INCRBY"),
                "回补脚本必须先 GET 判存在再 INCRBY：" + lua);
        assertEquals(1L, stockClient.getReleaseKeyMissingCount());
    }

    @Test
    @DisplayName("P2-4 RedissonClient 缺失：预占降级 0、回补返回缺失哨兵，均计数不静默")
    void redisMissing_warnAndCount() {
        when(redissonProvider.getIfAvailable()).thenReturn(null);

        assertEquals(0L, stockClient.tryAcquire(10L, 1L, 2));
        assertEquals(SeckillStockClient.RELEASE_KEY_MISSING, stockClient.release(10L, 1L, 2));
        stockClient.initStock(10L, 1L, 10);
        assertEquals(3L, stockClient.getRedisUnavailableCount());
        assertEquals(0L, stockClient.getReleaseKeyMissingCount());
    }
}
