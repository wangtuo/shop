package com.shop.settlement.clearing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.api.settlement.enums.ClearingStages;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.entity.SettClearingReverse;
import com.shop.settlement.clearing.enums.ReverseStatuses;
import com.shop.settlement.clearing.event.RefundShortfallEvent;
import com.shop.settlement.clearing.mapper.ClearingMapper;
import com.shop.settlement.clearing.mapper.ClearingReverseMapper;
import com.shop.settlement.deposit.service.DepositDeductResult;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.engine.RefundCalculator;
import com.shop.settlement.engine.RefundReverseResult;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.mq.service.MqConsumeService;
import com.shop.settlement.support.SettleNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 退款清算冲正（design 7.5），消费 REFUND_SUCCESS：
 * <ol>
 *   <li>按退款占订单实付比例：平台佣金按比例回退、营销补贴回营销账户；技服费/通道费不退；</li>
 *   <li>商户承担部分按瀑布 <b>待结算 → 可提现余额 → 保证金</b> 顺序扣减（P1-10）：
 *       分布式锁 lock:merchant:fund:{id} 降低并发竞争，每档均为 {@code FOR UPDATE} 行锁 +
 *       {@code LEAST} 单条原子 SQL（P1-12），返回实际扣减值后继续下一档，不重不漏；</li>
 *   <li>冲正明细落 t_sett_clearing_reverse（refundNo 唯一幂等）；
 *       部分退款清算单保持可结算状态并累计冲正，全额退款 stage→40；</li>
 *   <li>三档合计仍不足时：扣尽三档余额、明细置 status=2 记录 shortfall、同事务登记
 *       REFUND_SHORTFALL outbox 告警，消息正常 ACK（P1-10：不无限重试卡死，挂起追讨为后续项）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClearingReverseService {

    public static final String CG_REFUND = "cg_sett_refund";

    private final ClearingMapper clearingMapper;
    private final ClearingReverseMapper reverseMapper;
    private final MqConsumeService mqConsumeService;
    private final RefundCalculator refundCalculator;
    private final AccountService accountService;
    private final DepositService depositService;
    private final DistributedLockTemplate lockTemplate;
    private final SettleNoGenerator noGenerator;
    // P1-1/P1-10：缺口告警事件与冲正扣款、明细落库同事务登记 outbox
    private final OutboxPublisher outboxPublisher;

    @Transactional
    public void onRefundSucceeded(RefundSucceededEvent event) {
        if (!mqConsumeService.tryRecord(event.getEventId(), MqTopics.REFUND_SUCCESS,
                CG_REFUND, event.getRefundNo())) {
            log.info("REFUND_SUCCESS 重复消费直接ACK eventId={} refundNo={}", event.getEventId(), event.getRefundNo());
            return;
        }
        SettClearingReverse existed = reverseMapper.selectOne(new LambdaQueryWrapper<SettClearingReverse>()
                .eq(SettClearingReverse::getRefundNo, event.getRefundNo()));
        if (existed != null) {
            log.info("冲正明细已存在，幂等跳过 refundNo={}", event.getRefundNo());
            return;
        }
        SettClearing clearing = clearingMapper.selectOne(new LambdaQueryWrapper<SettClearing>()
                .eq(SettClearing::getOrderNo, event.getOrderNo()));
        if (clearing == null) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "清算单尚未登记，等待重试: " + event.getOrderNo());
        }

        lockTemplate.execute("lock:merchant:fund:" + clearing.getMerchantId(), () ->
                doReverse(event, clearing));
    }

    private void doReverse(RefundSucceededEvent event, SettClearing clearing) {
        // B11：pay_amount_fen 含不退的运费险保费，退款比例分母与累计退款上限必须剔除保费，
        // 否则部分退款比例被稀释、且累计退款校验会错误地留出保费"可退额度"。
        // RefundSucceededEvent.amountFen 本身不含保费（售后/支付侧保证）。
        long orderPay = nz(clearing.getPayAmountFen());
        long insurancePremium = nz(clearing.getInsurancePremiumFen());
        RefundReverseResult calc = refundCalculator.calculate(
                event.getAmountFen(), orderPay, insurancePremium,
                nz(clearing.getMerchantReceivableFen()),
                nz(clearing.getPlatformCommissionFen()),
                nz(clearing.getMarketingSubsidyFen()),
                event.getRefundType() == null ? 0 : event.getRefundType(),
                nz(clearing.getRefundedFen()));

        long merchantId = clearing.getMerchantId();

        // 1) 平台佣金按比例回退（平台收入账户出账，退款前未结算也由后续日终批按净额入账自动轧平）
        accountService.debitAvailable(0L, AccountRole.PLATFORM, event.getRefundNo(),
                FlowChangeTypes.COMMISSION_REVERSE, calc.getCommissionReverseFen(),
                "退款冲正平台佣金");
        // 2) 营销补贴按比例退回营销账户
        accountService.creditAvailable(0L, AccountRole.MARKETING, event.getRefundNo(),
                FlowChangeTypes.SUBSIDY_REVERSE, calc.getSubsidyReverseFen(),
                "退款冲正营销补贴回营销账户");

        // 3) 商户承担部分瀑布（P1-10）：待结算 → 可提现余额 → 保证金。
        //    每档 FOR UPDATE 行锁 + LEAST 原子 SQL（P1-12），返回实际扣减值，未扣够才进下一档。
        long merchantPart = calc.getMerchantPartFen();
        accountService.getOrCreate(merchantId, AccountRole.MERCHANT);
        String refundNo = event.getRefundNo();

        long fromPending = accountService.debitPendingPartial(merchantId, AccountRole.MERCHANT,
                refundNo, FlowChangeTypes.REFUND_FROM_PENDING, merchantPart,
                "退款冲正扣商户待结算");
        long remainAfterPending = merchantPart - fromPending;

        long fromAvailable = 0L;
        if (remainAfterPending > 0) {
            fromAvailable = accountService.debitAvailablePartial(merchantId, AccountRole.MERCHANT,
                    refundNo, FlowChangeTypes.REFUND_FROM_AVAILABLE, remainAfterPending,
                    "退款冲正扣商户可提现余额");
        }
        long remainAfterAvailable = remainAfterPending - fromAvailable;

        long fromDeposit = 0L;
        if (remainAfterAvailable > 0) {
            DepositDeductResult depositResult =
                    depositService.deductPartialForRefund(merchantId, remainAfterAvailable, refundNo);
            fromDeposit = depositResult.getActualFen();
            if (fromDeposit > 0) {
                accountService.writeZeroFlow(merchantId, AccountRole.MERCHANT, refundNo,
                        FlowChangeTypes.REFUND_FROM_DEPOSIT,
                        "退款冲正不足部分扣保证金 " + fromDeposit + "分");
            }
        }
        long shortfall = remainAfterAvailable - fromDeposit;

        // 4) 落冲正明细（refundNo 唯一）。三档扣尽仍不足：status=2 挂起追讨，消息正常 ACK
        String reverseNo = noGenerator.nextReverseNo();
        SettClearingReverse reverse = new SettClearingReverse();
        reverse.setReverseNo(reverseNo);
        reverse.setRefundNo(refundNo);
        reverse.setOrderNo(event.getOrderNo());
        reverse.setClearingNo(clearing.getClearingNo());
        reverse.setMerchantId(merchantId);
        reverse.setRefundType(event.getRefundType());
        reverse.setRefundFen(event.getAmountFen());
        reverse.setRefundRatioBps(calc.getRefundRatioBps());
        reverse.setReverseMerchantFen(merchantPart);
        reverse.setReverseCommissionFen(calc.getCommissionReverseFen());
        reverse.setReverseSubsidyFen(calc.getSubsidyReverseFen());
        reverse.setFromPendingFen(fromPending);
        reverse.setFromAvailableFen(fromAvailable);
        reverse.setFromDepositFen(fromDeposit);
        reverse.setShortfallFen(shortfall);
        reverse.setStatus(shortfall > 0
                ? ReverseStatuses.PARTIAL_SUSPENDED : ReverseStatuses.FULLY_DEDUCTED);
        reverse.setFullReversed(calc.isFullRefund() ? 1 : 0);
        reverseMapper.insert(reverse);

        // 5) 累计冲正金额；全额 stage→40，部分保持当前阶段（20/30）由日终批按净额结算
        LambdaUpdateWrapper<SettClearing> w = new LambdaUpdateWrapper<SettClearing>()
                .eq(SettClearing::getId, clearing.getId())
                .setSql("reversed_merchant_fen = reversed_merchant_fen + " + merchantPart)
                .setSql("reversed_commission_fen = reversed_commission_fen + "
                        + calc.getCommissionReverseFen())
                .setSql("reversed_subsidy_fen = reversed_subsidy_fen + " + calc.getSubsidyReverseFen())
                .setSql("refunded_fen = refunded_fen + " + event.getAmountFen());
        if (calc.isFullRefund()) {
            w.in(SettClearing::getStage,
                    ClearingStages.WAIT_CLEAR, ClearingStages.WAIT_SETTLE, ClearingStages.SETTLED)
                    .set(SettClearing::getStage, ClearingStages.REVERSED);
        }
        int rows = clearingMapper.update(null, w);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "清算单冲正并发冲突: " + refundNo);
        }

        if (shortfall > 0) {
            // 三档合计仍不足：不再抛 DEPOSIT_NOT_ENOUGH 无限重试。
            // 挂起记录（status=2 + shortfall_fen）与告警事件均已随本事务落库/登记，
            // 后续追讨（后续入账/补缴时补扣）为独立任务，见 FIXES_E.md 残留风险。
            log.error("退款冲正三档扣尽仍不足，缺口挂起 refundNo={} clearingNo={} merchantId={} "
                            + "merchantPart={} 待结算={} 可提现={} 保证金={} 缺口={}",
                    refundNo, clearing.getClearingNo(), merchantId, merchantPart,
                    fromPending, fromAvailable, fromDeposit, shortfall);
            publishShortfall(event, clearing, reverseNo, merchantPart,
                    fromPending, fromAvailable, fromDeposit, shortfall);
        }

        log.info("退款清算冲正完成 refundNo={} clearingNo={} 比例={}bps 待结算={} 可提现={} 保证金={} "
                        + "缺口={} 全额={}",
                refundNo, clearing.getClearingNo(), calc.getRefundRatioBps(),
                fromPending, fromAvailable, fromDeposit, shortfall, calc.isFullRefund());
    }

    /** P1-10 挂起缺口告警：必须与冲正明细同事务（本方法处于 onRefundSucceeded 事务内）。 */
    private void publishShortfall(RefundSucceededEvent event, SettClearing clearing, String reverseNo,
                                  long merchantPart, long fromPending, long fromAvailable,
                                  long fromDeposit, long shortfall) {
        RefundShortfallEvent payload = RefundShortfallEvent.builder()
                .merchantId(clearing.getMerchantId())
                .orderNo(event.getOrderNo())
                .refundNo(event.getRefundNo())
                .reverseNo(reverseNo)
                .merchantPartFen(merchantPart)
                .fromPendingFen(fromPending)
                .fromAvailableFen(fromAvailable)
                .fromDepositFen(fromDeposit)
                .shortfallFen(shortfall)
                .build();
        payload.setBizNo(event.getRefundNo());
        outboxPublisher.publish(MqTopics.REFUND_SHORTFALL, null, payload, event.getRefundNo());
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
