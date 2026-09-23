package com.shop.settlement.statement.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.ClearingStages;
import com.shop.api.settlement.event.SettlementCompletedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.service.ClearingService;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.statement.entity.SettStatement;
import com.shop.settlement.statement.mapper.StatementMapper;
import com.shop.settlement.support.SettleNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 单笔清算单结算执行器（P1-4）：<b>独立 Spring Bean + public 方法 + @Transactional</b>，
 * 日终批 {@link StatementSettleService#runDailySettle} 逐单注入调用本 Bean（走 Spring 代理），
 * 每笔清算单的阶段推进、六笔账户记账、结算单累计、CLEARING_SETTLE 事件登记在一个独立事务内；
 * 单笔抛错只回滚该笔，不影响同批其他清算单。
 *
 * <p>事件经 outbox 与余额变更同事务登记（P1-1），按清算单粒度发布，bizKey=clearingNo。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettleClearingExecutor {

    private final ClearingService clearingService;
    private final StatementMapper statementMapper;
    private final MerchantService merchantService;
    private final AccountService accountService;
    private final SettleNoGenerator noGenerator;
    private final OutboxPublisher outboxPublisher;

    /** 单笔结算结果。 */
    public record SettleItem(boolean settled, long clearingId, String clearingNo, long merchantId,
                             String statementNo, long amountFen) {

        static SettleItem skipped(SettClearing c) {
            return new SettleItem(false, c.getId(), c.getClearingNo(), c.getMerchantId(), null, 0L);
        }
    }

    /**
     * 结算单笔清算单（调用方逐单调用，本方法是独立事务边界；禁止在同类中自调用，否则事务失效）。
     */
    @Transactional
    public SettleItem settleOne(SettClearing clearing, LocalDate today) {
        // 双保险：流水已存在说明此前已记账（例如阶段更新成功后重跑）
        if (accountService.flowExists(clearing.getClearingNo(),
                FlowChangeTypes.PENDING_TO_AVAILABLE)) {
            log.info("清算单已结算流水存在，幂等跳过 clearingNo={}", clearing.getClearingNo());
            return SettleItem.skipped(clearing);
        }
        // 条件推进 20→30，影响 0 行即被并发节点处理
        SettStatement statement = getOrCreateStatement(clearing.getMerchantId(), today);
        int rows = clearingService.compareAndUpdateStage(clearing.getId(),
                ClearingStages.WAIT_SETTLE, ClearingStages.SETTLED, w -> w
                        .set(SettClearing::getSettleTime, LocalDateTime.now())
                        .set(SettClearing::getMerchantStatementId, statement.getId()));
        if (rows == 0) {
            return SettleItem.skipped(clearing);
        }

        // 结算金额取「原始分账 - 已累计冲正」的净额：
        // 部分退款后仍到结算周期的清算单，商户货款/佣金/补贴按净额入账；技服费/通道费不退。
        long receivable = nz(clearing.getMerchantReceivableFen())
                - nz(clearing.getReversedMerchantFen());
        long commissionNet = nz(clearing.getPlatformCommissionFen())
                - nz(clearing.getReversedCommissionFen());
        long subsidyNet = nz(clearing.getMarketingSubsidyFen())
                - nz(clearing.getReversedSubsidyFen());
        // 商户货款：先入待结算再转可提现（留两笔流水，与资金语义一致）
        accountService.creditPending(clearing.getMerchantId(), AccountRole.MERCHANT,
                clearing.getClearingNo(), FlowChangeTypes.CLEARING_TO_PENDING,
                receivable, "清算货款入待结算（净额）");
        accountService.pendingToAvailable(clearing.getMerchantId(), AccountRole.MERCHANT,
                clearing.getClearingNo(), FlowChangeTypes.PENDING_TO_AVAILABLE,
                receivable, "结算周期到期转可提现（净额）");
        // 平台收入：佣金（净） + 技服费（不退） + 通道费（不退）
        accountService.creditAvailable(0L, AccountRole.PLATFORM,
                clearing.getClearingNo(), FlowChangeTypes.COMMISSION_INCOME,
                commissionNet, "平台佣金入账（退款按比例冲正后净额）");
        accountService.creditAvailable(0L, AccountRole.PLATFORM,
                clearing.getClearingNo(), FlowChangeTypes.TECH_FEE_INCOME,
                nz(clearing.getTechFeeFen()), "技术服务费入账（退款不退）");
        accountService.creditAvailable(0L, AccountRole.PLATFORM,
                clearing.getClearingNo(), FlowChangeTypes.CHANNEL_FEE_INCOME,
                nz(clearing.getChannelFeeFen()), "支付通道费入账（退款不退）");
        // 第 7 笔：运费险保费平台保险收入（B11，流水类型 16）。保费随单一次性收取、退款不退，
        // 取清算单原始金额全额入账，不随 reversedMerchant/Commission/Subsidy 冲减。
        accountService.creditAvailable(0L, AccountRole.PLATFORM,
                clearing.getClearingNo(), FlowChangeTypes.INSURANCE_PREMIUM_INCOME,
                nz(clearing.getInsurancePremiumFen()), "运费险保费入账（不退）");
        // 营销补贴出资（按退款冲正后净额）
        accountService.debitAvailable(0L, AccountRole.MARKETING,
                clearing.getClearingNo(), FlowChangeTypes.MARKETING_SUBSIDY_OUT,
                subsidyNet, "营销补贴出账（退款冲正后净额）");

        accumulateStatement(statement, receivable);
        publishSettled(statement, clearing, receivable);

        return new SettleItem(true, clearing.getId(), clearing.getClearingNo(),
                clearing.getMerchantId(), statement.getStatementNo(), receivable);
    }

    /** P1-1：结算事件与上面的余额变更、阶段推进在同一事务内登记 outbox。 */
    private void publishSettled(SettStatement statement, SettClearing clearing, long receivable) {
        if (receivable <= 0) {
            return;
        }
        SettlementCompletedEvent event = SettlementCompletedEvent.builder()
                .statementNo(statement.getStatementNo())
                .merchantId(statement.getMerchantId())
                .amountFen(receivable)
                .settleTime(LocalDateTime.now())
                .build();
        // 按清算单粒度发布（bizKey=clearingNo，消费端可按 bizNo 幂等）；同一结算单多笔事件可按 statementNo 归并
        event.setBizNo(clearing.getClearingNo());
        outboxPublisher.publish(MqTopics.CLEARING_SETTLE, null, event, clearing.getClearingNo());
    }

    private SettStatement getOrCreateStatement(long merchantId, LocalDate periodDate) {
        SettStatement statement = statementMapper.selectOne(new LambdaQueryWrapper<SettStatement>()
                .eq(SettStatement::getMerchantId, merchantId)
                .eq(SettStatement::getPeriodDate, periodDate));
        if (statement != null) {
            return statement;
        }
        SettMerchant merchant = merchantService.requireMerchant(merchantId);
        statement = new SettStatement();
        statement.setStatementNo(noGenerator.nextStatementNo());
        statement.setMerchantId(merchantId);
        statement.setMerchantLevel(merchant.getMerchantLevel());
        statement.setPeriodDate(periodDate);
        statement.setTotalFen(0L);
        statement.setSettledFen(0L);
        statement.setFreezingFen(0L);
        statement.setClearingCount(0);
        statement.setStage(ClearingStages.SETTLED);
        statement.setSettleTime(LocalDateTime.now());
        try {
            statementMapper.insert(statement);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            statement = statementMapper.selectOne(new LambdaQueryWrapper<SettStatement>()
                    .eq(SettStatement::getMerchantId, merchantId)
                    .eq(SettStatement::getPeriodDate, periodDate));
        }
        return statement;
    }

    private void accumulateStatement(SettStatement statement, long receivable) {
        statement.setTotalFen(nz(statement.getTotalFen()) + receivable);
        statement.setSettledFen(nz(statement.getSettledFen()) + receivable);
        statement.setFreezingFen(0L);
        statement.setClearingCount((statement.getClearingCount() == null ? 0 : statement.getClearingCount()) + 1);
        statement.setStage(ClearingStages.SETTLED);
        statement.setSettleTime(LocalDateTime.now());
        statementMapper.updateById(statement);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
