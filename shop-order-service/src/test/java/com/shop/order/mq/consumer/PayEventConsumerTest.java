package com.shop.order.mq.consumer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.order.invoice.service.InvoiceService;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORDER_PAID / REFUND_SUCCESS 消费单测：流水幂等、10→20 条件更新、
 * 退款按实付占比分摊、全额关单 + 发票红冲、部分退款保持。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayEventConsumerTest {

    @Mock
    private MqConsumeService consumeService;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private InvoiceService invoiceService;

    private PayEventConsumer consumer;

    private static final String NO = "260315011001000001";

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Order.class);
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        consumer = new PayEventConsumer(consumeService, orderMapper, orderItemMapper, invoiceService);
        when(consumeService.firstTime(any(), any(), any())).thenReturn(true);
    }

    private Order order(int status) {
        Order o = new Order();
        o.setOrderNo(NO);
        o.setStatus(status);
        return o;
    }

    @Test
    void onPaid_duplicateEvent_skipped() {
        when(consumeService.firstTime(any(), any(), any())).thenReturn(false);
        consumer.onPaid(new PaymentSucceededEvent());
        verify(orderMapper, never()).selectOne(any());
        verify(orderMapper, never()).markPaid(any(), anyInt(), any(), any(), any());
    }

    @Test
    void onPaid_firstTime_marksPaidConditionally() {
        PaymentSucceededEvent e = PaymentSucceededEvent.builder()
                .payNo("PAY1").orderNo(NO).userId(1001L)
                .payMethod(PayMethods.ALIPAY.getCode())
                .channelTransactionNo("TXN-1")
                .paidTime(LocalDateTime.of(2026, 3, 15, 10, 0))
                .build();
        e.setEventId("evt-paid-1");
        when(orderMapper.selectOne(any())).thenReturn(order(OrderStatuses.WAIT_PAY));
        when(orderMapper.markPaid(eq(NO), eq(PayMethods.ALIPAY.getCode()), eq("PAY1"),
                eq("TXN-1"), any())).thenReturn(1);

        consumer.onPaid(e);

        verify(orderMapper).markPaid(eq(NO), eq(PayMethods.ALIPAY.getCode()), eq("PAY1"),
                eq("TXN-1"), eq(LocalDateTime.of(2026, 3, 15, 10, 0)));
    }

    @Test
    void onPaid_orderMissing_throwsForRetry() {
        when(orderMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> consumer.onPaid(PaymentSucceededEvent.builder().orderNo(NO).build()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND.getCode());
    }

    @Test
    void onPaid_depositSceneWithoutOrder_acksNoop_noPoison() {
        // FUNDS B10：保证金缴费（payScene=4）在本域无业务订单，消费记录已落，必须 ACK no-op
        when(orderMapper.selectOne(any())).thenReturn(null);
        PaymentSucceededEvent e = PaymentSucceededEvent.builder()
                .orderNo("DEP202603150001").payNo("PAY-DEP-1")
                .payScene(com.shop.api.pay.enums.PayScenes.DEPOSIT).build();

        consumer.onPaid(e);

        verify(orderMapper, never()).markPaid(any(), anyInt(), any(), any(), any());
    }

    @Test
    void onRefunded_partial_allocatesByPaidWeight_noCloseNoRedFlush() {
        RefundSucceededEvent e = RefundSucceededEvent.builder()
                .refundNo("R1").orderNo(NO).aftersaleNo("AS1")
                .refundType(2).amountFen(5000L).build();
        when(orderMapper.selectOne(any())).thenReturn(order(OrderStatuses.COMPLETED));
        OrderItem i1 = new OrderItem();
        i1.setId(1L);
        i1.setPaidFen(3000L);
        OrderItem i2 = new OrderItem();
        i2.setId(2L);
        i2.setPaidFen(7000L);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(i1, i2));

        consumer.onRefunded(e);

        // 最大余数法按 3:7 分摊 5000 → 1500 / 3500
        verify(orderItemMapper).markRefunded(1L, 1500L);
        verify(orderItemMapper).markRefunded(2L, 3500L);
        verify(orderMapper, never()).closeAfterAftersale(any(), any());
        verify(invoiceService, never()).redFlush(any());
    }

    @Test
    void onRefunded_full_closesOrderAndRedFlushesInvoice() {
        RefundSucceededEvent e = RefundSucceededEvent.builder()
                .refundNo("R2").orderNo(NO).aftersaleNo("AS2")
                .refundType(1).amountFen(20000L).build();
        when(orderMapper.selectOne(any())).thenReturn(order(60));
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
        when(orderMapper.closeAfterAftersale(eq(NO), any())).thenReturn(1);

        consumer.onRefunded(e);

        verify(orderMapper).closeAfterAftersale(eq(NO), any());
        verify(invoiceService).redFlush(NO);
    }

    @Test
    void onRefunded_groupFailFullRefundAtWaitShip_closesWithoutAftersaleNo() {
        // B1 拼团失败：已付待发货单由 GroupbuyOrderFlowService 直接发起 FULL 自动退款
        // （refundNo=GB:{orderNo}，无售后单），REFUND_SUCCESS 到达时订单仍在 20，必须关单+红冲；
        // SQL 谓词侧由 OrderMapper 注释钉住 20/60/61/62 四态（30/40 刻意不纳入）。
        RefundSucceededEvent e = RefundSucceededEvent.builder()
                .refundNo("GB:" + NO).orderNo(NO)
                .refundType(1).amountFen(20000L).build();
        when(orderMapper.selectOne(any())).thenReturn(order(OrderStatuses.WAIT_SHIP));
        when(orderMapper.closeAfterAftersale(eq(NO), any())).thenReturn(1);

        consumer.onRefunded(e);

        // 无 aftersaleNo：不查明细、不做分摊
        verify(orderItemMapper, never()).selectList(any());
        verify(orderMapper).closeAfterAftersale(eq(NO), any());
        verify(invoiceService).redFlush(NO);
    }

    @Test
    void onRefunded_duplicateEvent_skipped() {
        when(consumeService.firstTime(any(), any(), any())).thenReturn(false);
        consumer.onRefunded(RefundSucceededEvent.builder().orderNo(NO).build());
        verify(orderMapper, never()).selectOne(any());
        verify(orderMapper, never()).closeAfterAftersale(any(), any());
    }
}
