package com.shop.pay.feature.refund.support;

import com.shop.api.pay.enums.RefundStatuses;
import com.shop.api.user.dto.AmountCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.feign.FeignResults;
import com.shop.framework.tx.TransactionalTemplate;
import com.shop.pay.channel.ChannelLimits;
import com.shop.pay.channel.ChannelRefundRequest;
import com.shop.pay.channel.ChannelRefundResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.api.user.client.UserClient;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.refund.entity.RefundOrder;
import com.shop.pay.feature.refund.entity.RefundSplit;
import com.shop.pay.feature.refund.mapper.RefundMapper;
import com.shop.pay.feature.refund.mapper.RefundSplitMapper;
import com.shop.pay.feature.refund.statemachine.RefundStateMachine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 退款三段式编排器（P2-5）：跨 Bean 承载事务边界，避免 {@code @Transactional} 自调用绕过代理。
 *
 * <pre>
 * TX1（TransactionalTemplate 独立短事务，提交点）：
 *   新单 insert 退款单(10)/splits(10) → 状态机校验 10→20 → markProcessing CAS、splits 批量 10/40→20 → COMMIT
 *   （retry：40→10 reopen 再 10→20，已成功 split=30 不动）
 * 段二（本方法内，无任何 DB 事务）：
 *   逐 split 调渠道 channelRouter.refund（幂等号 refundNo-index）/ 余额 userClient.creditBalance(bizNo=refundNo)；
 *   异常不回滚（单已落 20）——渠道异常按"结果不确定"留 20 等回调/查询补偿，绝不伪失败
 * TX3（RefundConvergeService.converge，REQUIRES_NEW 独立短事务）：
 *   split/退款单 CAS 终态，CAS 获胜方发 REFUND_SUCCESS outbox
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class RefundOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RefundOrchestrator.class);

    private final TransactionalTemplate transactionalTemplate;
    private final RefundConvergeService convergeService;
    private final RefundMapper refundMapper;
    private final RefundSplitMapper refundSplitMapper;
    private final ChannelRouter channelRouter;
    private final UserClient userClient;
    private final RefundStateMachine stateMachine;

    /**
     * 新退款单：TX1 落单并置 PROCESSING(20) 提交 → 段二事务外执行 → TX3 收敛。
     *
     * @param refund  已组装、尚未落库的退款单（status 会被置 10）
     * @param flows   原支付渠道流水
     * @param amounts 与 flows 等长的 split 金额（RefundSplitter 最大余数法结果）
     * @return 收敛后的退款单最新状态
     */
    public RefundOrder runNew(RefundOrder refund, List<ChannelFlow> flows, List<Long> amounts) {
        refund.setStatus(RefundStatuses.WAIT.getCode());
        List<RefundSplit> splits = transactionalTemplate.execute(() -> {
            // ---- TX1：落单 + PROCESSING(20)，本短事务内零外部调用 ----
            refundMapper.insert(refund);
            List<RefundSplit> created = new ArrayList<>(flows.size());
            for (int i = 0; i < flows.size(); i++) {
                ChannelFlow flow = flows.get(i);
                RefundSplit split = new RefundSplit();
                split.setRefundNo(refund.getRefundNo());
                split.setPayNo(refund.getPayNo());
                split.setPayMethod(flow.getPayMethod());
                split.setChannelCode(flow.getChannelCode());
                split.setAmountFen(amounts.get(i));
                split.setStatus(RefundStatuses.WAIT.getCode());
                refundSplitMapper.insert(split);
                created.add(split);
            }
            stateMachine.assertTransition(RefundStatuses.WAIT.getCode(), RefundStatuses.PROCESSING.getCode());
            if (refundMapper.markProcessing(refund.getRefundNo()) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "退款单置退款中失败: " + refund.getRefundNo());
            }
            if (refundSplitMapper.markProcessingByRefundNo(refund.getRefundNo()) != flows.size()) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "退款明细置退款中数量不符: " + refund.getRefundNo());
            }
            return created;
        });
        // ---- TX1 已提交：此后任何异常都不回滚退款单（外部世界可见 20）----
        return runExternalAndConverge(refund, flows, splits);
    }

    /**
     * 失败单重试：TX1 内 reopen 40→10→20（失败 split 40→20，成功 split=30 保留）提交，
     * 段二只重做未成功 split，成功 split 不重复打款（余额 bizNo 幂等 + 渠道 refundNo-index 幂等）。
     */
    public RefundOrder runRetry(RefundOrder failed, List<ChannelFlow> flows) {
        stateMachine.assertTransition(RefundStatuses.FAIL.getCode(), RefundStatuses.WAIT.getCode());
        transactionalTemplate.executeWithoutResult(() -> {
            // ---- TX1：reopen + PROCESSING(20) ----
            if (refundMapper.reopen(failed.getRefundNo()) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "退款单重试并发冲突: " + failed.getRefundNo());
            }
            stateMachine.assertTransition(RefundStatuses.WAIT.getCode(), RefundStatuses.PROCESSING.getCode());
            if (refundMapper.markProcessing(failed.getRefundNo()) == 0) {
                throw new BizException(ErrorCode.CONFLICT, "退款单置退款中失败: " + failed.getRefundNo());
            }
            // 仅失败(40)/待退(10) split 回到 20；已成功(30) split 不动，不重复打款
            refundSplitMapper.markProcessingByRefundNo(failed.getRefundNo());
        });
        RefundOrder reloaded = refundMapper.selectById(failed.getId());
        List<RefundSplit> splits = refundSplitMapper.selectByRefundNo(failed.getRefundNo());
        return runExternalAndConverge(reloaded, flows, splits);
    }

    /**
     * 段二 + 段三：无事务执行外部动作，再由唯一收敛漏斗 CAS 终态。
     * 包级可见便于补偿路径复用前置校验；调用方须保证退款单已在 20。
     */
    private RefundOrder runExternalAndConverge(RefundOrder refund, List<ChannelFlow> flows,
                                               List<RefundSplit> splits) {
        // 段二红线断言：外部调用点不允许存在活动事务（P2-5 单测同样断言）
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("退款段二外部动作不允许在事务内执行: " + refund.getRefundNo());
        }
        List<SplitOutcome> outcomes = new ArrayList<>(splits.size());
        String firstFailReason = null;
        for (int i = 0; i < splits.size(); i++) {
            RefundSplit split = splits.get(i);
            // retry 场景：已成功 split 跳过外部动作（结果以 DB 30 为准，converge 也不会重放）
            if (split.getStatus() != null && split.getStatus() == RefundStatuses.SUCCESS.getCode()) {
                continue;
            }
            ChannelFlow flow = matchFlow(flows, split);
            SplitOutcome outcome = invokeExternal(refund, flow, split, i);
            outcomes.add(outcome);
            if (outcome.getState() == SplitOutcome.State.FAIL && firstFailReason == null) {
                firstFailReason = outcome.getFailReason();
            }
        }

        // ---- 段三：独立短事务 CAS 终态（同步路径与回调/查询在同一漏斗仲裁）----
        convergeService.converge(refund.getId(), RefundConvergeService.Trigger.SYNC,
                outcomes, firstFailReason);
        return refundMapper.selectById(refund.getId());
    }

    /**
     * 段二单个 split 的事务外外部动作。
     * 渠道返回受理中(10)/抛异常（超时等结果不确定场景）→ PENDING，split 留 20；
     * 渠道明确失败(30) → FAIL；余额 BizException（业务拒绝，如账户异常）→ FAIL，
     * 其余异常（基础设施不确定）→ PENDING。
     */
    private SplitOutcome invokeExternal(RefundOrder refund, ChannelFlow flow, RefundSplit split, int index) {
        if (ChannelLimits.isBalance(flow.getChannelCode())) {
            try {
                FeignResults.unwrap(userClient.creditBalance(AmountCommand.builder()
                        .userId(refund.getUserId())
                        // bizNo=退款单号：一个退款单至多一条余额 split，用户域按 bizNo 幂等，重试不重复入账
                        .bizNo(refund.getRefundNo())
                        .amountFen(split.getAmountFen())
                        .remark("退款入余额 " + refund.getOrderNo())
                        .build()));
                return SplitOutcome.success(split.getId(), "BALANCE_CREDIT_" + refund.getRefundNo());
            } catch (BizException e) {
                log.warn("[退款段二] 余额入账业务失败 refundNo={} splitId={} reason={}",
                        refund.getRefundNo(), split.getId(), e.getMessage());
                return SplitOutcome.fail(split.getId(), "余额入账失败: " + e.getMessage());
            } catch (Exception e) {
                log.warn("[退款段二] 余额入账异常（结果不确定，保持处理中）refundNo={} splitId={}",
                        refund.getRefundNo(), split.getId(), e);
                return SplitOutcome.pending(split.getId());
            }
        }
        try {
            ChannelRefundResult result = channelRouter.route(flow.getChannelCode())
                    .refund(ChannelRefundRequest.builder()
                            .channelCode(flow.getChannelCode())
                            .channelOrderNo(flow.getChannelOrderNo())
                            .channelTxnNo(flow.getChannelTransactionNo())
                            // 渠道幂等号 refundNo-index：重试/段二重放安全，渠道侧不重复退款
                            .refundNo(refund.getRefundNo() + "-" + index)
                            .amountFen(split.getAmountFen())
                            .reason(refund.getReason())
                            .build());
            if (result.success()) {
                return SplitOutcome.success(split.getId(), result.getChannelRefundNo());
            }
            if (result.getStatus() == 10) {
                return SplitOutcome.pending(split.getId());
            }
            return SplitOutcome.fail(split.getId(),
                    "渠道退款失败: " + flow.getChannelCode() + " " + result.getFailReason());
        } catch (Exception e) {
            // 超时/连接错误等：渠道侧可能已退款成功，严禁当失败（杜绝长短款）；保持 20 等回调/查询追平
            log.warn("[退款段二] 渠道退款调用异常（结果不确定，保持处理中）refundNo={} channel={}",
                    refund.getRefundNo(), flow.getChannelCode(), e);
            return SplitOutcome.pending(split.getId());
        }
    }

    private ChannelFlow matchFlow(List<ChannelFlow> flows, RefundSplit split) {
        return flows.stream()
                .filter(f -> f.getChannelCode().equals(split.getChannelCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR,
                        "退款明细找不到原渠道流水: " + split.getChannelCode()));
    }
}
