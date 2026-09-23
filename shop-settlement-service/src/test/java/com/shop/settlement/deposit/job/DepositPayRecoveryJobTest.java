package com.shop.settlement.deposit.job;

import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.result.Result;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.deposit.service.DepositPaySettlementService;
import com.shop.settlement.enums.DepositLogTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4-24 对账兜底 Job 单测：只以支付域 status=30 且 orderNo/金额严格一致为补账事实；
 * 非成功/不一致/RPC 异常/补账异常各路径必须保守挂起或下轮重试，永不伪补账。
 * R4-24 加固另覆盖：陈旧 payNo（FAIL/CLOSED 墓碑单）必须按 orderNo 回查活跃支付单。
 */
@ExtendWith(MockitoExtension.class)
class DepositPayRecoveryJobTest {

    @Mock private DepositLogMapper depositLogMapper;
    @Mock private PayClient payClient;
    @Mock private DepositPaySettlementService depositPaySettlementService;

    private DepositPayRecoveryJob job;

    @BeforeEach
    void setUp() {
        job = new DepositPayRecoveryJob(depositLogMapper, payClient, depositPaySettlementService);
    }

    private SettDepositLog payLog(String logNo, String payNo, long amount) {
        SettDepositLog l = new SettDepositLog();
        l.setId(1L);
        l.setLogNo(logNo);
        l.setMerchantId(7L);
        l.setLogType(DepositLogTypes.PAY);
        l.setStatus(SettDepositLog.STATUS_PROCESSING);
        l.setPayNo(payNo);
        l.setAmountFen(amount);
        return l;
    }

    private PaymentDTO payment(String payNo, String orderNo, long amount, int status) {
        return PaymentDTO.builder()
                .payNo(payNo).orderNo(orderNo).userId(7L).payMethod(1)
                .amountFen(amount).status(status).channelTransactionNo("TXN1")
                .payTime(LocalDateTime.now()).build();
    }

    private void stubPending(SettDepositLog log) {
        when(depositLogMapper.selectPayPendingForRecovery(any(), any(), anyInt()))
                .thenReturn(List.of(log));
    }

    @Test
    @DisplayName("支付成功且orderNo金额一致_合成scene4事件驱动补账_返回1")
    void success_drivesBooking() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenReturn(Result.success(payment("P1", "DP1", 100000L, 30)));
        // CAS 真实获胜才计数
        when(depositPaySettlementService.onPaymentSucceeded(any())).thenReturn(true);

        int recovered = job.recoverOnce(LocalDateTime.now());
        assertEquals(1, recovered);

