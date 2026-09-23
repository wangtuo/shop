package com.shop.settlement.deposit.service;

import com.shop.api.aftersale.client.AftersaleClient;
import com.shop.api.aftersale.dto.MerchantDisputeDTO;
import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.result.Result;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.deposit.dto.DepositPayRequest;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.deposit.vo.DepositPayVO;
import com.shop.settlement.enums.DepositLogTypes;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.enums.MerchantStatuses;
import com.shop.settlement.enums.WithdrawChannels;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.remit.RemitQueryResult;
import com.shop.settlement.remit.RemitResult;
import com.shop.settlement.remit.RemitRouter;
import com.shop.settlement.remit.RemitStatuses;
import com.shop.settlement.support.DataCipher;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.mapper.WithdrawMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保证金服务单测（B10 真实资金链路）：阈值/扣赔预警保留；缴费三段式；
 * 罚款行锁禁负 + 平台 43；清退 90 天 + aftersale 纠纷校验 + 代发；查询确认才置零/关单。
 */
@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock private MerchantMapper merchantMapper;
    @Mock private MerchantService merchantService;
    @Mock private DepositLogMapper depositLogMapper;
    @Mock private SettleNoGenerator noGenerator;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private PayClient payClient;
    @Mock private RemitRouter remitRouter;
    @Mock private AftersaleClient aftersaleClient;
    @Mock private WithdrawMapper withdrawMapper;
    @Mock private AccountService accountService;

    private DataCipher cipher;
    private SimpleMeterRegistry registry;
    private DepositService service;

    @BeforeEach
    void setUp() {
        cipher = DataCipher.forTest(DataCipher.DEV_DEFAULT_KEY, "");
        registry = new SimpleMeterRegistry();
        service = new DepositService(merchantMapper, merchantService, depositLogMapper,
                noGenerator, outboxPublisher, payClient, remitRouter, aftersaleClient,
                withdrawMapper, cipher, accountService, registry);
        // 编排方法经代理调短事务方法：单测内自引用指向自身
        ReflectionTestUtils.setField(service, "self", service);
    }

    private SettMerchant merchant(long id, long required, long balance, Integer alerted, int status) {
        SettMerchant m = new SettMerchant();
        m.setId(id);
        m.setDepositRequiredFen(required);
        m.setDepositBalanceFen(balance);
        m.setDepositAlerted(alerted);
        m.setStatus(status);
        return m;
    }

    private DepositPayRequest payReq(long amount, String token) {
        DepositPayRequest req = new DepositPayRequest();
        req.setAmountFen(amount);
        req.setPayMethod(1);
        req.setTerminal(1);
        req.setClientToken(token);
        return req;
    }

    private SettDepositLog refundLog(String logNo, long merchantId, long amount, int status, String remitNo) {
        SettDepositLog l = new SettDepositLog();
        l.setId(100L);
        l.setLogNo(logNo);
        l.setMerchantId(merchantId);
        l.setLogType(DepositLogTypes.RESIGN_REFUND);
        l.setStatus(status);
        l.setAmountFen(-amount);
        l.setChannelRemitNo(remitNo == null ? "" : remitNo);
        l.setRemark("清退退还");
        return l;
    }

    // ============================== 阈值（既有语义） ==============================

    @Test
    @DisplayName("threshold_余额低于应缴50%_限提判定边界正确")
    void belowAlertThreshold_boundary() {
        SettMerchant m = merchant(1L, 200_000L, 100_000L, 0, 1);
        assertFalse(service.belowAlertThreshold(m));
        m.setDepositBalanceFen(99_999L);
        assertTrue(service.belowAlertThreshold(m));
        assertEquals(100_000L, service.thresholdFen(merchant(1L, 200_000L, 0L, 0, 1)));
        assertEquals(100_000L, service.thresholdFen(merchant(1L, 199_999L, 0L, 0, 1)));
    }

    // ============================== 退款瀑布扣赔（既有语义） ==============================

    @Test
    @DisplayName("partialDeduct_扣赔后跌破50%_置预警并登记DEPOSIT_ALERT(outbox)")
    void deduct_crossThreshold_publishAlert() {
        SettMerchant pre = merchant(7L, 200_000L, 120_000L, 0, 1);
        SettMerchant post = merchant(7L, 200_000L, 90_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(pre);
        when(merchantMapper.changeDepositPartial(7L, 30_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post);
        when(noGenerator.nextDepositLogNo()).thenReturn("DP001");

        DepositDeductResult result = service.deductPartialForRefund(7L, 30_000L, "RF1");

        assertEquals(30_000L, result.getActualFen());
        assertEquals(90_000L, result.getBalanceAfterFen());
        assertEquals(1, post.getDepositAlerted());
        verify(merchantMapper).updateById(post);
        verify(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("CLAW:RF1"));
        ArgumentCaptor<SettDepositLog> cap = ArgumentCaptor.forClass(SettDepositLog.class);
        verify(depositLogMapper).insert(cap.capture());
        assertEquals(DepositLogTypes.REFUND_COMPENSATE, cap.getValue().getLogType());
        assertEquals(-30_000L, cap.getValue().getAmountFen());
        assertEquals("RF1", cap.getValue().getBizNo());
    }

    @Test
    @DisplayName("partialDeduct_已在预警中_不重复告警；保证金为0_返回0不扣")
    void deduct_existingCases() {
        SettMerchant pre = merchant(7L, 200_000L, 60_000L, 1, 1);
        SettMerchant post = merchant(7L, 200_000L, 50_000L, 1, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(pre);
        when(merchantMapper.changeDepositPartial(7L, 10_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post);

        DepositDeductResult r = service.deductPartialForRefund(7L, 10_000L, "RF2");
        assertEquals(10_000L, r.getActualFen());
        verify(outboxPublisher, never()).publish(any(), any(), any(), any());

        when(merchantMapper.selectForUpdate(8L)).thenReturn(merchant(8L, 1000L, 0L, 0, 1));
        DepositDeductResult zero = service.deductPartialForRefund(8L, 10_000L, "RFZ");
        assertEquals(0L, zero.getActualFen());
        verify(merchantMapper, never()).changeDepositPartial(eq(8L), anyLong());
    }

    // ============================== 缴费三段式 ==============================

    @Test
    @DisplayName("缴费_建单status10不动余额_支付域建单回写payNo返回payUrl")
    void initiateDepositPay_success() {
        SettMerchant m = merchant(7L, 200_000L, 90_000L, 0, 1);
        when(merchantService.requireMerchant(7L)).thenReturn(m);
        PaymentDTO payment = PaymentDTO.builder().payNo("P100").payUrl("https://pay/x").build();
        when(payClient.createPayment(any())).thenReturn(Result.success(payment));

        DepositPayVO vo = service.initiateDepositPay(7L, payReq(110_000L, null), "DP100");

        assertEquals("DP100", vo.getLogNo());
        assertEquals("P100", vo.getPayNo());
        assertEquals("https://pay/x", vo.getPayUrl());
        // 建单阶段不动保证金余额
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        ArgumentCaptor<SettDepositLog> cap = ArgumentCaptor.forClass(SettDepositLog.class);
        verify(depositLogMapper).insert(cap.capture());
        assertEquals(SettDepositLog.STATUS_PROCESSING, cap.getValue().getStatus());
        assertEquals(DepositLogTypes.PAY, cap.getValue().getLogType());
        verify(depositLogMapper).updatePayNo("DP100", "P100");
    }

    @Test
    @DisplayName("缴费_重复clientToken_返回同一logNo不重复建单")
    void initiateDepositPay_duplicateClientToken_sameLog() {
        SettDepositLog existing = refundLog("DP999", 7L, 100L, SettDepositLog.STATUS_PROCESSING, "");
        existing.setLogType(DepositLogTypes.PAY);
        existing.setAmountFen(100L);
        when(depositLogMapper.selectOne(any())).thenReturn(existing);
        PaymentDTO payment = PaymentDTO.builder().payNo("P999").payUrl("u").build();
        when(payClient.createPayment(any())).thenReturn(Result.success(payment));

        DepositPayVO vo = service.initiateDepositPay(7L, payReq(100L, "tok-1"), "DPNEW");

        assertEquals("DP999", vo.getLogNo());
        verify(depositLogMapper, never()).insert(any());
    }

    @Test
    @DisplayName("缴费_支付域失败/异常_抛DEPENDENCY_FAIL且不动余额(零伪成功)")
    void initiateDepositPay_payFail_throw() {
        when(merchantService.requireMerchant(7L)).thenReturn(merchant(7L, 1000L, 0L, 0, 1));
        when(payClient.createPayment(any())).thenReturn(Result.fail(
                com.shop.common.exception.ErrorCode.DEPENDENCY_FAIL, "boom"));
        assertThrows(BizException.class, () -> service.initiateDepositPay(7L, payReq(100L, null), "DP101"));

        when(payClient.createPayment(any())).thenThrow(new RuntimeException("network"));
        assertThrows(BizException.class, () -> service.initiateDepositPay(7L, payReq(100L, null), "DP102"));
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
    }

    // ============================== 罚款 ==============================

    @Test
    @DisplayName("罚款_余额充足_扣保证金+log30+平台43入账同事务+低于50%告警")
    void fine_success() {
        SettMerchant locked = merchant(7L, 200_000L, 200_000L, 0, 1);
        SettMerchant post = merchant(7L, 200_000L, 90_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(locked);
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        when(merchantMapper.changeDeposit(7L, -110_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPF1");

        SettDepositLog log = service.fine(7L, 110_000L, "违规", "fine-token-1");

        assertEquals(DepositLogTypes.FINE, log.getLogType());
        assertEquals(SettDepositLog.STATUS_SUCCESS, log.getStatus());
        assertEquals(-110_000L, log.getAmountFen());
        assertEquals("fine-token-1", log.getBizNo());
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "DPF1",
                FlowChangeTypes.DEPOSIT_FINE_INCOME, 110_000L, "保证金罚款入账");
        verify(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("FINE:DPF1"));
    }

    @Test
    @DisplayName("罚款_余额不足_拒绝且余额不变；clientToken重放不重复扣")
    void fine_insufficientAndReplay() {
        SettMerchant locked = merchant(7L, 200_000L, 50_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(locked);
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.fine(7L, 60_000L, "r", "t-x"));
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        verify(accountService, never()).creditAvailable(anyLong(), anyInt(), any(), anyInt(), anyLong(), any());

        SettDepositLog existing = refundLog("DPF2", 7L, 10L, SettDepositLog.STATUS_SUCCESS, "");
        existing.setLogType(DepositLogTypes.FINE);
        when(depositLogMapper.selectOne(any())).thenReturn(existing);
        SettDepositLog again = service.fine(7L, 10L, "r", "t-y");
        assertEquals("DPF2", again.getLogNo());
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
    }

    // ============================== 清退登记 ==============================

    @Test
    @DisplayName("resign_正常商户_进入清退观察期状态2")
    void resign_ok() {
        SettMerchant m = merchant(7L, 200_000L, 200_000L, 0, MerchantStatuses.NORMAL);
        when(merchantService.requireActiveMerchant(7L)).thenReturn(m);
        service.resign(7L);
        assertEquals(MerchantStatuses.RESIGNING, m.getStatus());
        verify(merchantMapper).updateById(m);
    }

    // ============================== 清退退还（90 天 + 纠纷校验 + 代发） ==============================

    private SettMerchant resigning(long id, long balance, LocalDateTime resignTime) {
        SettMerchant m = merchant(id, 200_000L, balance, 0, MerchantStatuses.RESIGNING);
        m.setResignTime(resignTime);
        return m;
    }

    private SettWithdraw successWithdraw(long merchantId) {
        SettWithdraw w = new SettWithdraw();
        w.setId(9L);
        w.setWithdrawNo("WDOK");
        w.setMerchantId(merchantId);
        w.setChannel(WithdrawChannels.BANK_CARD);
        w.setChannelAccount(cipher.encrypt("62220202"));
        w.setAccountName(cipher.encrypt("张三"));
        w.setBankName("招商银行");
        w.setStatus(30);
        return w;
    }

    @Test
    @DisplayName("清退_观察期未满_不查纠纷不打款")
    void scan_before90Days_skip() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 3, 0);
        when(merchantService.listByStatus(MerchantStatuses.RESIGNING))
                .thenReturn(List.of(resigning(1L, 200_000L, now.minusDays(89))));
        assertTrue(service.scanAndRefundResigned(now).isEmpty());
        verify(aftersaleClient, never()).existsOpenDispute(anyLong(), any());
        verify(remitRouter, never()).route(anyInt());
    }

    @Test
    @DisplayName("清退_满90天但存在未终结纠纷_不打款不清零次日再判")
    void scan_openDispute_noRemit() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 3, 0);
        when(merchantService.listByStatus(MerchantStatuses.RESIGNING))
                .thenReturn(List.of(resigning(1L, 200_000L, now.minusDays(90))));
        when(aftersaleClient.existsOpenDispute(eq(1L), any()))
                .thenReturn(Result.success(MerchantDisputeDTO.builder().exists(true).openCount(2L).build()));
        assertTrue(service.scanAndRefundResigned(now).isEmpty());
        verify(remitRouter, never()).route(anyInt());
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
    }

    @Test
    @DisplayName("清退_无纠纷无成功提现账户_挂人工DEPOSIT_ALERT不打款")
    void scan_noAccount_manualHang() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 3, 0);
        when(merchantService.listByStatus(MerchantStatuses.RESIGNING))
                .thenReturn(List.of(resigning(1L, 200_000L, now.minusDays(90))));
        when(aftersaleClient.existsOpenDispute(eq(1L), any()))
                .thenReturn(Result.success(MerchantDisputeDTO.builder().exists(false).build()));
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPR1");
        when(depositLogMapper.casHangAlerted("DPR1")).thenReturn(1);
        when(withdrawMapper.selectLatestSuccess(1L)).thenReturn(null);
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 200_000L, 200_000L, 0, 2));

        assertTrue(service.scanAndRefundResigned(now).isEmpty());
        verify(remitRouter, never()).route(anyInt());
        verify(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("HANG:DPR1"));
    }

    @Test
    @DisplayName("清退_无纠纷有账户_建log40(status10)事务外代发受理落渠道号_余额不动")
    void scan_noDispute_remitAccepted() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 3, 0);
        when(merchantService.listByStatus(MerchantStatuses.RESIGNING))
                .thenReturn(List.of(resigning(1L, 200_000L, now.minusDays(90))));
        when(aftersaleClient.existsOpenDispute(eq(1L), any()))
                .thenReturn(Result.success(MerchantDisputeDTO.builder().exists(false).build()));
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPR2");
        when(withdrawMapper.selectLatestSuccess(1L)).thenReturn(successWithdraw(1L));
        com.shop.settlement.remit.RemitChannelClient client =
                org.mockito.Mockito.mock(com.shop.settlement.remit.RemitChannelClient.class);
        when(remitRouter.route(WithdrawChannels.BANK_CARD)).thenReturn(client);
        when(client.remit(any())).thenReturn(RemitResult.accepted("CHR2"));

        List<Long> advanced = service.scanAndRefundResigned(now);

        assertEquals(List.of(1L), advanced);
        verify(depositLogMapper).markRemitAccepted("DPR2", "CHR2");
        // 受理阶段余额不动、商户不关单
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        verify(merchantMapper, never()).updateById(any());
    }

    @Test
    @DisplayName("清退_已受理(status10+渠道号)_重复Job不重复代发")
    void scan_alreadyAccepted_skipResubmit() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 3, 0);
        when(merchantService.listByStatus(MerchantStatuses.RESIGNING))
                .thenReturn(List.of(resigning(1L, 200_000L, now.minusDays(90))));
        when(aftersaleClient.existsOpenDispute(eq(1L), any()))
                .thenReturn(Result.success(MerchantDisputeDTO.builder().exists(false).build()));
        when(depositLogMapper.selectOne(any()))
                .thenReturn(refundLog("DPR3", 1L, 200_000L, SettDepositLog.STATUS_PROCESSING, "CHR3"));

        assertTrue(service.scanAndRefundResigned(now).isEmpty());
        verify(remitRouter, never()).route(anyInt());
    }

    @Test
    @DisplayName("清退_aftersale异常/受理拒绝_跳过本轮不伪造结论")
    void scan_aftersaleFailOrReject_skip() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 3, 0);
        when(merchantService.listByStatus(MerchantStatuses.RESIGNING))
                .thenReturn(List.of(resigning(1L, 200_000L, now.minusDays(90)),
                        resigning(2L, 200_000L, now.minusDays(91))));
        when(aftersaleClient.existsOpenDispute(eq(1L), any())).thenThrow(new RuntimeException("timeout"));
        when(aftersaleClient.existsOpenDispute(eq(2L), any()))
                .thenReturn(Result.success(MerchantDisputeDTO.builder().exists(false).build()));
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPR4");
        when(withdrawMapper.selectLatestSuccess(2L)).thenReturn(successWithdraw(2L));
        com.shop.settlement.remit.RemitChannelClient client =
                org.mockito.Mockito.mock(com.shop.settlement.remit.RemitChannelClient.class);
        when(remitRouter.route(anyInt())).thenReturn(client);
        when(client.remit(any())).thenReturn(RemitResult.rejected("渠道维护"));

        List<Long> advanced = service.scanAndRefundResigned(now);
        assertTrue(advanced.isEmpty());
        verify(depositLogMapper, never()).markRemitAccepted(any(), any());
    }

    // ============================== 查询补偿（退还终态） ==============================

    @Test
    @DisplayName("退还查询_成功CAS获胜_余额置零+商户30+41流水；CAS失败零副作用")
    void queryRefund_success_cas() {
        SettDepositLog rl = refundLog("DPRQ1", 1L, 200_000L, SettDepositLog.STATUS_PROCESSING, "CHQ1");
        when(depositLogMapper.selectRefundRemitPending(anyInt())).thenReturn(List.of(rl));
        when(depositLogMapper.touchQuery(eq("DPRQ1"), any())).thenReturn(1);
        when(withdrawMapper.selectLatestSuccess(1L)).thenReturn(successWithdraw(1L));
        com.shop.settlement.remit.RemitChannelClient client =
                org.mockito.Mockito.mock(com.shop.settlement.remit.RemitChannelClient.class);
        when(remitRouter.route(WithdrawChannels.BANK_CARD)).thenReturn(client);
        when(client.query(any())).thenReturn(RemitQueryResult.success("CHQ1"));
        when(depositLogMapper.casStatus("DPRQ1", 10, 20)).thenReturn(1);
        when(merchantMapper.selectForUpdate(1L)).thenReturn(merchant(1L, 200_000L, 200_000L, 0, 2));
        when(merchantMapper.changeDeposit(1L, -200_000L)).thenReturn(1);
        SettMerchant resigned = merchant(1L, 200_000L, 0L, 0, MerchantStatuses.RESIGNING);
        when(merchantService.requireMerchant(1L)).thenReturn(resigned);

        assertEquals(1, service.queryPendingRefunds(LocalDateTime.now()));
        assertEquals(MerchantStatuses.RESIGNED, resigned.getStatus());
        verify(merchantMapper).updateById(resigned);
        verify(accountService).writeZeroFlow(1L, AccountRole.MERCHANT, "DPRQ1",
                FlowChangeTypes.DEPOSIT_REFUND, "清退保证金打款成功");

        // CAS 失败：重复查询/并发零副作用
        when(depositLogMapper.casStatus("DPRQ1", 10, 20)).thenReturn(0);
        assertFalse(service.confirmRefundSuccess(rl, "CHQ1"));
        verify(merchantMapper, never()).changeDeposit(eq(2L), anyLong());
    }

    @Test
    @DisplayName("退还查询_失败_10转30余额保留+DEPOSIT_ALERT；处理中不动；touch失败不查询")
    void queryRefund_failAndProcessing() {
        SettDepositLog rl = refundLog("DPRQ2", 1L, 200_000L, SettDepositLog.STATUS_PROCESSING, "CHQ2");
        when(depositLogMapper.selectRefundRemitPending(anyInt())).thenReturn(List.of(rl));
        when(depositLogMapper.touchQuery(eq("DPRQ2"), any())).thenReturn(1);
        when(withdrawMapper.selectLatestSuccess(1L)).thenReturn(successWithdraw(1L));
        com.shop.settlement.remit.RemitChannelClient client =
                org.mockito.Mockito.mock(com.shop.settlement.remit.RemitChannelClient.class);
        when(remitRouter.route(anyInt())).thenReturn(client);
        when(client.query(any())).thenReturn(RemitQueryResult.fail("CHQ2", "账户冻结"));
        when(depositLogMapper.casStatus("DPRQ2", 10, 30)).thenReturn(1);
        when(merchantService.requireMerchant(1L)).thenReturn(merchant(1L, 200_000L, 200_000L, 0, 2));

        service.queryPendingRefunds(LocalDateTime.now());
        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        verify(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("FAIL:DPRQ2"));

        // 处理中：保持 10，不 CAS
        when(client.query(any())).thenReturn(RemitQueryResult.processing());
        service.queryPendingRefunds(LocalDateTime.now());
        verify(depositLogMapper, never()).casStatus("DPRQ2", 10, 20);

        // touch 未获胜：不发起渠道查询
        when(depositLogMapper.touchQuery(eq("DPRQ2"), any())).thenReturn(0);
        service.queryPendingRefunds(LocalDateTime.now());
        verify(client, org.mockito.Mockito.times(2)).query(any());
    }

    @Test
    @DisplayName("清退_余额为0_写0元退还留痕直接关单商户30")
    void scan_zeroBalance_close() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 16, 3, 0);
        SettMerchant m = resigning(3L, 0L, now.minusDays(100));
        when(merchantService.listByStatus(MerchantStatuses.RESIGNING)).thenReturn(List.of(m));
        when(aftersaleClient.existsOpenDispute(eq(3L), any()))
                .thenReturn(Result.success(MerchantDisputeDTO.builder().exists(false).build()));
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPR0");
        when(merchantService.requireMerchant(3L)).thenReturn(m);

        List<Long> advanced = service.scanAndRefundResigned(now);
        assertEquals(List.of(3L), advanced);
        assertEquals(MerchantStatuses.RESIGNED, m.getStatus());
        verify(remitRouter, never()).route(anyInt());
        ArgumentCaptor<SettDepositLog> cap = ArgumentCaptor.forClass(SettDepositLog.class);
        verify(depositLogMapper).insert(cap.capture());
        assertEquals(SettDepositLog.STATUS_SUCCESS, cap.getValue().getStatus());
        assertEquals(0L, cap.getValue().getAmountFen());
        verify(merchantMapper).updateById(m);
    }

    // ============================== O6 业务指标 ==============================

    private void assertNoHighCardinalityTagKeys() {
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            String k = tag.getKey();
            assertFalse(k.equals("merchantId") || k.equals("userId") || k.equals("logNo")
                            || k.equals("refundNo"),
                    "高基数标签键泄露: " + k);
        }));
    }

    @Test
    @DisplayName("指标_罚款余额不足_shop_deposit_insufficient_total{event=FINE}递增")
    void metrics_fineInsufficient_counterIncrement() {
        SettMerchant locked = merchant(7L, 200_000L, 50_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(locked);
        when(depositLogMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.fine(7L, 60_000L, "r", "t-metric-1"));

        assertEquals(1.0, registry.counter(DepositService.DEPOSIT_INSUFFICIENT_TOTAL,
                "event", DepositService.EVENT_FINE).count());
        assertNoHighCardinalityTagKeys();
    }

    @Test
    @DisplayName("指标_扣赔跌破50%_{event=CLAWBACK}递增；标签基数受控")
    void metrics_clawbackBelowThreshold_counterIncrement() {
        SettMerchant pre = merchant(7L, 200_000L, 120_000L, 0, 1);
        SettMerchant post = merchant(7L, 200_000L, 90_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(pre);
        when(merchantMapper.changeDepositPartial(7L, 30_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post);
        when(noGenerator.nextDepositLogNo()).thenReturn("DP001");

        service.deductPartialForRefund(7L, 30_000L, "RF1");

        assertEquals(1.0, registry.counter(DepositService.DEPOSIT_INSUFFICIENT_TOTAL,
                "event", DepositService.EVENT_CLAWBACK).count());
        assertNoHighCardinalityTagKeys();
    }

    @Test
    @DisplayName("指标_清退退还挂人工_{event=RESIGN}递增")
    void metrics_resignManualHang_counterIncrement() {
        when(merchantService.requireMerchant(7L))
                .thenReturn(merchant(7L, 200_000L, 40_000L, 0, MerchantStatuses.RESIGNING));
        when(depositLogMapper.casHangAlerted("DPM7")).thenReturn(1);

        service.alertManualHang(7L, "DPM7");

        assertEquals(1.0, registry.counter(DepositService.DEPOSIT_INSUFFICIENT_TOTAL,
                "event", DepositService.EVENT_RESIGN).count());
        verify(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("HANG:DPM7"));
        assertNoHighCardinalityTagKeys();
    }

    // ============================== R4-25 outbox UK 去重回归 ==============================

    @Test
    @DisplayName("R4-25_清退挂起_次日重扫CAS未获胜_不发预警不增计数")
    void alertManualHang_secondScan_casLost_silent() {
        when(depositLogMapper.casHangAlerted("DPR1")).thenReturn(0);

        service.alertManualHang(1L, "DPR1");

        verify(outboxPublisher, never()).publish(any(), any(), any(), any());
        assertEquals(0.0, registry.counter(DepositService.DEPOSIT_INSUFFICIENT_TOTAL,
                "event", DepositService.EVENT_RESIGN).count());
    }

    @Test
    @DisplayName("R4-25_罚款_充值复位预警后再次跌破_两笔罚款outbox键各不相同")
    void fine_afterRechargeReset_keysDistinct() {
        SettMerchant locked1 = merchant(7L, 200_000L, 200_000L, 0, 1);
        SettMerchant post1 = merchant(7L, 200_000L, 90_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(locked1);
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        when(merchantMapper.changeDeposit(7L, -110_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post1);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPFA");

        service.fine(7L, 110_000L, "违规一", "fine-token-a");

        // 商户充值到账后 deposit_alerted 复位（DepositPaySettlementService），再次被罚跌破
        SettMerchant locked2 = merchant(7L, 200_000L, 200_000L, 0, 1);
        SettMerchant post2 = merchant(7L, 200_000L, 80_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(locked2);
        when(merchantMapper.changeDeposit(7L, -120_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post2);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPFB");

        service.fine(7L, 120_000L, "违规二", "fine-token-b");

        verify(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("FINE:DPFA"));
        verify(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("FINE:DPFB"));
    }

    @Test
    @DisplayName("R4-25_预警outbox撞UK_罚款主事务照常提交不抛出")
    void fine_outboxDuplicateKey_mainTransactionSurvives() {
        SettMerchant locked = merchant(7L, 200_000L, 200_000L, 0, 1);
        SettMerchant post = merchant(7L, 200_000L, 90_000L, 0, 1);
        when(merchantMapper.selectForUpdate(7L)).thenReturn(locked);
        when(depositLogMapper.selectOne(any())).thenReturn(null);
        when(merchantMapper.changeDeposit(7L, -110_000L)).thenReturn(1);
        when(merchantService.requireMerchant(7L)).thenReturn(post);
        when(noGenerator.nextDepositLogNo()).thenReturn("DPFD");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DuplicateKeyException(
                "Duplicate entry 'FINE:DPFD' for key 'uk_topic_tag_bizkey'"))
                .when(outboxPublisher).publish(eq(MqTopics.DEPOSIT_ALERT), any(), any(), eq("FINE:DPFD"));

        SettDepositLog log = service.fine(7L, 110_000L, "违规", "fine-token-dup");

        // 资金动作全部完成，异常被吞在发布层
        assertEquals("DPFD", log.getLogNo());
        verify(merchantMapper).changeDeposit(7L, -110_000L);
        verify(accountService).creditAvailable(eq(0L), eq(AccountRole.PLATFORM), eq("DPFD"),
                eq(FlowChangeTypes.DEPOSIT_FINE_INCOME), eq(110_000L), any());
    }
}
