package com.shop.order.mq.consumer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.api.aftersale.dto.AftersaleItemMessage;
import com.shop.api.aftersale.event.AftersaleChangedEvent;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.support.AftersaleStatusMapping;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AFTERSALE_CHANGED 消费单测：明细状态同步、整单进入售后态/售后态内迁移/恢复/等待退款事件。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AftersaleEventConsumerTest {

    @Mock
    private MqConsumeService consumeService;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;

    private AftersaleEventConsumer consumer;

    private static final String NO = "260315011001000001";
    private static final String AS = "AS20260315001";

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        consumer = new AftersaleEventConsumer(consumeService, orderMapper, orderItemMapper,
                new AftersaleStatusMapping());
        when(consumeService.firstTime(any(), any(), any())).thenReturn(true);
        Order order = new Order();
        order.setOrderNo(NO);
        order.setStatus(20);
        when(orderMapper.selectOne(any())).thenReturn(order);
    }

    private AftersaleChangedEvent event(int type, int newStatus, Long itemId) {
        AftersaleChangedEvent e = AftersaleChangedEvent.builder()
                .aftersaleNo(AS).orderNo(NO).type(type).newStatus(newStatus).build();
        if (itemId != null) {
            AftersaleItemMessage line = new AftersaleItemMessage();
            line.setOrderItemId(itemId);
            e.setItems(List.of(line));
        }
        return e;
    }

    @Test
    void duplicateEvent_skipped() {
        when(consumeService.firstTime(any(), any(), any())).thenReturn(false);
        consumer.onAftersaleChanged(event(1, 10, null));
        verify(orderMapper, never()).enterAftersale(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void returnRefundApplied_syncsItemAndEnters61() {
        OrderItem item = new OrderItem();
        item.setId(7L);
        item.setOrderNo(NO);
        when(orderItemMapper.selectById(7L)).thenReturn(item);
        when(orderMapper.enterAftersale(eq(NO), eq(61))).thenReturn(1);

        consumer.onAftersaleChanged(event(2, 10, 7L));

        verify(orderItemMapper).forceAftersaleStatus(7L, 1, AS);
        verify(orderMapper).enterAftersale(NO, 61);
    }

    @Test
    void exchangeFinished_resumesOrderAndSyncsItemFinished() {
        OrderItem item = new OrderItem();
        item.setId(8L);
        item.setOrderNo(NO);
        when(orderItemMapper.selectById(8L)).thenReturn(item);
        when(orderMapper.resumeAftersaleByNo(NO)).thenReturn(1);

        consumer.onAftersaleChanged(event(3, 50, 8L));

        verify(orderItemMapper).forceAftersaleStatus(8L, 6, AS);
        verify(orderMapper).resumeAftersaleByNo(NO);
        verify(orderMapper, never()).enterAftersale(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void refundFinished_keepsOrderAwaitingRefundEvent() {
        consumer.onAftersaleChanged(event(1, 50, null));
        verify(orderMapper, never()).resumeAftersaleByNo(any());
        verify(orderMapper, never()).enterAftersale(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(orderMapper, never()).moveWithinAftersale(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void alreadyInAftersale_fallsBackToMoveWithin() {
        when(orderMapper.enterAftersale(NO, 62)).thenReturn(0);
        when(orderMapper.moveWithinAftersale(NO, 62)).thenReturn(1);

        consumer.onAftersaleChanged(event(3, 42, null));

        verify(orderMapper).moveWithinAftersale(NO, 62);
    }

    @Test
    void itemFromAnotherOrder_isSkipped() {
        OrderItem item = new OrderItem();
        item.setId(9L);
        item.setOrderNo("OTHER-NO");
        when(orderItemMapper.selectById(9L)).thenReturn(item);
        when(orderMapper.enterAftersale(eq(NO), eq(60))).thenReturn(1);

        consumer.onAftersaleChanged(event(1, 10, 9L));

        verify(orderItemMapper, never()).forceAftersaleStatus(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void orderMissing_throwsForRetry() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> consumer.onAftersaleChanged(event(1, 10, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND.getCode());
    }
}