        ArgumentCaptor<PaymentSucceededEvent> captor = ArgumentCaptor.forClass(PaymentSucceededEvent.class);
        verify(depositPaySettlementService).onPaymentSucceeded(captor.capture());
        PaymentSucceededEvent event = captor.getValue();
        assertEquals(PayScenes.DEPOSIT, event.getPayScene());
        assertEquals("P1", event.getPayNo());
        assertEquals("DP1", event.getOrderNo());
        assertEquals(100000L, event.getAmountFen());
        // 确定性 eventId 使多轮/多节点补账自身幂等，且与原始 MQ 事件不冲突
        assertEquals("rec-P1", event.getEventId());
        verify(depositLogMapper, never()).touchPayRecoveryQuery(any());
    }

    @Test
    @DisplayName("CAS落败（他节点已补）_返回0不计数")
    void casLoses_notCounted() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenReturn(Result.success(payment("P1", "DP1", 100000L, 30)));
        when(depositPaySettlementService.onPaymentSucceeded(any())).thenReturn(false);

        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper, never()).touchPayRecoveryQuery(any());
    }

    @Test
    @DisplayName("支付未成功_touch降频_不补账")
    void nonSuccess_touchOnly() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenReturn(Result.success(
                payment("P1", "DP1", 100000L, PayStatuses.WAIT.getCode())));

        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper).touchPayRecoveryQuery("DP1");
        verify(depositPaySettlementService, never()).onPaymentSucceeded(any());
    }

    @Test
    @DisplayName("orderNo不一致_挂起人工核对_禁止补账")
    void orderNoMismatch_noBooking() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenReturn(Result.success(payment("P1", "DP-OTHER", 100000L, 30)));

        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper).touchPayRecoveryQuery("DP1");
        verify(depositPaySettlementService, never()).onPaymentSucceeded(any());
    }

    @Test
    @DisplayName("金额不一致_挂起人工核对_禁止补账")
    void amountMismatch_noBooking() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenReturn(Result.success(payment("P1", "DP1", 99999L, 30)));

        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper).touchPayRecoveryQuery("DP1");
        verify(depositPaySettlementService, never()).onPaymentSucceeded(any());
    }

    @Test
    @DisplayName("支付域RPC异常_不touch不补账_下轮快速重试")
    void rpcException_noTouchNoBooking() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenThrow(new RuntimeException("feign connect refused"));

        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper, never()).touchPayRecoveryQuery(any());
        verify(depositPaySettlementService, never()).onPaymentSucceeded(any());
    }

    @Test
    @DisplayName("补账事务抛错_不touch_下轮重试")
    void bookingThrows_noTouch() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenReturn(Result.success(payment("P1", "DP1", 100000L, 30)));
        org.mockito.Mockito.doThrow(new RuntimeException("db deadlock"))
                .when(depositPaySettlementService).onPaymentSucceeded(any());

        assertFalse(job.recoverOnce(LocalDateTime.now()) > 0);
        verify(depositLogMapper, never()).touchPayRecoveryQuery(eq("DP1"));
    }

    @Test
    @DisplayName("支付域返回空Result且无活跃单_不touch_下轮重试")
    void emptyResult_noTouchNoBooking() {
        SettDepositLog log = payLog("DP1", "P1", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1")).thenReturn(Result.success(null));
        // 活跃单回查同样无响应（mock 默认 null Result）→ 视为支付域故障
        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper, never()).touchPayRecoveryQuery(any());
        verify(depositPaySettlementService, never()).onPaymentSucceeded(any());
    }

    @Test
    @DisplayName("陈旧payNo已CLOSED_按orderNo回查活跃支付单成功_用新payNo补账")
    void stalePayNo_fallbackToActivePayment_booksWithNewPayNo() {
        SettDepositLog log = payLog("DP1", "P1-OLD", 100000L);
        stubPending(log);
        // 缴费单上的存档 payNo 是已关闭的首次尝试
        when(payClient.getByPayNo("P1-OLD"))
                .thenReturn(Result.success(payment("P1-OLD", "DP1", 100000L, PayStatuses.CLOSED.getCode())));
        // 同 orderNo 当前活跃单是重新支付且已成功的新单
        when(payClient.getActiveByOrderNo("DP1"))
                .thenReturn(Result.success(payment("P1-NEW", "DP1", 100000L, PayStatuses.SUCCESS.getCode())));
        when(depositPaySettlementService.onPaymentSucceeded(any())).thenReturn(true);

        assertEquals(1, job.recoverOnce(LocalDateTime.now()));

        ArgumentCaptor<PaymentSucceededEvent> captor = ArgumentCaptor.forClass(PaymentSucceededEvent.class);
        verify(depositPaySettlementService).onPaymentSucceeded(captor.capture());
        PaymentSucceededEvent event = captor.getValue();
        assertEquals("P1-NEW", event.getPayNo());
        assertEquals("DP1", event.getOrderNo());
        assertEquals(100000L, event.getAmountFen());
        assertEquals(PayScenes.DEPOSIT, event.getPayScene());
        assertEquals("rec-P1-NEW", event.getEventId());
        verify(depositLogMapper, never()).touchPayRecoveryQuery(any());
    }

    @Test
    @DisplayName("陈旧payNo已FAIL且无活跃支付单_touch降频_不补账")
    void stalePayNo_noActive_touchOnly() {
        SettDepositLog log = payLog("DP1", "P1-OLD", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1-OLD"))
                .thenReturn(Result.success(payment("P1-OLD", "DP1", 100000L, PayStatuses.FAIL.getCode())));
        // 支付域明确：无活跃支付单（用户未重新发起）
        when(payClient.getActiveByOrderNo("DP1")).thenReturn(Result.success(null));

        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper).touchPayRecoveryQuery("DP1");
        verify(depositPaySettlementService, never()).onPaymentSucceeded(any());
    }

    @Test
    @DisplayName("陈旧payNo回查活跃单时RPC异常_不touch_下轮重试")
    void stalePayNo_activeLookupRpcException_noTouch() {
        SettDepositLog log = payLog("DP1", "P1-OLD", 100000L);
        stubPending(log);
        when(payClient.getByPayNo("P1-OLD"))
                .thenReturn(Result.success(payment("P1-OLD", "DP1", 100000L, PayStatuses.FAIL.getCode())));
        when(payClient.getActiveByOrderNo("DP1")).thenThrow(new RuntimeException("connect refused"));

        assertEquals(0, job.recoverOnce(LocalDateTime.now()));
        verify(depositLogMapper, never()).touchPayRecoveryQuery(any());
        verify(depositPaySettlementService, never()).onPaymentSucceeded(any());
    }
}
