package com.shop.order.order.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.order.cart.entity.CartItem;
import com.shop.order.cart.mapper.CartItemMapper;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.invoice.mapper.OrderInvoiceMapper;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.order.event.OrderCompletedEvent;
import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.order.mq.message.OrderDelayMessage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落库器单测（design 5.3.2）：主单/明细/发票顺序插入，购物车仅在有 ID 时清理。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderPersisterTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderInvoiceMapper invoiceMapper;
    @Mock
    private CartItemMapper cartItemMapper;
    @Mock
    private OutboxPublisher outboxPublisher;

    private OrderPersister persister;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CartItem.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        persister = new OrderPersister(orderMapper, orderItemMapper, invoiceMapper,
                cartItemMapper, outboxPublisher);
    }

    private Order order() {
        Order o = new Order();
        o.setOrderNo("O1");
        o.setUserId(1001L);
        return o;
    }

    private OrderItem item(long skuId) {
        OrderItem i = new OrderItem();
        i.setSkuId(skuId);
        i.setOrderNo("O1");
        return i;
    }

    @Test
    void persist_fullAggregate_insertsAllAndClearsCart() {
        OrderInvoice invoice = new OrderInvoice();
        invoice.setOrderNo("O1");
        OrderAggregate aggregate = OrderAggregate.builder()
                .order(order())
                .items(List.of(item(11L), item(12L)))
                .invoice(invoice)
                .expirePaySeconds(1800L)
                .cartIdsToClear(List.of(1L, 2L, 3L))
                .build();

        persister.persist(aggregate, new OrderCreatedEvent(), new OrderDelayMessage());

        verify(orderMapper).insert(any(Order.class));
        verify(orderItemMapper, times(2)).insert(any(OrderItem.class));
        verify(invoiceMapper).insert(any(OrderInvoice.class));
        verify(cartItemMapper).update(any(), any());
        // ORDER_CREATED 普通事件 + 支付超时延时事件同事务登记
        verify(outboxPublisher).publish(any(), any(), any(), eq("O1"));
        verify(outboxPublisher).publishDelay(any(), any(), any(), eq("O1"), eq(1800L));
    }

    @Test
    void persist_noInvoiceAndNoCartIds_skipsBoth() {
        OrderAggregate aggregate = OrderAggregate.builder()
                .order(order())
                .items(List.of(item(11L)))
                .invoice(null)
                .expirePaySeconds(0L)
                .cartIdsToClear(List.of())
                .build();

        persister.persist(aggregate, new OrderCreatedEvent(), new OrderDelayMessage());

        verify(orderMapper).insert(any(Order.class));
        verify(orderItemMapper).insert(any(OrderItem.class));
        verify(invoiceMapper, never()).insert(any());
        verify(cartItemMapper, never()).update(any(), any());
        verify(outboxPublisher).publish(any(), any(), any(), eq("O1"));
    }

    @Test
    void persist_nullCartIds_skipsCartDelete() {
        OrderAggregate aggregate = OrderAggregate.builder()
                .order(order())
                .items(List.of())
                .build();

        persister.persist(aggregate, new OrderCreatedEvent(), new OrderDelayMessage());

        verify(orderMapper).insert(any(Order.class));
        verify(cartItemMapper, never()).update(any(), any());
    }

    @Test
    void stateTransitions_zeroRows_registerNoEvent_oneRowRegistersEvents() {
        Order o = order();

        // 0 行：并发冲突，不登记事件
        whenCancelReturnsRows(0);
        org.junit.jupiter.api.Assertions.assertEquals(0,
                persister.cancel(o, 1, null, OrderCancelledEvent.builder().orderNo("O1").build()));
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());

        // 关单 1 行：登记 ORDER_CANCELLED
        whenCancelReturnsRows(1);
        org.junit.jupiter.api.Assertions.assertEquals(1,
                persister.cancel(o, 1, null, OrderCancelledEvent.builder().orderNo("O1").build()));
        verify(outboxPublisher, times(1)).publish(any(), any(), any(), eq("O1"));

        // 发货 1 行：事件 + 10 天延时
        when(orderMapper.markShipped(any(), any(), any(), any(), any())).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertEquals(1, persister.ship("O1", "SF1", "",
                null, null, OrderShippedEvent.builder().orderNo("O1").build(), new OrderDelayMessage()));
        verify(outboxPublisher, times(1)).publishDelay(any(), any(), any(), eq("O1"),
                eq(com.shop.common.constant.MqTopics.DELAY_10_DAY_SECONDS));

        // 确认 1 行：事件 + 15 天延时
        when(orderMapper.markConfirmed(any(), any(), any())).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertEquals(1, persister.confirm(o, null, null,
                OrderConfirmedEvent.builder().orderNo("O1").build(), new OrderDelayMessage()));
        verify(outboxPublisher, times(1)).publishDelay(any(), any(), any(), eq("O1"),
                eq(com.shop.common.constant.MqTopics.DELAY_15_DAY_SECONDS));

        // 关单（售后期满）1 行：ORDER_COMPLETED
        when(orderMapper.markClosed(any(), any())).thenReturn(1);
        org.junit.jupiter.api.Assertions.assertTrue(persister.close("O1", null,
                OrderCompletedEvent.builder().orderNo("O1").build()));
        // cancel 1 + ship 1 + confirm 1 + close 1 = 4 次普通事件
        verify(outboxPublisher, times(4)).publish(any(), any(), any(), eq("O1"));
    }

    private void whenCancelReturnsRows(int rows) {
        org.mockito.Mockito.reset(orderMapper);
        when(orderMapper.markCancelled(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(rows);
    }
}
