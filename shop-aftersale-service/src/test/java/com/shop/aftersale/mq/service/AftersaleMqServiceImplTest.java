package com.shop.aftersale.mq.service;

import com.shop.aftersale.aftersale.entity.AftersaleInsurance;
import com.shop.aftersale.aftersale.entity.AftersaleItem;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.entity.AftersaleRefund;
import com.shop.aftersale.aftersale.entity.AftersaleWindow;
import com.shop.aftersale.aftersale.mapper.AftersaleInsuranceMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleItemMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleRefundMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleWindowMapper;
import com.shop.aftersale.aftersale.mapper.OrderItemRefMapper;
import com.shop.aftersale.aftersale.mapper.PriceProtectRecordMapper;
import com.shop.aftersale.aftersale.mapper.StatusLogMapper;
import com.shop.aftersale.mq.mapper.MqConsumeLogMapper;
import com.shop.aftersale.mq.service.impl.AftersaleMqServiceImpl;
import com.shop.aftersale.support.AftersaleEventPublisher;
import com.shop.aftersale.support.AftersalePolicy;
import com.shop.api.aftersale.enums.AftersaleStatuses;
import com.shop.api.aftersale.enums.AftersaleTypes;
import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.api.user.client.UserClient;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.aftersale.support.FeignFailures;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.framework.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.annotations.Update;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REFUND_SUCCESS 消费：状态推进、积分退还、运费险每单一次、金额守恒、幂等。
 */
@ExtendWith(MockitoExtension.class)
class AftersaleMqServiceImplTest {

    @Mock private MqConsumeLogMapper mqConsumeLogMapper;
    @Mock private AftersaleWindowMapper windowMapper;
    @Mock private AftersaleOrderMapper orderMapper;
    @Mock private AftersaleItemMapper itemMapper;
    @Mock private OrderItemRefMapper itemRefMapper;
    @Mock private AftersaleRefundMapper refundMapper;
    @Mock private AftersaleInsuranceMapper insuranceMapper;
    @Mock private PriceProtectRecordMapper priceProtectMapper;
    @Mock private StatusLogMapper statusLogMapper;
    @Mock private UserClient userClient;
    @Mock private AftersaleEventPublisher eventPublisher;
    @Mock private OutboxPublisher outboxPublisher;

    private AftersaleMqServiceImpl mqService;

    @BeforeEach
    void setUp() {
        mqService = new AftersaleMqServiceImpl(mqConsumeLogMapper, windowMapper, orderMapper,
                itemMapper, itemRefMapper, refundMapper, insuranceMapper, priceProtectMapper,
                statusLogMapper, userClient, new AftersalePolicy(), eventPublisher, outboxPublisher);
        // 模拟 MyBatis-Plus 雪花 ID 回填：R4-25 后状态流转 outbox 键 aftersaleNo#t{statusLogId}
        statusLogIdSeq.set(9001L);
        org.mockito.Mockito.lenient().doAnswer(inv -> {
            com.shop.aftersale.aftersale.entity.StatusLog l = inv.getArgument(0);
            l.setId(statusLogIdSeq.getAndIncrement());
            return 1;
        }).when(statusLogMapper).insert(any());
    }

    /** R4-25：模拟 t_aftersale_status_log ID 回填（每次状态流转唯一）。 */
    private final java.util.concurrent.atomic.AtomicLong statusLogIdSeq =
            new java.util.concurrent.atomic.AtomicLong(9001L);

    @AfterEach
    void clearMqContext() {
        // R4-27：eventId 回退依赖 ThreadLocal，用例间必须清理，避免上下文泄漏造成假阳性
        MqConsumeContext.clear();
    }

    private RefundSucceededEvent event() {
        return RefundSucceededEvent.builder()
                .refundNo("R1").orderNo("O1").aftersaleNo("AS1").userId(1001L)
                .amountFen(10000L).payMethod(1).refundType(1)
                .refundTime(LocalDateTime.now()).build();
    }

    private AftersaleRefund refundRecord() {
        AftersaleRefund r = new AftersaleRefund();
        r.setRefundNo("R1");
        r.setAftersaleNo("AS1");
        r.setOrderNo("O1");
        r.setUserId(1001L);
        r.setAmountFen(10000L);
        r.setStatus(20);
        return r;
    }

    private AftersaleOrder order(int type, int points) {
        AftersaleOrder o = new AftersaleOrder();
        o.setAftersaleNo("AS1");
        o.setOrderNo("O1");
        o.setUserId(1001L);
        o.setType(type);
        o.setStatus(AftersaleStatuses.REFUNDING);
        o.setResponsibilitySide(2);
        o.setPointsRefund(points);
        o.setRefundFen(10000L);
        return o;
    }

