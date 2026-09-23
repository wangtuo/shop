package com.shop.marketing.job;

import com.shop.framework.id.IdGenerator;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillSku;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.activity.support.SeckillStockClient;
import com.shop.marketing.common.entity.StockReconcileLog;
import com.shop.marketing.common.mapper.StockReconcileLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W4-4/P1-2 秒杀对账三级自愈：
 * ①缺键即重建（不再 continue）②连续两周期偏差 DB 覆盖 ③重建后仍偏/DB 为负 → 停售。
 * 所有处置落 t_stock_reconcile_log（scope=1，action 0告警/1重建/2停售）。
 */
@ExtendWith(MockitoExtension.class)
class SeckillReconcileJobSelfHealTest {

    @Mock private SeckillSkuMapper seckillSkuMapper;
    @Mock private ActivityMapper activityMapper;
    @Mock private SeckillStockClient stockClient;
    @Mock private DistributedLockTemplate lockTemplate;
    @Mock private StockReconcileLogMapper reconcileLogMapper;
    @Mock private IdGenerator idGenerator;

    private SeckillReconcileJob job;

    @BeforeEach
    void setUp() {
        job = new SeckillReconcileJob(seckillSkuMapper, activityMapper, stockClient,
                lockTemplate, reconcileLogMapper, idGenerator);
        // 自愈锁直接放行（模拟抢到 Redisson 锁）；非在架分支在取锁前返回，故声明为宽松存根
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(lockTemplate).execute(any(String.class), any(Runnable.class));
    }

    private Activity activity(int status) {
        Activity a = new Activity();
        a.setId(10L);
        a.setType(10);
        a.setStatus(status);
        return a;
    }

    private SeckillSku sku(int total, int locked, int sold) {
        SeckillSku s = new SeckillSku();
        s.setId(100L);
        s.setActivityId(10L);
        s.setSkuId(1L);
        s.setTotalStock(total);
        s.setLockedStock(locked);
        s.setSoldStock(sold);
        return s;
    }

    private void runWith(SeckillSku s) {
        when(seckillSkuMapper.selectList(any())).thenReturn(List.of(s));
        job.reconcile();
    }

    private StockReconcileLog lastLog() {
        ArgumentCaptor<StockReconcileLog> c = ArgumentCaptor.forClass(StockReconcileLog.class);
        verify(reconcileLogMapper).insert(c.capture());
        return c.getValue();
    }

