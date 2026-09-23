package com.shop.settlement.clearing.service;

import com.shop.api.pay.enums.RefundTypes;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.ClearingStages;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.entity.SettClearingReverse;
import com.shop.settlement.clearing.enums.ReverseStatuses;
import com.shop.settlement.clearing.mapper.ClearingMapper;
import com.shop.settlement.clearing.mapper.ClearingReverseMapper;
import com.shop.settlement.deposit.service.DepositDeductResult;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.engine.RefundCalculator;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.mq.service.MqConsumeService;
import com.shop.settlement.support.LambdaTableSupport;
import com.shop.settlement.support.SettleNoGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退款清算冲正单测（design 7.5，P1-10/P1-12）：
 * MQ 重复 / 冲正重复 / 缺单重试；全额退款待结算足额扣减且 stage→40；
 * 商户承担部分严格按瀑布「待结算 → 可提现余额 → 保证金」扣减；
 * 三档合计仍不足时挂起（status=2 + shortfall + REFUND_SHORTFALL outbox），不抛异常卡死。
 */
@ExtendWith(MockitoExtension.class)
class ClearingReverseServiceTest {

    @Mock private ClearingMapper clearingMapper;
    @Mock private ClearingReverseMapper reverseMapper;
    @Mock private MqConsumeService mqConsumeService;
    @Mock private AccountService accountService;
    @Mock private DepositService depositService;
    @Mock private DistributedLockTemplate lockTemplate;
    @Mock private SettleNoGenerator noGenerator;
    @Mock private OutboxPublisher outboxPublisher;

    private ClearingReverseService service;

    @BeforeAll
    static void initLambdaCache() {
        LambdaTableSupport.init();
    }

    @BeforeEach
    void setUp() {
        service = new ClearingReverseService(clearingMapper, reverseMapper, mqConsumeService,
                new RefundCalculator(), accountService, depositService, lockTemplate,
                noGenerator, outboxPublisher);
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(lockTemplate).execute(anyString(), any(Runnable.class));
    }

    private SettClearing clearing(long pay, long receivable, long commission, long subsidy) {
        return clearing(pay, receivable, commission, subsidy, ClearingStages.WAIT_SETTLE);
    }

    private SettClearing clearing(long pay, long receivable, long commission, long subsidy, int stage) {
        SettClearing c = new SettClearing();
        c.setId(11L);
        c.setClearingNo("CL001");
        c.setOrderNo("O1");
        c.setMerchantId(777L);
        c.setStage(stage);
        c.setPayAmountFen(pay);
        c.setMerchantReceivableFen(receivable);
        c.setPlatformCommissionFen(commission);
        c.setMarketingSubsidyFen(subsidy);
        c.setTechFeeFen(50L);
        c.setChannelFeeFen(60L);
        return c;
    }

    private RefundSucceededEvent event(String refundNo, long amount, int refundType) {
        return RefundSucceededEvent.builder()
                .refundNo(refundNo).orderNo("O1").amountFen(amount).refundType(refundType).build();
    }

    private void stubBaseOk(String reverseNo) {
        when(clearingMapper.update(any(), any())).thenReturn(1);
        when(noGenerator.nextReverseNo()).thenReturn(reverseNo);
    }

    @Test
    @DisplayName("mq_重复eventId_直接ACK不执行冲正")
    void duplicateEvent_skip() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(false);
        service.onRefundSucceeded(event("RF0", 1, RefundTypes.FULL.getCode()));
        verify(reverseMapper, never()).insert(any());
        verify(clearingMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("refundNo_冲正明细已存在_幂等跳过")
    void duplicateRefundNo_skip() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(new SettClearingReverse());
        service.onRefundSucceeded(event("RF0", 1, RefundTypes.FULL.getCode()));
        verify(accountService, never()).debitAvailable(anyLong(), anyInt(), anyString(),
                anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("清算单不存在_抛依赖异常等重试")
    void missingClearing_throw() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class,
                () -> service.onRefundSucceeded(event("RF0", 1, RefundTypes.FULL.getCode())));
    }