    private AftersaleItem item(long fen) {
        AftersaleItem it = new AftersaleItem();
        it.setAftersaleNo("AS1");
        it.setOrderItemId(11L);
        it.setSkuId(101L);
        it.setQty(1);
        it.setRefundFen(fen);
        return it;
    }

    @Test
    void onRefundSuccess_退款成功_售后单40转50() {
        AftersaleOrder o = order(AftersaleTypes.REFUND_ONLY, 0);
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(o);
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(11L, 10000L, "AS1")).thenReturn(1);
        when(orderMapper.updateStatus("AS1", 40, 50)).thenReturn(1);

        mqService.onRefundSuccess(event());

        assertEquals(AftersaleStatuses.FINISHED, o.getStatus());
        verify(orderMapper).updateStatus("AS1", AftersaleStatuses.REFUNDING, AftersaleStatuses.FINISHED);
        // R4-25：publish 增加每次流转唯一的状态流水 id（outbox 键 aftersaleNo#t{logId}）
        verify(eventPublisher).publish(any(), any(), any(), any(), any(), anyLong());
        verify(insuranceMapper, never()).insert(any());
    }

    @Test
    void onRefundSuccess_重复eventId_直接ACK() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(0);
        mqService.onRefundSuccess(event());
        verify(refundMapper, never()).selectByRefundNo(any());
        verify(orderMapper, never()).updateStatus(any(), anyInt(), anyInt());
    }

    @Test
    void onRefundSuccess_积分按比例退还() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(order(AftersaleTypes.REFUND_ONLY, 500));
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(any(), anyLong(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(userClient.refundPoints(any())).thenReturn(Result.success());

        mqService.onRefundSuccess(event());

        verify(userClient).refundPoints(any());
    }

    @Test
    void onRefundSuccess_退货退款_登记运费险且72小时理赔() {
        AftersaleWindow w = new AftersaleWindow();
        w.setHasFreightInsurance(1);
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(order(AftersaleTypes.RETURN_REFUND, 0));
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(any(), anyLong(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(w);
        when(insuranceMapper.selectByOrderNo("O1")).thenReturn(null);

        mqService.onRefundSuccess(event());

        ArgumentCaptor<AftersaleInsurance> cap = ArgumentCaptor.forClass(AftersaleInsurance.class);
        verify(insuranceMapper).insert(cap.capture());
        assertEquals(2500L, cap.getValue().getClaimFen());
        assertEquals(10, cap.getValue().getStatus());
        verify(outboxPublisher).publishDelay(any(), any(), any(), any(), anyLong());
    }

    @Test
    void onRefundSuccess_运费险每单仅一次_不再登记() {
        AftersaleWindow w = new AftersaleWindow();
        w.setHasFreightInsurance(1);
        AftersaleInsurance existing = new AftersaleInsurance();
        existing.setId(9L);
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(order(AftersaleTypes.RETURN_REFUND, 0));
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(any(), anyLong(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(w);
        when(insuranceMapper.selectByOrderNo("O1")).thenReturn(existing);

        mqService.onRefundSuccess(event());

        verify(insuranceMapper, never()).insert(any());
        verify(outboxPublisher, never()).publishDelay(any(), any(), any(), any(), anyLong());
    }

    // ======================== R-B6：refundPoints 三类失败形态（9 用例之 3） ========================

    private void stubPointsRefundPath() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(order(AftersaleTypes.REFUND_ONLY, 500));
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(any(), anyLong(), any())).thenReturn(1);
    }

    @Test
    void onRefundSuccess_积分退还返回null_抛可恢复依赖失败且售后单不完成() {
        stubPointsRefundPath();
        when(userClient.refundPoints(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> mqService.onRefundSuccess(event()));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        // 售后单 40→50 未发生（@Transactional 下同异常回滚消费流水，broker 重投，bizNo 幂等）
        verify(orderMapper, never()).updateStatus(any(), anyInt(), anyInt());
    }

    @Test
    void onRefundSuccess_积分退还非success_抛可恢复依赖失败() {
        stubPointsRefundPath();
        when(userClient.refundPoints(any())).thenReturn(Result.fail(50000, "积分服务内部错误"));

        BizException ex = assertThrows(BizException.class, () -> mqService.onRefundSuccess(event()));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        verify(orderMapper, never()).updateStatus(any(), anyInt(), anyInt());
    }

    @Test
    void onRefundSuccess_积分服务HTTP500_异常外抛触发重投() {
        stubPointsRefundPath();
        when(userClient.refundPoints(any()))
                .thenThrow(FeignFailures.serverError500("UserClient#refundPoints"));

        BizException ex = assertThrows(BizException.class, () -> mqService.onRefundSuccess(event()));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        verify(userClient).refundPoints(any());
        verify(orderMapper, never()).updateStatus(any(), anyInt(), anyInt());
    }

    @Test
    void onRefundSuccess_累计退款超实付_抛异常触发重试() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(order(AftersaleTypes.REFUND_ONLY, 0));
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(any(), anyLong(), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> mqService.onRefundSuccess(event()));
        assertEquals(80003, ex.getCode());
    }

    // ======================== B11：运费险购买侧透传（窗口投影 + 防乱序覆盖） ========================

    private OrderShippedEvent shippedEvent(Integer has, Long premiumFen) {
        return OrderShippedEvent.builder()
                .orderNo("O1").userId(1001L).hasFreightInsurance(has)
                .insurancePremiumFen(premiumFen).build();
    }

    private OrderConfirmedEvent confirmedEvent(Integer has, Long premiumFen) {
        return OrderConfirmedEvent.builder()
                .orderNo("O1").userId(1001L).merchantId(2002L)
                .productPayFen(10000L).freightFen(0L).pointsDeductFen(0L)
                .hasFreightInsurance(has).insurancePremiumFen(premiumFen).build();
    }

    @Test
    void onOrderShipped_新建窗口_购险标记与保费来自事件() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(null);

        mqService.onOrderShipped(shippedEvent(1, 300L));

        ArgumentCaptor<AftersaleWindow> cap = ArgumentCaptor.forClass(AftersaleWindow.class);
        verify(windowMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getHasFreightInsurance());
        assertEquals(300L, cap.getValue().getPremiumFen());
    }

    @Test
    void onOrderShipped_事件无保费字段_置0不恒写购险() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(null);

        mqService.onOrderShipped(shippedEvent(null, null));

        ArgumentCaptor<AftersaleWindow> cap = ArgumentCaptor.forClass(AftersaleWindow.class);
        verify(windowMapper).insert(cap.capture());
        assertEquals(0, cap.getValue().getHasFreightInsurance());
        assertEquals(0L, cap.getValue().getPremiumFen());
    }

    @Test
    void onOrderConfirmed_新建窗口_落premium_fen快照() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(null);

        mqService.onOrderConfirmed(confirmedEvent(1, 300L));

        ArgumentCaptor<AftersaleWindow> cap = ArgumentCaptor.forClass(AftersaleWindow.class);
        verify(windowMapper).insert(cap.capture());
        assertEquals(1, cap.getValue().getHasFreightInsurance());
        assertEquals(300L, cap.getValue().getPremiumFen());
    }

    @Test
    void onOrderShipped_正序先发货后确认_两事件均透传购险标记() {
        // shipped 先到：新建窗口 has=1
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        AftersaleWindow existing = new AftersaleWindow();
        existing.setOrderNo("O1");
        existing.setOrderType(1);
        existing.setHasFreightInsurance(1);
        existing.setPremiumFen(300L);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(null, existing);

        mqService.onOrderShipped(shippedEvent(1, 300L));
        // confirmed 后到：走 updateConfirmed，has=1/premium=300 原样透传
        mqService.onOrderConfirmed(confirmedEvent(1, 300L));

        verify(windowMapper).updateConfirmed(eq("O1"), eq(40), eq(2002L), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), eq(10000L), eq(0L), eq(0L), eq(1), eq(300L));
    }

    @Test
    void onOrderShipped_乱序先确认后发货_后到shipped传0也不覆盖购险标记() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        // confirmed 先到已建窗口 has=1 premium=300
        AftersaleWindow existing = new AftersaleWindow();
        existing.setOrderNo("O1");
        existing.setOrderType(1);
        existing.setHasFreightInsurance(1);
        existing.setPremiumFen(300L);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(existing);

        // shipped 后到且事件未携带购险（旧生产者/默认 0）
        mqService.onOrderShipped(shippedEvent(0, 0L));

        // SQL 内 GREATEST 保证库里仍为 1（参数本身为 0，防覆盖由数据库单调并集完成）
        verify(windowMapper).updateShipped(eq("O1"), eq(30), eq(1), any(), any(), eq(0), eq(0L));
    }

    @Test
    void window更新SQL_购险与保费列使用GREATEST单调并集_钉死防乱序覆盖() throws Exception {
        assertUpdateSqlUsesGreatest("updateShipped");
        assertUpdateSqlUsesGreatest("updateConfirmed");
    }

    private void assertUpdateSqlUsesGreatest(String method) throws Exception {
        for (Method m : AftersaleWindowMapper.class.getMethods()) {
            if (!m.getName().equals(method)) {
                continue;
            }
            String sql = String.join(" ", m.getAnnotation(Update.class).value());
            org.junit.jupiter.api.Assertions.assertTrue(
                    sql.contains("GREATEST(has_freight_insurance, #{hasFreightInsurance})"),
                    method + " 必须用 GREATEST 防乱序覆盖: " + sql);
            org.junit.jupiter.api.Assertions.assertTrue(
                    sql.contains("GREATEST(premium_fen, #{premiumFen})"),
                    method + " 保费列必须单调并集: " + sql);
            return;
        }
        throw new AssertionError("未找到 mapper 方法 " + method);
    }

    @Test
    void onOrderShipped_重复eventId_幂等不触碰窗口() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(0);

        mqService.onOrderShipped(shippedEvent(1, 300L));
        mqService.onOrderConfirmed(confirmedEvent(1, 300L));

        verify(windowMapper, never()).selectByOrderNo(any());
        verify(windowMapper, never()).insert(any());
    }

    @Test
    void onRefundSuccess_理赔单保费快照取自窗口() {
        AftersaleWindow w = new AftersaleWindow();
        w.setHasFreightInsurance(1);
        w.setPremiumFen(300L);
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(order(AftersaleTypes.RETURN_REFUND, 0));
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(any(), anyLong(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(w);
        when(insuranceMapper.selectByOrderNo("O1")).thenReturn(null);

        mqService.onRefundSuccess(event());

        ArgumentCaptor<AftersaleInsurance> cap = ArgumentCaptor.forClass(AftersaleInsurance.class);
        verify(insuranceMapper).insert(cap.capture());
        // premiumFen=窗口值；claimFen 仍按封顶 2500 不变
        assertEquals(300L, cap.getValue().getPremiumFen());
        assertEquals(2500L, cap.getValue().getClaimFen());
    }

    // ======================== R4-27：eventId 归一化（body→框架上下文→fail-fast） ========================

    @Test
    void r427_onOrderShipped_bodyEventId空白_回退上下文eventId落库() {
        MqConsumeContext.bind(new MqConsumeContext(
                "noid:shop_order_shipped:O1", MqTopics.ORDER_SHIPPED, "O1", "rmq-1", true));
        OrderShippedEvent e = shippedEvent(0, 0L);
        e.setEventId(" ");
        when(mqConsumeLogMapper.insertIgnore(
                eq("noid:shop_order_shipped:O1"), eq(MqTopics.ORDER_SHIPPED), eq("O1")))
                .thenReturn(1);
        when(windowMapper.selectByOrderNo("O1")).thenReturn(null);

        mqService.onOrderShipped(e);

        verify(windowMapper).insert(any());
    }

    @Test
    void r427_onRefundSuccess_bodyEventId空白_回退上下文eventId落库() {
        MqConsumeContext.bind(new MqConsumeContext(
                "noid:shop_refund_success:R1", MqTopics.REFUND_SUCCESS, "R1", "rmq-1", true));
        RefundSucceededEvent e = event();
        e.setEventId(null);
        when(mqConsumeLogMapper.insertIgnore(
                eq("noid:shop_refund_success:R1"), eq(MqTopics.REFUND_SUCCESS), eq("R1")))
                .thenReturn(1);
        when(refundMapper.selectByRefundNo("R1")).thenReturn(refundRecord());
        when(orderMapper.selectByNo("AS1")).thenReturn(order(AftersaleTypes.REFUND_ONLY, 0));
        when(itemMapper.selectByAftersaleNo("AS1")).thenReturn(List.of(item(10000L)));
        when(itemRefMapper.addRefunded(any(), anyLong(), any())).thenReturn(1);
        when(orderMapper.updateStatus(any(), anyInt(), anyInt())).thenReturn(1);

        mqService.onRefundSuccess(e);

        verify(orderMapper).updateStatus("AS1", AftersaleStatuses.REFUNDING, AftersaleStatuses.FINISHED);
    }

    @Test
    void r427_body与上下文双空_failFast且不写消费流水() {
        OrderConfirmedEvent e = confirmedEvent(0, 0L);
        e.setEventId(null);

        assertThrows(IllegalStateException.class, () -> mqService.onOrderConfirmed(e));
        verify(mqConsumeLogMapper, never()).insertIgnore(any(), any(), any());
        verify(windowMapper, never()).selectByOrderNo(any());
    }
}
