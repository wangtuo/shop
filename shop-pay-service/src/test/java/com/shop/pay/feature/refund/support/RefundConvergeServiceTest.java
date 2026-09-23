package com.shop.pay.feature.refund.support;

import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.enums.RefundStatuses;
import com.shop.api.pay.enums.RefundTypes;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.refund.entity.RefundOrder;
import com.shop.pay.feature.refund.entity.RefundSplit;
import com.shop.pay.feature.refund.mapper.RefundMapper;
import com.shop.pay.feature.refund.mapper.RefundSplitMapper;
import com.shop.pay.feature.refund.statemachine.RefundStateMachine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B8/P2-5 唯一收敛漏斗测试：三路（SYNC/NOTIFY/QUERY）同构，CAS 获胜方写终态+发事件一次。
 */
class RefundConvergeServiceTest {

    private RefundMapper refundMapper;
    private RefundSplitMapper refundSplitMapper;
    private PaymentMapper paymentMapper;
    private ChannelFlowMapper channelFlowMapper;
    private OutboxPublisher outboxPublisher;
    private SimpleMeterRegistry registry;
    private RefundConvergeService converge;

    @BeforeEach
    void setUp() {
        refundMapper = mock(RefundMapper.class);
        refundSplitMapper = mock(RefundSplitMapper.class);
        paymentMapper = mock(PaymentMapper.class);
        channelFlowMapper = mock(ChannelFlowMapper.class);
        outboxPublisher = mock(OutboxPublisher.class);
        registry = new SimpleMeterRegistry();
        converge = new RefundConvergeService(refundMapper, refundSplitMapper, paymentMapper,
                channelFlowMapper, new RefundStateMachine(), outboxPublisher, registry);
    }

    private RefundOrder order(long id, int status) {
        RefundOrder r = new RefundOrder();
        r.setId(id);
        r.setRefundNo("R1");
        r.setPayNo("P1");
        r.setOrderNo("O1");
        r.setUserId(7L);
        r.setAmountFen(1000L);
        r.setPayMethod(PayMethods.ALIPAY.getCode());
        r.setRefundType(RefundTypes.FULL.getCode());
        r.setStatus(status);
        return r;
    }

    private RefundSplit split(long id, String code, int status, long amount) {
        RefundSplit s = new RefundSplit();
        s.setId(id);
        s.setRefundNo("R1");
        s.setPayNo("P1");
        s.setChannelCode(code);
        s.setAmountFen(amount);
        s.setStatus(status);
        return s;
    }

    private ChannelFlow flow(long id, String code, long amount, long paid) {
        ChannelFlow f = new ChannelFlow();
        f.setId(id);
        f.setChannelCode(code);
        f.setAmountFen(amount);
        f.setPaidFen(paid);
        return f;
    }