    @Test
    @DisplayName("全额退款_待结算足额_佣金补贴全退且stage转40，不碰可提现与保证金")
    void fullRefund_pendingEnough() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any())).thenReturn(clearing(10_000, 8_000, 1_000, 500));
        when(accountService.debitPendingPartial(eq(777L), eq(AccountRole.MERCHANT), eq("RF1"),
                eq(FlowChangeTypes.REFUND_FROM_PENDING), eq(8_000L), anyString())).thenReturn(8_000L);
        stubBaseOk("RV001");

        service.onRefundSucceeded(event("RF1", 10_000, RefundTypes.FULL.getCode()));

        verify(accountService).debitAvailable(0L, AccountRole.PLATFORM, "RF1",
                FlowChangeTypes.COMMISSION_REVERSE, 1_000L, "退款冲正平台佣金");
        verify(accountService).creditAvailable(0L, AccountRole.MARKETING, "RF1",
                FlowChangeTypes.SUBSIDY_REVERSE, 500L, "退款冲正营销补贴回营销账户");
        verify(accountService, never()).debitAvailablePartial(anyLong(), anyInt(), anyString(),
                anyInt(), anyLong(), anyString());
        verify(depositService, never()).deductPartialForRefund(anyLong(), anyLong(), anyString());
        verify(outboxPublisher, never()).publish(eq(MqTopics.REFUND_SHORTFALL),
                any(), any(), anyString());

        ArgumentCaptor<SettClearingReverse> cap = ArgumentCaptor.forClass(SettClearingReverse.class);
        verify(reverseMapper).insert(cap.capture());
        SettClearingReverse r = cap.getValue();
        assertEquals(10_000, r.getRefundRatioBps());
        assertEquals(8_000, r.getReverseMerchantFen());
        assertEquals(8_000, r.getFromPendingFen());
        assertEquals(0, r.getFromAvailableFen());
        assertEquals(0, r.getFromDepositFen());
        assertEquals(0, r.getShortfallFen());
        assertEquals(ReverseStatuses.FULLY_DEDUCTED, r.getStatus());
        assertEquals(1, r.getFullReversed());
    }

    @Test
    @DisplayName("部分退款_待结算1000可提现1000保证金2000_瀑布三档依次扣足额(status=1)")
    void partialRefund_waterfallAllThreeTiers() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any())).thenReturn(clearing(10_000, 8_000, 1_000, 500));
        when(accountService.debitPendingPartial(eq(777L), eq(AccountRole.MERCHANT), eq("RF2"),
                eq(FlowChangeTypes.REFUND_FROM_PENDING), eq(4_000L), anyString())).thenReturn(1_000L);
        when(accountService.debitAvailablePartial(eq(777L), eq(AccountRole.MERCHANT), eq("RF2"),
                eq(FlowChangeTypes.REFUND_FROM_AVAILABLE), eq(3_000L), anyString())).thenReturn(1_000L);
        when(depositService.deductPartialForRefund(777L, 2_000L, "RF2"))
                .thenReturn(new DepositDeductResult(2_000L, 0L));
        stubBaseOk("RV002");

        service.onRefundSucceeded(event("RF2", 5_000, RefundTypes.PART.getCode()));

        verify(accountService).debitAvailable(0L, AccountRole.PLATFORM, "RF2",
                FlowChangeTypes.COMMISSION_REVERSE, 500L, "退款冲正平台佣金");
        verify(accountService).creditAvailable(0L, AccountRole.MARKETING, "RF2",
                FlowChangeTypes.SUBSIDY_REVERSE, 250L, "退款冲正营销补贴回营销账户");
        verify(accountService).writeZeroFlow(777L, AccountRole.MERCHANT, "RF2",
                FlowChangeTypes.REFUND_FROM_DEPOSIT, "退款冲正不足部分扣保证金 2000分");

        ArgumentCaptor<SettClearingReverse> cap = ArgumentCaptor.forClass(SettClearingReverse.class);
        verify(reverseMapper).insert(cap.capture());
        SettClearingReverse r = cap.getValue();
        assertEquals(4_000, r.getReverseMerchantFen());
        assertEquals(1_000, r.getFromPendingFen());
        assertEquals(1_000, r.getFromAvailableFen());
        assertEquals(2_000, r.getFromDepositFen());
        assertEquals(0, r.getShortfallFen());
        assertEquals(ReverseStatuses.FULLY_DEDUCTED, r.getStatus());
        assertEquals(0, r.getFullReversed());
        verify(outboxPublisher, never()).publish(eq(MqTopics.REFUND_SHORTFALL),
                any(), any(), anyString());
    }

    @Test
    @DisplayName("stage30已结算_待结算0可提现足额_只扣可提现不扣保证金(P1-10核心场景)")
    void settledStage_availableEnough_noDeposit() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        // 售后期长于结算周期：清算单已 stage=30，待结算早已转可提现
        when(clearingMapper.selectOne(any()))
                .thenReturn(clearing(10_000, 8_000, 1_000, 500, ClearingStages.SETTLED));
        when(accountService.debitPendingPartial(eq(777L), eq(AccountRole.MERCHANT), eq("RF6"),
                eq(FlowChangeTypes.REFUND_FROM_PENDING), eq(4_000L), anyString())).thenReturn(0L);
        when(accountService.debitAvailablePartial(eq(777L), eq(AccountRole.MERCHANT), eq("RF6"),
                eq(FlowChangeTypes.REFUND_FROM_AVAILABLE), eq(4_000L), anyString())).thenReturn(4_000L);
        stubBaseOk("RV006");

        service.onRefundSucceeded(event("RF6", 5_000, RefundTypes.PART.getCode()));

        // 可提现足额，保证金不动（修复前错误行为是全额扣保证金）
        verify(depositService, never()).deductPartialForRefund(anyLong(), anyLong(), anyString());
        ArgumentCaptor<SettClearingReverse> cap = ArgumentCaptor.forClass(SettClearingReverse.class);
        verify(reverseMapper).insert(cap.capture());
        assertEquals(0, cap.getValue().getFromPendingFen());
        assertEquals(4_000, cap.getValue().getFromAvailableFen());
        assertEquals(0, cap.getValue().getFromDepositFen());
        assertEquals(ReverseStatuses.FULLY_DEDUCTED, cap.getValue().getStatus());
    }

    @Test
    @DisplayName("stage30_待结算0可提现0_商户部分全部走保证金足额(status=1)")
    void settledStage_allFromDeposit() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any()))
                .thenReturn(clearing(10_000, 8_000, 1_000, 500, ClearingStages.SETTLED));
        when(accountService.debitPendingPartial(anyLong(), anyInt(), anyString(),
                anyInt(), anyLong(), anyString())).thenReturn(0L);
        when(accountService.debitAvailablePartial(anyLong(), anyInt(), anyString(),
                anyInt(), anyLong(), anyString())).thenReturn(0L);
        when(depositService.deductPartialForRefund(777L, 4_000L, "RF3"))
                .thenReturn(new DepositDeductResult(4_000L, 6_000L));
        stubBaseOk("RV003");

        service.onRefundSucceeded(event("RF3", 5_000, RefundTypes.PART.getCode()));

        verify(depositService).deductPartialForRefund(777L, 4_000L, "RF3");
        ArgumentCaptor<SettClearingReverse> cap = ArgumentCaptor.forClass(SettClearingReverse.class);
        verify(reverseMapper).insert(cap.capture());
        assertEquals(4_000, cap.getValue().getFromDepositFen());
        assertEquals(ReverseStatuses.FULLY_DEDUCTED, cap.getValue().getStatus());
    }

    @Test
    @DisplayName("P1-10_三档扣尽仍不足_挂起status2记shortfall并发REFUND_SHORTFALL_不抛异常ACK")
    void allTiersExhausted_suspendNoThrow() {
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any()))
                .thenReturn(clearing(10_000, 8_000, 1_000, 500, ClearingStages.SETTLED));
        // 商户承担 4000：待结算 500 + 可提现 1500 + 保证金 1000 = 3000，缺口 1000
        when(accountService.debitPendingPartial(eq(777L), eq(AccountRole.MERCHANT), eq("RF7"),
                eq(FlowChangeTypes.REFUND_FROM_PENDING), eq(4_000L), anyString())).thenReturn(500L);
        when(accountService.debitAvailablePartial(eq(777L), eq(AccountRole.MERCHANT), eq("RF7"),
                eq(FlowChangeTypes.REFUND_FROM_AVAILABLE), eq(3_500L), anyString())).thenReturn(1_500L);
        when(depositService.deductPartialForRefund(777L, 2_000L, "RF7"))
                .thenReturn(new DepositDeductResult(1_000L, 0L));
        stubBaseOk("RV007");

        // 关键：不再抛 DEPOSIT_NOT_ENOUGH 无限重试，处理正常返回（消息可 ACK）
        service.onRefundSucceeded(event("RF7", 5_000, RefundTypes.PART.getCode()));

        ArgumentCaptor<SettClearingReverse> cap = ArgumentCaptor.forClass(SettClearingReverse.class);
        verify(reverseMapper).insert(cap.capture());
        SettClearingReverse r = cap.getValue();
        assertEquals(500, r.getFromPendingFen());
        assertEquals(1_500, r.getFromAvailableFen());
        assertEquals(1_000, r.getFromDepositFen());
        assertEquals(1_000, r.getShortfallFen());
        assertEquals(ReverseStatuses.PARTIAL_SUSPENDED, r.getStatus());
        // 缺口告警事件同事务登记 outbox，bizKey=refundNo
        verify(outboxPublisher).publish(eq(MqTopics.REFUND_SHORTFALL), any(), any(), eq("RF7"));
    }

    @Test
    @DisplayName("B11_含保费订单部分退款_比例分母不含保费(5000/10000=5000bps)且保费无任何冲正")
    void partialRefund_withPremium_ratioExcludesPremium() {
        SettClearing c = clearing(10_100, 8_000, 1_000, 500);
        c.setInsurancePremiumFen(100L);
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any())).thenReturn(c);
        when(accountService.debitPendingPartial(eq(777L), eq(AccountRole.MERCHANT), eq("RFP1"),
                eq(FlowChangeTypes.REFUND_FROM_PENDING), eq(4_000L), anyString())).thenReturn(4_000L);
        stubBaseOk("RVP1");

        service.onRefundSucceeded(event("RFP1", 5_000, RefundTypes.PART.getCode()));

        verify(accountService).debitAvailable(0L, AccountRole.PLATFORM, "RFP1",
                FlowChangeTypes.COMMISSION_REVERSE, 500L, "退款冲正平台佣金");
        verify(accountService).creditAvailable(0L, AccountRole.MARKETING, "RFP1",
                FlowChangeTypes.SUBSIDY_REVERSE, 250L, "退款冲正营销补贴回营销账户");
        ArgumentCaptor<SettClearingReverse> cap = ArgumentCaptor.forClass(SettClearingReverse.class);
        verify(reverseMapper).insert(cap.capture());
        SettClearingReverse r = cap.getValue();
        assertEquals(5_000, r.getRefundRatioBps());
        assertEquals(4_000, r.getReverseMerchantFen());
        // 冲正全程不产生保费(16)流水：保费不退、不回营销、不扣商户
        verify(accountService, never()).creditAvailable(anyLong(), anyInt(), anyString(),
                eq(FlowChangeTypes.INSURANCE_PREMIUM_INCOME), anyLong(), anyString());
        verify(accountService, never()).debitAvailable(anyLong(), anyInt(), anyString(),
                eq(FlowChangeTypes.INSURANCE_PREMIUM_INCOME), anyLong(), anyString());
        verify(depositService, never()).deductPartialForRefund(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("B11_含保费订单商品侧全额退款_stage转40且保费100分不随退款冲减")
    void fullRefund_withPremium_premiumStaysOnPlatform() {
        // pay=10_100（商品 10_000 + 保费 100），退款事件金额仅商品侧 10_000（保费不退）
        SettClearing c = clearing(10_100, 8_000, 1_000, 500);
        c.setInsurancePremiumFen(100L);
        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any())).thenReturn(c);
        when(accountService.debitPendingPartial(eq(777L), eq(AccountRole.MERCHANT), eq("RFP2"),
                eq(FlowChangeTypes.REFUND_FROM_PENDING), eq(8_000L), anyString())).thenReturn(8_000L);
        stubBaseOk("RVP2");

        service.onRefundSucceeded(event("RFP2", 10_000, RefundTypes.FULL.getCode()));

        ArgumentCaptor<SettClearingReverse> cap = ArgumentCaptor.forClass(SettClearingReverse.class);
        verify(reverseMapper).insert(cap.capture());
        SettClearingReverse r = cap.getValue();
        assertEquals(10_000, r.getRefundRatioBps());
        assertEquals(8_000, r.getReverseMerchantFen());
        assertEquals(1, r.getFullReversed());
        // 无任何保费冲正/退还流水
        verify(accountService, never()).debitAvailable(anyLong(), anyInt(), anyString(),
                eq(FlowChangeTypes.INSURANCE_PREMIUM_INCOME), anyLong(), anyString());
    }

    @Test
    @DisplayName("清算单阶段更新0行(并发)_抛冲突异常")
    void updateConflict_throw() {        when(mqConsumeService.tryRecord(any(), any(), any(), any())).thenReturn(true);
        when(reverseMapper.selectOne(any())).thenReturn(null);
        when(clearingMapper.selectOne(any())).thenReturn(clearing(10_000, 8_000, 1_000, 500));
        when(accountService.debitPendingPartial(anyLong(), anyInt(), anyString(), anyInt(),
                anyLong(), anyString())).thenReturn(8_000L);
        when(noGenerator.nextReverseNo()).thenReturn("RV004");
        when(clearingMapper.update(any(), any())).thenReturn(0);

        assertThrows(BizException.class,
                () -> service.onRefundSucceeded(event("RF4", 10_000, RefundTypes.FULL.getCode())));
    }
}
