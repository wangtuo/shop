package com.shop.settlement.statement.service;

import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.ClearingStages;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.service.ClearingService;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.statement.entity.SettStatement;
import com.shop.settlement.statement.mapper.StatementMapper;
import com.shop.settlement.support.LambdaTableSupport;
import com.shop.settlement.support.SettleNoGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单笔清算单结算执行器单测（B11）：
 * 六笔既有记账之外增加第 7 笔运费险保费平台收入（流水类型 16）；
 * 保费按清算单原始金额全额入账，部分退款累计 reversed* 后净额结算时保费仍全额留平台。
 */
@ExtendWith(MockitoExtension.class)
class SettleClearingExecutorTest {

    @Mock private ClearingService clearingService;
    @Mock private StatementMapper statementMapper;
    @Mock private MerchantService merchantService;
    @Mock private AccountService accountService;
    @Mock private SettleNoGenerator noGenerator;
    @Mock private OutboxPublisher outboxPublisher;

    private SettleClearingExecutor executor;

    private final LocalDate today = LocalDate.of(2026, 9, 17);

    @BeforeAll
    static void initLambdaCache() {
        LambdaTableSupport.init();
    }

    @BeforeEach
    void setUp() {
        executor = new SettleClearingExecutor(clearingService, statementMapper, merchantService,
                accountService, noGenerator, outboxPublisher);
        SettStatement statement = new SettStatement();
        statement.setId(55L);
        statement.setStatementNo("ST26091701");
        statement.setMerchantId(777L);
        when(statementMapper.selectOne(any())).thenReturn(statement);
        when(clearingService.compareAndUpdateStage(anyLong(),
                eq(ClearingStages.WAIT_SETTLE), eq(ClearingStages.SETTLED), any()))
                .thenReturn(1);
    }

    private SettClearing clearingWithPremium(long premium, long reversedMerchant,
                                             long reversedCommission, long reversedSubsidy) {
        SettClearing c = new SettClearing();
        c.setId(11L);
        c.setClearingNo("CL2609170001");
        c.setOrderNo("O2026091701");
        c.setMerchantId(777L);
        c.setMerchantReceivableFen(18_130L);
        c.setPlatformCommissionFen(900L);
        c.setTechFeeFen(50L);
        c.setChannelFeeFen(120L);
        c.setMarketingSubsidyFen(1_500L);
        c.setInsurancePremiumFen(premium);
        c.setReversedMerchantFen(reversedMerchant);
        c.setReversedCommissionFen(reversedCommission);
        c.setReversedSubsidyFen(reversedSubsidy);
        return c;
    }

    @Test
    @DisplayName("B11_无退款结算_七笔记账且第7笔保费16全额入平台账户")
    void settle_noRefund_sevenFlowsWithPremiumIncome() {
        when(accountService.flowExists("CL2609170001", FlowChangeTypes.PENDING_TO_AVAILABLE))
                .thenReturn(false);
        SettClearing c = clearingWithPremium(100L, 0L, 0L, 0L);

        var item = executor.settleOne(c, today);

        assertTrue(item.settled());
        // 商户货款两笔（待结算→可提现）
        verify(accountService).creditPending(777L, AccountRole.MERCHANT, "CL2609170001",
                FlowChangeTypes.CLEARING_TO_PENDING, 18_130L, "清算货款入待结算（净额）");
        verify(accountService).pendingToAvailable(777L, AccountRole.MERCHANT, "CL2609170001",
                FlowChangeTypes.PENDING_TO_AVAILABLE, 18_130L, "结算周期到期转可提现（净额）");
        // 平台四笔收入：佣金 / 技服费 / 通道费 / 运费险保费(16)
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.COMMISSION_INCOME, 900L, "平台佣金入账（退款按比例冲正后净额）");
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.TECH_FEE_INCOME, 50L, "技术服务费入账（退款不退）");
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.CHANNEL_FEE_INCOME, 120L, "支付通道费入账（退款不退）");
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.INSURANCE_PREMIUM_INCOME, 100L, "运费险保费入账（不退）");
        // 营销补贴出资
        verify(accountService).debitAvailable(0L, AccountRole.MARKETING, "CL2609170001",
                FlowChangeTypes.MARKETING_SUBSIDY_OUT, 1_500L, "营销补贴出账（退款冲正后净额）");
        verify(statementMapper).updateById(any());
    }

    @Test
    @DisplayName("B11_部分退款后净额结算_货款佣金补贴按净额但保费100分仍全额留平台")
    void settle_afterPartialRefund_premiumNotReversed() {
        when(accountService.flowExists("CL2609170001", FlowChangeTypes.PENDING_TO_AVAILABLE))
                .thenReturn(false);
        // 已冲正：商户货款 4_000、佣金 200、补贴 300（保费无任何 reversed 字段可冲减）
        SettClearing c = clearingWithPremium(100L, 4_000L, 200L, 300L);

        executor.settleOne(c, today);

        // 净额：18130-4000=14130；佣金 900-200=700；补贴 1500-300=1200
        verify(accountService).creditPending(777L, AccountRole.MERCHANT, "CL2609170001",
                FlowChangeTypes.CLEARING_TO_PENDING, 14_130L, "清算货款入待结算（净额）");
        verify(accountService).pendingToAvailable(777L, AccountRole.MERCHANT, "CL2609170001",
                FlowChangeTypes.PENDING_TO_AVAILABLE, 14_130L, "结算周期到期转可提现（净额）");
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.COMMISSION_INCOME, 700L, "平台佣金入账（退款按比例冲正后净额）");
        verify(accountService).debitAvailable(0L, AccountRole.MARKETING, "CL2609170001",
                FlowChangeTypes.MARKETING_SUBSIDY_OUT, 1_200L, "营销补贴出账（退款冲正后净额）");
        // 关键断言：保费不随 reversed* 冲减，原始 100 分全额留平台
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.INSURANCE_PREMIUM_INCOME, 100L, "运费险保费入账（不退）");
        // 不存在任何保费冲正流水类型
        verify(accountService, never()).debitAvailable(anyLong(), anyInt(), anyString(),
                eq(FlowChangeTypes.INSURANCE_PREMIUM_INCOME), anyLong(), anyString());
    }

    @Test
    @DisplayName("B11_无保单结算_保费16入账金额为0(保费字段默认0不影响既有六笔)")
    void settle_noPremium_premiumEntryZero() {
        when(accountService.flowExists("CL2609170001", FlowChangeTypes.PENDING_TO_AVAILABLE))
                .thenReturn(false);
        SettClearing c = clearingWithPremium(0L, 0L, 0L, 0L);

        executor.settleOne(c, today);

        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.INSURANCE_PREMIUM_INCOME, 0L, "运费险保费入账（不退）");
        // 既有六笔金额不变
        verify(accountService).creditPending(777L, AccountRole.MERCHANT, "CL2609170001",
                FlowChangeTypes.CLEARING_TO_PENDING, 18_130L, "清算货款入待结算（净额）");
        verify(accountService).creditAvailable(0L, AccountRole.PLATFORM, "CL2609170001",
                FlowChangeTypes.COMMISSION_INCOME, 900L, "平台佣金入账（退款按比例冲正后净额）");
    }
}