    @Test
    @DisplayName("一级：Redis key 缺失不再 continue——按 DB forceRebuild 并落 action=1")
    void 缺键_按DB重建_落痕() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(null);
        when(stockClient.forceRebuild(10L, 1L, 10)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(1L);

        runWith(sku(10, 0, 0));

        verify(stockClient).forceRebuild(10L, 1L, 10);
        verify(stockClient).clearDeviation(10L, 1L);
        StockReconcileLog row = lastLog();
        assertEquals(1, row.getScope());
        assertEquals(1, row.getAction());
        // 绝不停售
        verify(activityMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("缺键但 Redis 不可用：不虚假落重建痕，下周期再试")
    void 缺键_Redis不可用_不留痕() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(null);
        when(stockClient.forceRebuild(10L, 1L, 10)).thenReturn(false);

        runWith(sku(10, 0, 0));

        verify(reconcileLogMapper, never()).insert(any());
        verify(activityMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("二级前置：首个偏差周期只计数告警 action=0，不动 Redis、不停售")
    void 首次偏差_仅告警() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(8L);
        when(stockClient.bumpDeviation(10L, 1L)).thenReturn(1L);
        when(idGenerator.nextId()).thenReturn(2L);

        runWith(sku(10, 0, 0));

        verify(stockClient, never()).forceRebuild(anyLong(), anyLong(), anyLong());
        StockReconcileLog row = lastLog();
        assertEquals(0, row.getAction());
        assertEquals(-2L, row.getDeviation());
        verify(activityMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("二级：连续两周期偏差——DB 权威覆盖重建 + rebuilt 标记，落 action=1，不停售")
    void 连续两周期偏差_覆盖重建() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(8L);
        when(stockClient.bumpDeviation(10L, 1L)).thenReturn(2L);
        when(stockClient.isRebuilt(10L, 1L)).thenReturn(false);
        when(stockClient.forceRebuild(10L, 1L, 10)).thenReturn(true);
        when(idGenerator.nextId()).thenReturn(3L);

        runWith(sku(10, 0, 0));

        verify(stockClient).markRebuilt(10L, 1L);
        StockReconcileLog row = lastLog();
        assertEquals(1, row.getAction());
        verify(activityMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("三级：覆盖重建后下一周期仍偏——CAS 1→3 停售、全 SKU 置 SOLD_OUT、落 action=2")
    void 持续漂移_自动停售() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(8L);
        when(stockClient.bumpDeviation(10L, 1L)).thenReturn(3L);
        when(stockClient.isRebuilt(10L, 1L)).thenReturn(true);
        when(activityMapper.updateStatus(10L, 1, 3)).thenReturn(1);
        SeckillSku s1 = sku(10, 0, 0);
        SeckillSku s2 = sku(10, 0, 0);
        s2.setSkuId(2L);
        when(seckillSkuMapper.selectList(any())).thenReturn(List.of(s1), List.of(s1, s2));
        when(idGenerator.nextId()).thenReturn(4L);

        job.reconcile();

        verify(stockClient).markSoldOut(10L, 1L);
        verify(stockClient).markSoldOut(10L, 2L);
        verify(stockClient).clearDeviation(10L, 1L);
        verify(stockClient).clearDeviation(10L, 2L);
        StockReconcileLog row = lastLog();
        assertEquals(2, row.getAction());
    }

    @Test
    @DisplayName("三级保护：DB 可售为负（超卖）直接停售，不走覆盖重建")
    void DB为负_直接停售() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(1L);
        when(activityMapper.updateStatus(10L, 1, 3)).thenReturn(1);
        when(idGenerator.nextId()).thenReturn(5L);

        runWith(sku(10, 8, 4));

        verify(stockClient, never()).forceRebuild(anyLong(), anyLong(), anyLong());
        verify(stockClient).markSoldOut(eq(10L), eq(1L));
        assertEquals(2, lastLog().getAction());
    }

    @Test
    @DisplayName("停售 CAS 失败（已被其他路径翻态）：不置 SOLD_OUT、不落 action=2")
    void 停售CAS失败_不重复收口() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(8L);
        when(stockClient.bumpDeviation(10L, 1L)).thenReturn(3L);
        when(stockClient.isRebuilt(10L, 1L)).thenReturn(true);
        when(activityMapper.updateStatus(10L, 1, 3)).thenReturn(0);

        runWith(sku(10, 0, 0));

        verify(stockClient, never()).markSoldOut(anyLong(), anyLong());
        verify(reconcileLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("Redis/DB 一致：清零偏差计数，不留痕")
    void 一致_清零不留痕() {
        when(activityMapper.selectById(10L)).thenReturn(activity(1));
        when(stockClient.currentStock(10L, 1L)).thenReturn(10L);

        runWith(sku(10, 0, 0));

        verify(stockClient).clearDeviation(10L, 1L);
        verify(reconcileLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("非在架场次（已结束/已取消）：不核对不自愈，只清理残留计数")
    void 非在架_跳过() {
        when(activityMapper.selectById(10L)).thenReturn(activity(2));

        runWith(sku(10, 0, 0));

        verify(stockClient).clearDeviation(10L, 1L);
        verify(stockClient, never()).currentStock(anyLong(), anyLong());
        verify(stockClient, never()).forceRebuild(anyLong(), anyLong(), anyLong());
        verify(reconcileLogMapper, never()).insert(any());
    }
}
