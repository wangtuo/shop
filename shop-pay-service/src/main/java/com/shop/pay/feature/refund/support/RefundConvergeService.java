package com.shop.pay.feature.refund.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.pay.enums.RefundStatuses;
import com.shop.api.pay.enums.RefundTypes;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
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
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 退款终态唯一收敛漏斗（B8 + P2-5）：同步三段式段三（{@link Trigger#SYNC}）、
 * 渠道异步回调（{@link Trigger#NOTIFY}）、RefundQueryJob 主动查询（{@link Trigger#QUERY}）
 * 三路上报终态全部只经本服务 CAS，禁止三条路径各写一套状态更新。
 *
 * <p>本方法以 REQUIRES_NEW 独立短事务执行（只允许 DB 操作 + outbox 登记，绝无 Feign/HTTP）：
 * 退款单 {@code markSuccess IN(10,20)→30} CAS 行数仲裁，仅 CAS 获胜方执行
 * split 终态回写、支付单 addRefundedFen/markRefunded、渠道流水 addPaidFen 与
 * REFUND_SUCCESS outbox 同事务发出——REFUND_SUCCESS 全局只发一次。</p>
 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired(required = false))
public class RefundConvergeService {

    private static final Logger log = LoggerFactory.getLogger(RefundConvergeService.class);

    /** O6：退款结果指标（命名冻结），标签 result/operator_type（统一用户类型码 0用户/1商户/2平台）。 */
    private static final String REFUND_TOTAL = "shop_refund_total";

    private final RefundMapper refundMapper;
    private final RefundSplitMapper refundSplitMapper;
    private final PaymentMapper paymentMapper;
    private final ChannelFlowMapper channelFlowMapper;
    private final RefundStateMachine stateMachine;
    private final OutboxPublisher outboxPublisher;
    // O6：无注册表环境（部分单测）为空，埋点空转安全
    private final MeterRegistry meterRegistry;

    /** 终态上报来源（仅用于日志/排查，仲裁逻辑与来源无关）。 */
    public enum Trigger {
        /** P2-5 同步三段式段三 */
        SYNC,
        /** B8 渠道退款异步回调 */
        NOTIFY,
        /** B8 RefundQueryJob 主动查询补偿 */
        QUERY
    }

    /** 收敛结果。 */
    public enum ConvergeResult {
        /** 本次 CAS 获胜把退款单推进到 30（outbox 已同事务登记） */
        ADVANCED_SUCCESS,
        /** 退款单已在 30（他路先收敛），零副作用 */
        ALREADY_SUCCESS,
        /** 仍有 split 受理中，退款单保持 20 等下轮回调/查询 */
        STILL_PROCESSING,
        /** 本次 CAS 把退款单置为 40（或本就 40） */
        MARKED_FAIL,
        /** 当前状态不允许目标迁移（如 50 已冲正） */
        ILLEGAL_STATE
    }

    /**
     * 终态收敛（REQUIRES_NEW 短事务）。
     *
     * @param refundId   退款单 ID
     * @param trigger    上报来源
     * @param outcomes   本次拿到的 split 结果（只处理 DB 中仍为 20 的 split；null 表示无新结果，仅做状态复查）
     * @param failReason 失败留痕文案（成功/处理中路径忽略）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ConvergeResult converge(Long refundId, Trigger trigger,
                                  List<SplitOutcome> outcomes, String failReason) {
        RefundOrder refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "退款单不存在: id=" + refundId);
        }
        int status = refund.getStatus();
        if (status == RefundStatuses.SUCCESS.getCode()) {
            return ConvergeResult.ALREADY_SUCCESS;
        }
        if (status == RefundStatuses.REVERSED.getCode()) {
            log.warn("[退款收敛] 退款单已冲正，拒绝终态上报 refundNo={} trigger={}", refund.getRefundNo(), trigger);
            return ConvergeResult.ILLEGAL_STATE;
        }

        // 1) split 级 CAS（仅 DB 中仍 20 的行可迁移；30 幂等跳过，40 不再动）
        List<RefundSplit> splits = refundSplitMapper.selectByRefundNo(refund.getRefundNo());
        Map<Long, SplitOutcome> outcomeMap = outcomes == null ? Map.of()
                : outcomes.stream().collect(Collectors.toMap(SplitOutcome::getSplitId, Function.identity(),
                        (a, b) -> a));
        boolean hasFail = false;
        String firstFailReason = failReason;
        for (RefundSplit split : splits) {
            if (split.getStatus() == null || split.getStatus() != RefundStatuses.PROCESSING.getCode()) {
                if (split.getStatus() != null && split.getStatus() == RefundStatuses.FAIL.getCode()) {
                    hasFail = true;
                }
                continue;
            }
            SplitOutcome outcome = outcomeMap.get(split.getId());
            if (outcome == null) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            switch (outcome.getState()) {
                case SUCCESS -> {
                    String channelRefundNo = outcome.getChannelRefundNo() != null
                            ? outcome.getChannelRefundNo() : split.getChannelRefundNo();
                    // 20→30 CAS；0 行=他路抢先，读终态后以 DB 为准
                    refundSplitMapper.markSuccess(split.getId(), channelRefundNo, now);
                    split.setStatus(RefundStatuses.SUCCESS.getCode());
                    split.setChannelRefundNo(channelRefundNo);
                }
                case FAIL -> {
                    refundSplitMapper.markFail(split.getId());
                    split.setStatus(RefundStatuses.FAIL.getCode());
                    hasFail = true;
                    if (firstFailReason == null || firstFailReason.isBlank()) {
                        firstFailReason = outcome.getFailReason();
                    }
                }
                case PENDING -> {
                    // 保持 20，不写任何终态
                }
            }
        }

        // 2) 整单态聚合（以重读 DB 后的 split 状态为准）
        List<RefundSplit> latest = refundSplitMapper.selectByRefundNo(refund.getRefundNo());
        boolean anyFail = latest.stream().anyMatch(s -> s.getStatus() != null
                && s.getStatus() == RefundStatuses.FAIL.getCode()) || hasFail;
        boolean allSuccess = latest.stream().allMatch(s -> s.getStatus() != null
                && s.getStatus() == RefundStatuses.SUCCESS.getCode());

        if (anyFail) {
            return markOrderFail(refund, firstFailReason, trigger);
        }
        if (allSuccess) {
            return markOrderSuccess(refund, latest, trigger);
        }
        log.info("[退款收敛] 退款单保持处理中 refundNo={} trigger={}", refund.getRefundNo(), trigger);
        return ConvergeResult.STILL_PROCESSING;
    }

    private ConvergeResult markOrderSuccess(RefundOrder refund, List<RefundSplit> splits, Trigger trigger) {
        stateMachine.assertTransition(refund.getStatus(), RefundStatuses.SUCCESS.getCode());
        LocalDateTime now = LocalDateTime.now();
        // 退款单级 CAS：10/20 → 30，并发三路上报只有一方拿到 1 行
        int rows = refundMapper.markSuccess(refund.getRefundNo(), now);
        if (rows == 0) {
            RefundOrder reloaded = refundMapper.selectById(refund.getId());
            if (reloaded != null && reloaded.getStatus() == RefundStatuses.SUCCESS.getCode()) {
                // CAS 落败：他路已收敛，outbox 由获胜方负责，零副作用返回
                return ConvergeResult.ALREADY_SUCCESS;
            }
            throw new BizException(ErrorCode.CONFLICT, "退款单状态并发冲突: " + refund.getRefundNo());
        }

        // CAS 获胜方：支付单累计退款（防透支 CAS）+ 渠道流水累计已退 + 支付单状态
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPayNo, refund.getPayNo()));
        if (paymentMapper.addRefundedFen(refund.getPayNo(), refund.getAmountFen()) == 0) {
            // 同一 REQUIRES_NEW 事务：抛错回滚 markSuccess，退款单保持 20，交运营/对账处理
            throw new BizException(ErrorCode.REFUND_AMOUNT_ERROR, "累计退款超过实付金额");
        }
        List<ChannelFlow> flows = channelFlowMapper.selectByPayNo(refund.getPayNo());
        for (RefundSplit split : splits) {
            ChannelFlow flow = matchFlow(flows, split);
            if (channelFlowMapper.addPaidFen(flow.getId(), split.getAmountFen()) == 0) {
                throw new BizException(ErrorCode.REFUND_AMOUNT_ERROR,
                        "渠道流水可退金额不足: " + flow.getChannelCode());
            }
        }
        long refundedTotal = nz(payment.getRefundedFen()) + refund.getAmountFen();
        boolean full = refund.getRefundType() != null
                && (refund.getRefundType() == RefundTypes.FULL.getCode()
                || refundedTotal >= payment.getAmountFen());
        if (full) {
            paymentMapper.markRefunded(refund.getPayNo());
        } else {
            paymentMapper.enterRefunding(refund.getPayNo());
        }

        publishRefundSucceeded(refund, now);
        // O6：CAS 获胜首次落定成功才计数（ALREADY_SUCCESS 幂等不重复计）
        recordRefundResult(refund, "success");
        log.info("[退款收敛] 退款单收敛成功 refundNo={} trigger={}", refund.getRefundNo(), trigger);
        return ConvergeResult.ADVANCED_SUCCESS;
    }

    private ConvergeResult markOrderFail(RefundOrder refund, String failReason, Trigger trigger) {
        if (refund.getStatus() == RefundStatuses.FAIL.getCode()) {
            return ConvergeResult.MARKED_FAIL;
        }
        stateMachine.assertTransition(refund.getStatus(), RefundStatuses.FAIL.getCode());
        String reason = failReason != null && !failReason.isBlank() ? failReason : "渠道退款失败";
        refundMapper.markFail(refund.getRefundNo(), reason, 1);
        // O6：真正 20→40 落定失败才计数（进入本方法时已 40 的幂等路径不重复计）
        recordRefundResult(refund, "fail");
        log.info("[退款收敛] 退款单收敛失败 refundNo={} trigger={} reason={}",
                refund.getRefundNo(), trigger, reason);
        return ConvergeResult.MARKED_FAIL;
    }

    private void publishRefundSucceeded(RefundOrder refund, LocalDateTime refundTime) {
        RefundSucceededEvent event = RefundSucceededEvent.builder()
                .refundNo(refund.getRefundNo())
                .payNo(refund.getPayNo())
                .orderNo(refund.getOrderNo())
                .aftersaleNo(refund.getAftersaleNo())
                .userId(refund.getUserId())
                .amountFen(refund.getAmountFen())
                .payMethod(refund.getPayMethod())
                .refundType(refund.getRefundType())
                .refundTime(refundTime)
                .build();
        event.setBizNo(refund.getRefundNo());
        // 仅 markSuccess CAS 获胜方可达此处，与退款单/支付单终态同事务，relay 至少一次 + 消费端 UK 幂等
        outboxPublisher.publish(MqTopics.REFUND_SUCCESS, "refund", event, refund.getRefundNo());
    }

    private ChannelFlow matchFlow(List<ChannelFlow> flows, RefundSplit split) {
        return flows.stream()
                .filter(f -> f.getChannelCode().equals(split.getChannelCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR,
                        "退款明细找不到原渠道流水: " + split.getChannelCode()));
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** O6：退款发起/审批最终结果计数；operator_type 取退款单既有字段（统一用户类型码），无界 ID 不入标签。 */
    private void recordRefundResult(RefundOrder refund, String result) {
        if (meterRegistry == null) {
            return;
        }
        String operatorType = refund.getOperatorType() == null
                ? "unknown" : String.valueOf(refund.getOperatorType());
        meterRegistry.counter(REFUND_TOTAL, "result", result, "operator_type", operatorType).increment();
    }
}
