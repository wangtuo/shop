package com.shop.pay.feature.recon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.shop.pay.feature.recon.enums.ReconStatuses;
import com.shop.pay.feature.recon.mapper.ReconDiffMapper;
import com.shop.pay.feature.recon.service.ReconDiffHandler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 差错逐笔处置实现（P2-4）：单条差异 = 一个 REQUIRES_NEW 短事务（仅 DB + outbox，规约 3）。
 */
@Service
@RequiredArgsConstructor
public class ReconDiffHandlerImpl implements ReconDiffHandler {

    private static final Logger log = LoggerFactory.getLogger(ReconDiffHandlerImpl.class);

    private final PaymentMapper paymentMapper;
    private final ChannelFlowMapper channelFlowMapper;
    private final ReconDiffMapper diffMapper;
    private final OutboxPublisher outboxPublisher;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public DiffHandleResult handleOne(ReconDiff diff) {
        return handleOne(diff, null);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public DiffHandleResult handleOne(ReconDiff diff, ChannelQueryResult shortQuery) {
        int type = diff.getDiffType();
        if (type == ReconcileDiffTypes.LONG.getCode()) {
            return handleLong(diff);
        }
        if (type == ReconcileDiffTypes.SHORT.getCode()) {
            return handleShort(diff, shortQuery);
        }
        return handleMismatch(diff);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markFailure(ReconDiff diff, Exception error) {
        String reason = error == null ? "未知异常" : error.getClass().getSimpleName()
                + ": " + String.valueOf(error.getMessage());
        String remark = "处置失败可重试 " + truncate(reason);
        // retry_count+1 随本独立事务提交；差异维持原 10/20 态，等待 ReconRetryJob 下轮收敛，不伪平账
        diffMapper.incrRetry(diff.getId(), nz(diff.getStatus()), remark);
        log.warn("对账差错处置失败留痕 diffId={} type={} err={}", diff.getId(), diff.getDiffType(), reason);
    }

    /** 长款：渠道有本地无。能匹配待支付单则补单（回调补登），否则人工挂账补发货。 */
    private DiffHandleResult handleLong(ReconDiff diff) {
        Payment payment = diff.getPayNo() == null ? null : paymentMapper.selectOne(
                new LambdaQueryWrapper<Payment>().eq(Payment::getPayNo, diff.getPayNo()));
        if (payment != null
                && (payment.getStatus() == PayStatuses.WAIT.getCode()
                || payment.getStatus() == PayStatuses.PAYING.getCode())) {
            int rows;
            try {
                rows = paymentMapper.markSuccess(payment.getPayNo(), diff.getChannelTxnNo(),
                        "RECON_LONG_" + diff.getBatchNo(), LocalDateTime.now());
            } catch (DuplicateKeyException e) {
                // 并发补单：唯一键冲突落败方，视为赢家已处置，跳过且不向上抛
                return skipped(diff, "并发补单唯一键冲突，赢家已处置");
            }
            if (rows == 0) {
                // 并发：CAS 落败（已被其他补偿流程/回调抢先补单），赢家负责落终态+发事件，本笔跳过
                return skipped(diff, "补单 CAS rows=0，并发赢家已处置");
            }
            for (ChannelFlow flow : channelFlowMapper.selectByPayNo(payment.getPayNo())) {
                if (flow.getFlowStatus() == PayStatuses.WAIT.getCode()
                        || flow.getFlowStatus() == PayStatuses.PAYING.getCode()) {
                    channelFlowMapper.markSuccess(flow.getId(), diff.getChannelTxnNo(), LocalDateTime.now());
                }
            }
            // 补单成功同样发 ORDER_PAID，驱动订单/营销/清算等下游幂等消费
            Payment latest = paymentMapper.selectById(payment.getId());
            PaymentSucceededEvent event = PaymentSucceededEvent.builder()
                    .payNo(latest.getPayNo())
                    .orderNo(latest.getOrderNo())
                    .userId(latest.getUserId())
                    .payMethod(latest.getPayMethod())
                    .amountFen(latest.getAmountFen())
                    .channelTransactionNo(diff.getChannelTxnNo())
                    .paidTime(latest.getPayTime())
                    .build();
            event.setBizNo(latest.getPayNo());
            // P1-1：补单事件随差错处理事务登记 outbox（rows>0 的唯一获胜分支），relay 投递，下游按 payNo 幂等
            outboxPublisher.publish(MqTopics.ORDER_PAID, "recon", event, latest.getPayNo());
            diffMapper.updateStatus(diff.getId(), nz(diff.getStatus()), ReconStatuses.Diff.HANDLED,
                    "SUPPLEMENT_ORDER", "长款核实补单成功", LocalDateTime.now());
            return new DiffHandleResult(diff.getId(), DiffHandleResult.Outcome.HANDLED, "长款补单成功");
        }
        diffMapper.updateStatus(diff.getId(), nz(diff.getStatus()), ReconStatuses.Diff.MANUAL,
                "MANUAL", "长款无对应待支付单，转人工核实补单/补发货", LocalDateTime.now());
        return new DiffHandleResult(diff.getId(), DiffHandleResult.Outcome.HANDLED, "长款转人工挂账");
    }

    /**
     * 短款：本地有渠道无。先主动补偿查询（查询在调用方事务外完成，结果入备注），
     * 超过最大重试次数转人工（不擅自关单、不伪平账）。
     */
    private DiffHandleResult handleShort(ReconDiff diff, ChannelQueryResult shortQuery) {
        int retry = nz(diff.getRetryCount());
        int max = diff.getMaxRetry() == null ? ReconcileServiceImpl.DEFAULT_MAX_RETRY : diff.getMaxRetry();
        if (retry < max) {
            String remark = "短款第" + (retry + 1) + "次主动查询渠道补偿中";
            if (shortQuery != null) {
                remark += "，渠道查询=" + shortQuery.getState();
                if (shortQuery.getChannelTxnNo() != null) {
                    remark += "(" + shortQuery.getChannelTxnNo() + ")";
                }
            }
            diffMapper.incrRetry(diff.getId(), nz(diff.getStatus()), truncate(remark));
            // 渠道即便返回 SUCCESS 也不自动平账（账单缺失需账单侧核实），保持 10/20 待后续轮次收敛/转人工
            return new DiffHandleResult(diff.getId(), DiffHandleResult.Outcome.HANDLED, remark);
        }
        diffMapper.updateStatus(diff.getId(), nz(diff.getStatus()), ReconStatuses.Diff.MANUAL,
                "MANUAL", "短款超过最大补偿次数，需人工核实未支付后关单", LocalDateTime.now());
        return new DiffHandleResult(diff.getId(), DiffHandleResult.Outcome.HANDLED, "短款超最大次数转人工");
    }

    /** 金额不符：以渠道为准调账挂账。 */
    private DiffHandleResult handleMismatch(ReconDiff diff) {
        if (diff.getPayNo() != null
                && paymentMapper.adjustAmount(diff.getPayNo(), nz(diff.getChannelAmountFen())) > 0) {
            diffMapper.updateStatus(diff.getId(), nz(diff.getStatus()), ReconStatuses.Diff.HANDLED,
                    "ADJUST_AMOUNT",
                    "金额以渠道为准调账 " + nz(diff.getLocalAmountFen()) + "→" + nz(diff.getChannelAmountFen()),
                    LocalDateTime.now());
            return new DiffHandleResult(diff.getId(), DiffHandleResult.Outcome.HANDLED, "金额不符调账成功");
        }
        diffMapper.updateStatus(diff.getId(), nz(diff.getStatus()), ReconStatuses.Diff.MANUAL,
                "MANUAL", "金额不符调账失败，转人工挂账", LocalDateTime.now());
        return new DiffHandleResult(diff.getId(), DiffHandleResult.Outcome.HANDLED, "金额不符转人工挂账");
    }

    private DiffHandleResult skipped(ReconDiff diff, String remark) {
        log.info("对账差异并发跳过 diffId={} {}", diff.getId(), remark);
        return new DiffHandleResult(diff.getId(), DiffHandleResult.Outcome.SKIPPED, remark);
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
