package com.shop.pay.feature.recon.service;

import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.enums.ReconcileDiffTypes;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.pay.channel.ChannelQueryResult;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.recon.entity.ReconDiff;
import com.shop.pay.feature.recon.mapper.ReconDiffMapper;
import com.shop.pay.feature.recon.service.impl.ReconDiffHandlerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-4：逐差异处置器单测——终态落库/并发 CAS 落败跳过/短款重试与转人工/失败留痕/REQUIRES_NEW 注解断言。
 */
class ReconDiffHandlerTest {

    private PaymentMapper paymentMapper;
    private ChannelFlowMapper channelFlowMapper;
    private ReconDiffMapper diffMapper;
    private OutboxPublisher outboxPublisher;
    private ReconDiffHandlerImpl handler;

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        channelFlowMapper = mock(ChannelFlowMapper.class);
        diffMapper = mock(ReconDiffMapper.class);
        outboxPublisher = mock(OutboxPublisher.class);
        handler = new ReconDiffHandlerImpl(paymentMapper, channelFlowMapper, diffMapper, outboxPublisher);
    }

    private ReconDiff diff(int type, int status) {
        ReconDiff d = new ReconDiff();
        d.setId(77L);
        d.setBatchNo("B1");
        d.setReconDate(LocalDate.of(2026, 9, 16));
        d.setChannelCode("MOCK_ALIPAY");
        d.setDiffType(type);
        d.setPayNo("P1");
        d.setOrderNo("O1");
        d.setChannelTxnNo("TXN1");
        d.setLocalAmountFen(5000L);
        d.setChannelAmountFen(5000L);
        d.setStatus(status);
        d.setRetryCount(0);
        d.setMaxRetry(5);
        return d;
    }

    private Payment waitingPayment() {
        Payment p = new Payment();
        p.setId(100L);
        p.setPayNo("P1");
        p.setOrderNo("O1");
        p.setUserId(7L);
        p.setPayMethod(2);
        p.setAmountFen(5000L);
        p.setStatus(PayStatuses.WAIT.getCode());
        return p;
    }

    @Test
    void handleLong_补单CAS获胜_落30并登记ORDER_PAID() {
        ReconDiff d = diff(ReconcileDiffTypes.LONG.getCode(), 10);
        Payment latest = waitingPayment();
        latest.setStatus(PayStatuses.SUCCESS.getCode());
        ChannelFlow flow = new ChannelFlow();
        flow.setId(9L);
        flow.setPayNo("P1");
        flow.setChannelCode("MOCK_ALIPAY");
        flow.setFlowStatus(PayStatuses.WAIT.getCode());
        when(paymentMapper.selectOne(any())).thenReturn(waitingPayment());
        when(paymentMapper.markSuccess(eq("P1"), eq("TXN1"), anyString(), any())).thenReturn(1);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(flow));
        when(paymentMapper.selectById(100L)).thenReturn(latest);

        ReconDiffHandler.DiffHandleResult result = handler.handleOne(d);

        assertEquals(ReconDiffHandler.DiffHandleResult.Outcome.HANDLED, result.outcome());
        ArgumentCaptor<PaymentSucceededEvent> cap = ArgumentCaptor.forClass(PaymentSucceededEvent.class);
        verify(outboxPublisher).publish(eq(MqTopics.ORDER_PAID), eq("recon"), cap.capture(), eq("P1"));
        assertEquals("TXN1", cap.getValue().getChannelTransactionNo());
        verify(diffMapper).updateStatus(eq(77L), eq(10), eq(30), eq("SUPPLEMENT_ORDER"), anyString(), any());
    }

    @Test
    void handleLong_补单CAS落败_跳过不发事件不写终态() {
        ReconDiff d = diff(ReconcileDiffTypes.LONG.getCode(), 10);
        when(paymentMapper.selectOne(any())).thenReturn(waitingPayment());
        // 并发赢家已抢先补单
        when(paymentMapper.markSuccess(anyString(), anyString(), anyString(), any())).thenReturn(0);

        ReconDiffHandler.DiffHandleResult result = handler.handleOne(d);

        assertEquals(ReconDiffHandler.DiffHandleResult.Outcome.SKIPPED, result.outcome());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
        verify(diffMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString(), any());
    }

    @Test
    void handleLong_唯一键冲突_视为并发处理跳过无异常() {
        ReconDiff d = diff(ReconcileDiffTypes.LONG.getCode(), 10);
        when(paymentMapper.selectOne(any())).thenReturn(waitingPayment());
        when(paymentMapper.markSuccess(anyString(), anyString(), anyString(), any()))
                .thenThrow(new DuplicateKeyException("uk test"));

        ReconDiffHandler.DiffHandleResult result = handler.handleOne(d);

        assertEquals(ReconDiffHandler.DiffHandleResult.Outcome.SKIPPED, result.outcome());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void handleLong_无待支付单_转40人工挂账() {
        ReconDiff d = diff(ReconcileDiffTypes.LONG.getCode(), 10);
        when(paymentMapper.selectOne(any())).thenReturn(null);

        ReconDiffHandler.DiffHandleResult result = handler.handleOne(d);

        assertEquals(ReconDiffHandler.DiffHandleResult.Outcome.HANDLED, result.outcome());
        verify(diffMapper).updateStatus(eq(77L), eq(10), eq(40), eq("MANUAL"), anyString(), any());
    }

    @Test
    void handleMismatch_调账成功_落30() {
        ReconDiff d = diff(ReconcileDiffTypes.AMOUNT_MISMATCH.getCode(), 10);
        d.setLocalAmountFen(5000L);
        d.setChannelAmountFen(4900L);
        when(paymentMapper.adjustAmount("P1", 4900L)).thenReturn(1);

        ReconDiffHandler.DiffHandleResult result = handler.handleOne(d);

        assertEquals(ReconDiffHandler.DiffHandleResult.Outcome.HANDLED, result.outcome());
        verify(diffMapper).updateStatus(eq(77L), eq(10), eq(30), eq("ADJUST_AMOUNT"), anyString(), any());
    }

    @Test
    void handleMismatch_调账CAS0_转40不伪平账() {
        ReconDiff d = diff(ReconcileDiffTypes.AMOUNT_MISMATCH.getCode(), 10);
        when(paymentMapper.adjustAmount(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);

        handler.handleOne(d);

        verify(diffMapper).updateStatus(eq(77L), eq(10), eq(40), eq("MANUAL"), anyString(), any());
    }

    @Test
    void handleShort_未超次数_retry加1且保持待处理_备注带渠道查询结果() {
        ReconDiff d = diff(ReconcileDiffTypes.SHORT.getCode(), 10);
        d.setRetryCount(2);
        ChannelQueryResult qr = ChannelQueryResult.builder()
                .state(ChannelQueryResult.State.PAYING).channelTxnNo("TXN1").build();

        ReconDiffHandler.DiffHandleResult result = handler.handleOne(d, qr);

        assertEquals(ReconDiffHandler.DiffHandleResult.Outcome.HANDLED, result.outcome());
        ArgumentCaptor<String> remark = ArgumentCaptor.forClass(String.class);
        verify(diffMapper).incrRetry(eq(77L), eq(10), remark.capture());
        org.assertj.core.api.Assertions.assertThat(remark.getValue()).contains("第3次", "PAYING", "TXN1");
        // 渠道返回非成功绝不自动平账
        verify(diffMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                eq(30), anyString(), anyString(), any());
    }

    @Test
    void handleShort_渠道返回SUCCESS也不自动平账_保持重试() {
        ReconDiff d = diff(ReconcileDiffTypes.SHORT.getCode(), 10);
        ChannelQueryResult qr = ChannelQueryResult.of(ChannelQueryResult.State.SUCCESS);

        handler.handleOne(d, qr);

        verify(diffMapper).incrRetry(eq(77L), eq(10), org.mockito.ArgumentMatchers.contains("SUCCESS"));
        verify(diffMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString(), any());
    }

    @Test
    void handleShort_连续补偿达上限5_转40人工且不自动关单() {
        ReconDiff d = diff(ReconcileDiffTypes.SHORT.getCode(), 10);
        // 前 5 轮补偿未收敛：retry 0→5，差异保持 10
        for (int i = 0; i < 5; i++) {
            d.setRetryCount(i);
            handler.handleOne(d, ChannelQueryResult.of(ChannelQueryResult.State.PAYING));
        }
        verify(diffMapper, org.mockito.Mockito.times(5)).incrRetry(eq(77L), eq(10), anyString());
        verify(diffMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString(), anyString(), any());

        // 第 6 轮（retry=max=5）转 MANUAL(40)
        d.setRetryCount(5);
        ReconDiffHandler.DiffHandleResult result =
                handler.handleOne(d, ChannelQueryResult.of(ChannelQueryResult.State.PAYING));
        assertEquals(ReconDiffHandler.DiffHandleResult.Outcome.HANDLED, result.outcome());
        verify(diffMapper).updateStatus(eq(77L), eq(10), eq(40), eq("MANUAL"), anyString(), any());
    }

    @Test
    void markFailure_独立新事务留痕_retry加1状态不变() {
        ReconDiff d = diff(ReconcileDiffTypes.LONG.getCode(), 10);
        handler.markFailure(d, new RuntimeException("mock: channel 500"));

        verify(diffMapper).incrRetry(eq(77L), eq(10), org.mockito.ArgumentMatchers.contains("mock: channel 500"));
    }

    @Test
    void 注解断言_handleOne与markFailure均为REQUIRES_NEW且独立Bean() throws Exception {
        // 独立 Bean：处置器与批服务不是同一个类（REQUIRES_NEW 经代理生效的前提，规避 this 自调用陷阱）
        assertNotNull(ReconDiffHandler.class);
        assertEquals(ReconDiffHandler.class, handler.getClass().getInterfaces()[0]);

        for (String method : new String[]{"handleOne"}) {
            Method m1 = ReconDiffHandlerImpl.class.getMethod(method, ReconDiff.class);
            Method m2 = ReconDiffHandlerImpl.class.getMethod(method, ReconDiff.class, ChannelQueryResult.class);
            assertRequiresNew(m1);
            assertRequiresNew(m2);
        }
        Method markFailure = ReconDiffHandlerImpl.class.getMethod("markFailure", ReconDiff.class, Exception.class);
        assertRequiresNew(markFailure);
        // 传播注解必须声明在接口/实现上且 rollbackFor=Exception
        Transactional tx = markFailure.getAnnotation(Transactional.class);
        assertSame(Exception.class, tx.rollbackFor()[0]);
    }

    private void assertRequiresNew(Method method) {
        Transactional tx = method.getAnnotation(Transactional.class);
        assertNotNull(tx, "缺失 @Transactional: " + method);
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
    }
}
