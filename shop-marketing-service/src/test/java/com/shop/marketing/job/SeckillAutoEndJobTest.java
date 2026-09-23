package com.shop.marketing.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillOrder;
import com.shop.marketing.activity.entity.SeckillSku;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.SeckillOrderMapper;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.activity.service.SeckillService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W4-4/B2：秒杀到点自动结束 Job——CAS 1→2 成功才收口：
 * 先释放未支付预占（复用 SeckillService.release 幂等原语），再全 SKU 置 SOLD_OUT；
 * 双跑（CAS=0）整体跳过，保证只释放一次。
 */
@ExtendWith(MockitoExtension.class)
class SeckillAutoEndJobTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private SeckillSkuMapper seckillSkuMapper;
    @Mock private SeckillOrderMapper seckillOrderMapper;
    @Mock private SeckillStockClient stockClient;
    @Mock private SeckillService seckillService;
    @Mock private StockReconcileLogMapper reconcileLogMapper;
    @Mock private IdGenerator idGenerator;

    private SeckillAutoEndJob job;

    @BeforeEach
    void setUp() {
        job = new SeckillAutoEndJob(activityMapper, seckillSkuMapper, seckillOrderMapper,
                stockClient, seckillService, reconcileLogMapper, idGenerator);
    }

    private Activity due(long id) {
        Activity a = new Activity();
        a.setId(id);
        a.setType(10);
        a.setStatus(1);
        return a;
    }

    private SeckillSku sku(long activityId, long skuId) {
        SeckillSku s = new SeckillSku();
        s.setId(skuId * 10);
        s.setActivityId(activityId);
        s.setSkuId(skuId);
        s.setTotalStock(5);
        return s;
    }

    private SeckillOrder pending(long activityId, String orderNo, int qty) {
        SeckillOrder o = new SeckillOrder();
        o.setActivityId(activityId);
        o.setSkuId(1L);
        o.setOrderNo(orderNo);
        o.setQty(qty);
        o.setStatus(0);
        return o;
    }

    @Test
    @DisplayName("到点场次：CAS 1→2 后释放全部 status=0 预占（逐单留痕 scope=2 action=3），再 SOLD_OUT 收口")
    void 到点_CAS成功_释放并收口() {
        when(activityMapper.selectSeckillDue()).thenReturn(List.of(due(10L)));
        when(activityMapper.updateStatus(10L, 1, 2)).thenReturn(1);
        when(seckillOrderMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(pending(10L, "O1", 1), pending(10L, "O2", 2)));
        when(seckillSkuMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(sku(10L, 1L), sku(10L, 2L)));
        when(idGenerator.nextId()).thenReturn(100L, 101L);

        job.autoEnd();

        verify(seckillService).release("O1");
        verify(seckillService).release("O2");
        verify(stockClient).markSoldOut(10L, 1L);
        verify(stockClient).markSoldOut(10L, 2L);
        ArgumentCaptor<StockReconcileLog> c = ArgumentCaptor.forClass(StockReconcileLog.class);
        verify(reconcileLogMapper, org.mockito.Mockito.times(2)).insert(c.capture());
        for (StockReconcileLog row : c.getAllValues()) {
            assertEquals(2, row.getScope());
            assertEquals(3, row.getAction());
            assertEquals(10L, row.getActivityId());
        }
    }

    @Test
    @DisplayName("幂等双跑：第二次扫描 CAS 0 行（已结束）→ 不释放、不收口、不留痕")
    void 双跑_CAS失败_只释放一次() {
        when(activityMapper.selectSeckillDue()).thenReturn(List.of(due(10L)));
        when(activityMapper.updateStatus(10L, 1, 2)).thenReturn(0);

        job.autoEnd();
        // 模拟下一周期重复扫描（上一执行者已翻态）
        job.autoEnd();

        verify(seckillService, never()).release(anyString());
        verify(stockClient, never()).markSoldOut(anyLong(), anyLong());
        verify(reconcileLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("无到点场次：无任何动作")
    void 无到点_空转() {
        when(activityMapper.selectSeckillDue()).thenReturn(List.of());
        job.autoEnd();
        verifyNoInteractions(seckillService);
        verifyNoInteractions(stockClient);
        verify(reconcileLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("场次无悬挂预占：仍做 Redis 收口，不留 action=3 痕")
    void 到点_无预占_仅收口() {
        when(activityMapper.selectSeckillDue()).thenReturn(List.of(due(10L)));
        when(activityMapper.updateStatus(10L, 1, 2)).thenReturn(1);
        when(seckillOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(seckillSkuMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(sku(10L, 1L)));

        job.autoEnd();

        verify(seckillService, never()).release(anyString());
        verify(stockClient).markSoldOut(10L, 1L);
        verify(reconcileLogMapper, never()).insert(any());
    }
}
