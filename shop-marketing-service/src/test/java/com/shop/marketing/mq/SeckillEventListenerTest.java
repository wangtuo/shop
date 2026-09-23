package com.shop.marketing.mq;

import com.shop.api.marketing.event.SeckillEvent;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.entity.SeckillOrder;
import com.shop.marketing.activity.mapper.SeckillOrderMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W4-4/B4：SECKILL_EVENT 消费闭环（cg_marketing_seckill，tag 1||2||3）。
 * 只监控核对，不重复执行业务扣减；偏差只落 action=0 告警，不反向改状态。
 */
@ExtendWith(MockitoExtension.class)
class SeckillEventListenerTest {

    @Mock private MqConsumeTemplate consumeTemplate;
    @Mock private SeckillOrderMapper seckillOrderMapper;
    @Mock private StockReconcileLogMapper reconcileLogMapper;
    @Mock private IdGenerator idGenerator;

    private SeckillEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new SeckillEventListener(consumeTemplate, seckillOrderMapper,
                reconcileLogMapper, idGenerator);
        // 默认：消费模板受理并执行业务回调（模拟首次消费，eventId INSERT 成功）
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(3)).run();
            return null;
        }).when(consumeTemplate).runOnce(anyString(), anyString(), anyString(), any(Runnable.class));
    }

    private SeckillEvent event(int type, int dbStatusSeed) {
        return SeckillEvent.builder()
                .activityId(10L).skuId(1L).userId(7L).orderNo("O1").type(type).qty(1L).build();
    }

    private SeckillOrder order(int status) {
        SeckillOrder o = new SeckillOrder();
        o.setOrderNo("O1");
        o.setActivityId(10L);
        o.setSkuId(1L);
        o.setUserId(7L);
        o.setQty(1);
        o.setStatus(status);
        return o;
    }

    @Test
    @DisplayName("tag=1/2/3 与 DB 状态一致：各受理一次，不落告警、不做任何库存写操作")
    void tag123_状态一致_仅监控() {
        assertEquals("1||2||3", listener.tag());
        assertEquals("cg_marketing_seckill", listener.consumerGroup());
        when(seckillOrderMapper.selectListByOrderNo("O1"))
                .thenReturn(List.of(order(0)), List.of(order(1)), List.of(order(2)));

        listener.onMessage(event(1, 0));
        listener.onMessage(event(2, 1));
        listener.onMessage(event(3, 2));

        assertEquals(1L, listener.getLockHeartbeats());
        verify(reconcileLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("tag=2 但 DB 仍为 0已锁定：落 scope=1 action=0 告警留痕，不反向改状态")
    void tag2_状态不一致_只告警不改状态() {
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of(order(0)));
        when(idGenerator.nextId()).thenReturn(123L);

        listener.onMessage(event(2, 0));

        ArgumentCaptor<StockReconcileLog> captor = ArgumentCaptor.forClass(StockReconcileLog.class);
        verify(reconcileLogMapper).insert(captor.capture());
        StockReconcileLog row = captor.getValue();
        assertEquals(1, row.getScope());
        assertEquals(0, row.getAction());
        assertEquals(10L, row.getActivityId());
        // 不允许反向 update：消费侧只 select + insert 留痕
        verify(seckillOrderMapper, never()).updateStatus(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("t_seckill_order 缺失：落告警留痕，不补单、不扣库存")
    void tag3_订单行缺失_告警() {
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of());
        when(idGenerator.nextId()).thenReturn(124L);

        listener.onMessage(event(3, 0));

        verify(reconcileLogMapper).insert(any(StockReconcileLog.class));
        verify(seckillOrderMapper, never()).updateStatus(anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("R4-25 多 SKU 单：按事件 skuId 精确定位对应行核对，不误报另一 SKU 行缺失/状态偏差")
    void 多SKU单_按skuId定位状态行() {
        SeckillOrder otherSku = order(1);
        otherSku.setSkuId(2L);
        // 同单两行：skuId=1 已锁定(0) 与 tag=1 期望一致；skuId=2 已扣减(1) 与本事件无关
        when(seckillOrderMapper.selectListByOrderNo("O1")).thenReturn(List.of(order(0), otherSku));

        listener.onMessage(event(1, 0));

        verify(reconcileLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("重复 eventId（模板不执行回调）：直接跳过，不查库不留痕")
    void 重复eventId_跳过() {
        org.mockito.Mockito.reset(consumeTemplate);
        // 模拟 INSERT IGNORE 0 行：回调不执行
        listener.onMessage(event(1, 0));
        verifyNoInteractions(seckillOrderMapper);
        verify(reconcileLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("非法/未知 type：ACK 丢弃，不进消费模板")
    void 非法type_丢弃() {
        listener.onMessage(SeckillEvent.builder().orderNo("OX").type(9).build());
        listener.onMessage(null);
        verifyNoInteractions(seckillOrderMapper);
        verify(reconcileLogMapper, never()).insert(any());
    }
}