    private void stubSuccessWritable() {
        when(refundMapper.markSuccess(eq("R1"), any())).thenReturn(1);
        Payment payment = new Payment();
        payment.setPayNo("P1");
        payment.setAmountFen(1000L);
        payment.setRefundedFen(0L);
        payment.setStatus(PayStatuses.SUCCESS.getCode());
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.addRefundedFen(eq("P1"), anyLong())).thenReturn(1);
        when(channelFlowMapper.addPaidFen(anyLong(), anyLong())).thenReturn(1);
        when(paymentMapper.markRefunded("P1")).thenReturn(1);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(
                List.of(flow(100L, "MOCK_ALIPAY", 1000, 0)));
    }

    @Test
    void 全部split成功_首次收敛_CAS获胜发一次outbox() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 20));
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 1000)));
        when(refundSplitMapper.markSuccess(anyLong(), any(), any())).thenReturn(1);
        stubSuccessWritable();

        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.SYNC,
                List.of(SplitOutcome.success(1L, "CHRF1")), null);

        assertEquals(RefundConvergeService.ConvergeResult.ADVANCED_SUCCESS, result);
        verify(refundSplitMapper).markSuccess(eq(1L), eq("CHRF1"), any());
        verify(paymentMapper).addRefundedFen("P1", 1000L);
        verify(channelFlowMapper).addPaidFen(100L, 1000L);
        verify(paymentMapper).markRefunded("P1");
        verify(outboxPublisher).publish(eq(MqTopics.REFUND_SUCCESS), eq("refund"), any(), eq("R1"));
    }

    @Test
    void 三路并发_仅markSuccess_CAS获胜方发事件_败者零副作用() {
        RefundOrder processing = order(11L, 20);
        RefundOrder success = order(11L, 30);
        when(refundMapper.selectById(11L)).thenReturn(processing, processing, success);
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 1000)));
        when(refundSplitMapper.markSuccess(anyLong(), any(), any())).thenReturn(1);
        stubSuccessWritable();
        // 第三次（败者）markSuccess CAS 0 行
        when(refundMapper.markSuccess(eq("R1"), any())).thenReturn(1, 0);

        RefundConvergeService.ConvergeResult first = converge.converge(11L,
                RefundConvergeService.Trigger.NOTIFY,
                List.of(SplitOutcome.success(1L, "CHRF1")), null);
        RefundConvergeService.ConvergeResult loser = converge.converge(11L,
                RefundConvergeService.Trigger.QUERY,
                List.of(SplitOutcome.success(1L, "CHRF1")), null);

        assertEquals(RefundConvergeService.ConvergeResult.ADVANCED_SUCCESS, first);
        assertEquals(RefundConvergeService.ConvergeResult.ALREADY_SUCCESS, loser);
        verify(outboxPublisher).publish(eq(MqTopics.REFUND_SUCCESS), eq("refund"), any(), eq("R1"));
        verify(paymentMapper).addRefundedFen(eq("P1"), anyLong());
    }

    @Test
    void 已30态重复收敛_零副作用() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 30));
        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.NOTIFY,
                List.of(SplitOutcome.success(1L, "CHRF1")), null);
        assertEquals(RefundConvergeService.ConvergeResult.ALREADY_SUCCESS, result);
        verifyNoInteractions(outboxPublisher);
        verify(refundMapper, never()).markSuccess(any(), any());
        verify(refundSplitMapper, never()).markSuccess(anyLong(), any(), any());
    }

    @Test
    void 失败split_整单20到40_retryCount递增_不发事件() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 20));
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 1000)));

        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.SYNC,
                List.of(SplitOutcome.fail(1L, "渠道退款失败: 余额不足")), "渠道退款失败: 余额不足");

        assertEquals(RefundConvergeService.ConvergeResult.MARKED_FAIL, result);
        verify(refundSplitMapper).markFail(1L);
        verify(refundMapper).markFail("R1", "渠道退款失败: 余额不足", 1);
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void 受理中split_整单保持20等补偿() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 20));
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 1000)));

        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.QUERY,
                List.of(SplitOutcome.pending(1L)), null);

        assertEquals(RefundConvergeService.ConvergeResult.STILL_PROCESSING, result);
        verify(refundMapper, never()).markSuccess(any(), any());
        verify(refundMapper, never()).markFail(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void 混合split一成一败_整单40且成功split保留30() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 20));
        // 收敛后重读：split1=30、split2=40
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 600), split(2L, "BALANCE", 20, 400)),
                List.of(split(1L, "MOCK_ALIPAY", 30, 600), split(2L, "BALANCE", 40, 400)));
        when(refundSplitMapper.markSuccess(anyLong(), any(), any())).thenReturn(1);

        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.SYNC,
                List.of(SplitOutcome.success(1L, "CHRF1"), SplitOutcome.fail(2L, "余额入账失败")),
                "余额入账失败");

        assertEquals(RefundConvergeService.ConvergeResult.MARKED_FAIL, result);
        verify(refundSplitMapper).markSuccess(eq(1L), eq("CHRF1"), any());
        verify(refundSplitMapper).markFail(2L);
        verify(refundMapper).markFail(eq("R1"), eq("余额入账失败"), org.mockito.ArgumentMatchers.anyInt());
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void 已冲正50_拒绝30迁移() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 50));
        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.NOTIFY,
                List.of(SplitOutcome.success(1L, "CHRF1")), null);
        assertEquals(RefundConvergeService.ConvergeResult.ILLEGAL_STATE, result);
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void 累计退款超实付_抛60004_不发事件() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 20));
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 1000)));
        when(refundSplitMapper.markSuccess(anyLong(), any(), any())).thenReturn(1);
        when(refundMapper.markSuccess(eq("R1"), any())).thenReturn(1);
        Payment payment = new Payment();
        payment.setPayNo("P1");
        payment.setAmountFen(1000L);
        payment.setRefundedFen(500L);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.addRefundedFen(eq("P1"), anyLong())).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> converge.converge(11L,
                RefundConvergeService.Trigger.SYNC,
                List.of(SplitOutcome.success(1L, "CHRF1")), null));
        assertEquals(60004, ex.getCode());
        verifyNoInteractions(outboxPublisher);
    }

    @Test
    void DB中已有失败split_无新结果_整单仍置40() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 20));
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 40, 1000)));
        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.SYNC, null, "渠道退款失败");
        assertEquals(RefundConvergeService.ConvergeResult.MARKED_FAIL, result);
        verify(refundMapper).markFail(eq("R1"), eq("渠道退款失败"), org.mockito.ArgumentMatchers.anyInt());
    }

    // ============================== O6 业务指标 ==============================

    private void assertNoHighCardinalityTagKeys() {
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            String k = tag.getKey();
            org.junit.jupiter.api.Assertions.assertFalse(
                    k.equals("userId") || k.equals("merchantId") || k.equals("orderNo")
                            || k.equals("payNo") || k.equals("refundNo"),
                    "高基数标签键泄露: " + k);
        }));
    }

    @Test
    void metrics_退款收敛成功_shop_refund_total按operator_type递增() {
        RefundOrder refund = order(11L, 20);
        refund.setOperatorType(0); // 0 普通用户发起（统一用户类型码）
        when(refundMapper.selectById(11L)).thenReturn(refund);
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 1000)));
        when(refundSplitMapper.markSuccess(anyLong(), any(), any())).thenReturn(1);
        stubSuccessWritable();

        converge.converge(11L, RefundConvergeService.Trigger.SYNC,
                List.of(SplitOutcome.success(1L, "CHRF1")), null);

        assertEquals(1.0, registry.counter("shop_refund_total",
                "result", "success", "operator_type", "0").count());
        assertNoHighCardinalityTagKeys();
    }

    @Test
    void metrics_退款收敛失败_未知operator_type记unknown() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 20));
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(
                List.of(split(1L, "MOCK_ALIPAY", 20, 1000)));

        converge.converge(11L, RefundConvergeService.Trigger.SYNC,
                List.of(SplitOutcome.fail(1L, "渠道退款失败: 余额不足")), "渠道退款失败: 余额不足");

        assertEquals(1.0, registry.counter("shop_refund_total",
                "result", "fail", "operator_type", "unknown").count());
        assertNoHighCardinalityTagKeys();
    }

    @Test
    void metrics_幂等收敛_已成功不重复计数() {
        when(refundMapper.selectById(11L)).thenReturn(order(11L, 30));

        RefundConvergeService.ConvergeResult result = converge.converge(11L,
                RefundConvergeService.Trigger.NOTIFY,
                List.of(SplitOutcome.success(1L, "CHRF1")), null);

        assertEquals(RefundConvergeService.ConvergeResult.ALREADY_SUCCESS, result);
        assertEquals(0.0, registry.find("shop_refund_total").counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count).sum());
    }
}
