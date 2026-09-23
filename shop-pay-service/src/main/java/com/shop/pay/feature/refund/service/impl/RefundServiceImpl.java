package com.shop.pay.feature.refund.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.enums.RefundStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.feign.FeignResults;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.web.LoginUser;
import com.shop.pay.channel.ChannelLimits;
import com.shop.pay.channel.ChannelRefundQueryRequest;
import com.shop.pay.channel.ChannelRefundQueryResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.entity.NotifyLog;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.NotifyLogMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.payment.dto.RefundNotifyParams;
import com.shop.pay.feature.payment.support.SignVerifier;
import com.shop.pay.feature.refund.entity.RefundOrder;
import com.shop.pay.feature.refund.entity.RefundSplit;
import com.shop.pay.feature.refund.mapper.RefundMapper;
import com.shop.pay.feature.refund.mapper.RefundSplitMapper;
import com.shop.pay.feature.refund.service.RefundService;
import com.shop.pay.feature.refund.support.RefundConvergeService;
import com.shop.pay.feature.refund.support.RefundOrchestrator;
import com.shop.pay.feature.refund.support.RefundSplitter;
import com.shop.pay.feature.refund.support.SplitOutcome;
import com.shop.pay.support.PayAssembler;
import com.shop.pay.support.PayNoGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 退款领域服务实现（design 6.4）。
 *
 * <p>P2-5：{@link #refund}/{@link #retry} 入口不再持有方法级大事务，只保留入口校验/分布式锁/幂等，
 * 三段式事务边界全部在 {@link RefundOrchestrator}（TX1 落 20 提交 → 事务外渠道/余额动作 →
 * TX3 {@link RefundConvergeService} 漏斗 CAS 终态）。</p>
 *
 * <p>B8：{@link #handleRefundNotify} 受理渠道退款回调，{@link #scanProcessingRefunds} 由
 * RefundQueryJob 定时补偿查询；两路与同步段三共用唯一收敛漏斗。</p>
 */
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundServiceImpl.class);

    /** 新建退款单首次主动查询前的宽限（秒），避免与同步路径竞争 */
    static final int QUERY_CREATE_GRACE_SECONDS = 60;
    /** PROCESSING 退款单主动查询最小间隔（秒） */
    static final int QUERY_INTERVAL_SECONDS = 30;

    private final RefundMapper refundMapper;
    private final RefundSplitMapper refundSplitMapper;
    private final PaymentMapper paymentMapper;
    private final ChannelFlowMapper channelFlowMapper;
    private final NotifyLogMapper notifyLogMapper;
    private final RefundSplitter refundSplitter;
    private final PayNoGenerator payNoGenerator;
    private final DistributedLockTemplate lockTemplate;
    private final OrderClient orderClient;
    private final SignVerifier signVerifier;
    private final ChannelRouter channelRouter;
    private final RefundOrchestrator orchestrator;
    private final RefundConvergeService convergeService;

    public static final int USER_TYPE_PLATFORM = 2;

    // ------------------------------------------------------------------
    // 退款入口（锁 + 幂等 + 三段式编排，入口自身无事务）
    // ------------------------------------------------------------------

    @Override
    public RefundDTO refund(CreateRefundCommand command) {
        return lockTemplate.execute("pay:lock:refund:" + command.getOrderNo(), () -> {
            // refundNo 幂等
            if (command.getRefundNo() != null && !command.getRefundNo().isBlank()) {
                RefundOrder existed = findByRefundNo(command.getRefundNo());
                if (existed != null) {
                    return PayAssembler.toRefundDTO(existed);
                }
            }
            Payment payment = requirePaymentByOrderNo(command.getOrderNo());
            validateRefundable(payment, command.getAmountFen());

            List<ChannelFlow> flows = channelFlowMapper.selectByPayNo(payment.getPayNo());
            List<Long> amounts = refundSplitter.split(command.getAmountFen(), flows);
            RefundOrder refund = buildRefundOrder(command.getRefundNo(), payment, command.getAmountFen(),
                    command.getPayMethod(), command.getRefundType(), command.getSource(),
                    command.getOperatorType(), command.getAftersaleNo(), command.getReason(), command.getUserId());

            // TX1（落 20 提交）→ 段二（事务外外部动作）→ TX3（漏斗 CAS 终态）
            RefundOrder done = orchestrator.runNew(refund, flows, amounts);
            return PayAssembler.toRefundDTO(done);
        });
    }

    @Override
    public RefundDTO retry(String refundNo) {
        RefundOrder refund = requireRefund(refundNo);
        if (refund.getStatus() != RefundStatuses.FAIL.getCode()) {
            throw new BizException(ErrorCode.CONFLICT, "仅失败退款单可重试");
        }
        Payment payment = requirePaymentByPayNo(refund.getPayNo());
        List<ChannelFlow> flows = channelFlowMapper.selectByPayNo(payment.getPayNo());
        // TX1 reopen 40→10→20 提交 → 段二续做未成功 split → TX3 收敛
        RefundOrder done = orchestrator.runRetry(refund, flows);
        return PayAssembler.toRefundDTO(done);
    }

    @Override
    public RefundDTO getByRefundNoForViewer(String refundNo, LoginUser viewer) {
        RefundOrder refund = requireRefund(refundNo);
        if (viewer == null || viewer.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        // 平台运营放行
        if (viewer.getUserType() != null && viewer.getUserType() == USER_TYPE_PLATFORM) {
            return PayAssembler.toRefundDTO(refund);
        }
        // 商户：按退款单关联订单的 merchantId 归属校验（订单任一分明细属于该店即放行）
        if (viewer.getMerchantId() != null) {
            OrderDTO order = FeignResults.unwrap(orderClient.getByOrderNo(refund.getOrderNo()));
            boolean owner = order != null && order.getItems() != null && order.getItems().stream()
                    .anyMatch(i -> viewer.getMerchantId().equals(i.getMerchantId()));
            if (!owner) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权查看该退款单");
            }
            return PayAssembler.toRefundDTO(refund);
        }
        // C 端消费者：仅退款单买家本人可查自己的退款进度（design 6.3「退款中-查看进度」/8.2）
        if (viewer.getUserId().equals(refund.getUserId())) {
            return PayAssembler.toRefundDTO(refund);
        }
        // 其他消费者无权查看他人退款单
        throw new BizException(ErrorCode.FORBIDDEN, "无权查看该退款单");
    }

    // ------------------------------------------------------------------
    // B8：渠道退款异步回调受理（终态只进收敛漏斗）
    // ------------------------------------------------------------------

    @Override
    public RefundDTO handleRefundNotify(RefundNotifyParams params) {
        if (params.getNotifyType() == null) {
            params.setNotifyType(2);
        }
        // 1) 验签（与支付回调同套 HMAC/密钥提供方，字段集不同）：失败写 notify_log 后 60002 拒绝
        try {
            signVerifier.verifyRefund(params);
        } catch (BizException e) {
            recordSignFailedRefundNotify(params, e.getMessage());
            throw e;
        }

        // 2) t_pay_notify_log UK(channel_code,notify_id) INSERT 幂等（notify_type=2、refund_no 落列）
        boolean first = recordRefundNotifyReceived(params);
        if (!first) {
            NotifyLog existed = findNotify(params.getChannelCode(), params.getNotifyId());
            if (existed != null && existed.getHandleStatus() == 1) {
                // 同通知重放：已成功受理，幂等 ACK 当前单，绝不重复收敛/发事件
                return PayAssembler.toRefundDTO(requireRefund(params.getRefundNo()));
            }
            if (existed != null && existed.getHandleStatus() == 2) {
                throw new BizException(ErrorCode.PAY_SIGN_ERROR,
                        "退款回调验签失败 notifyId=" + params.getNotifyId());
            }
        }

        // 3) 退款单存在性 / 金额校验
        RefundOrder refund = findByRefundNo(params.getRefundNo());
        if (refund == null) {
            markNotifyFail(params, "退款单不存在");
            throw new BizException(ErrorCode.NOT_FOUND, "退款单不存在");
        }
        if (params.getAmountFen() == null || !params.getAmountFen().equals(refund.getAmountFen())) {
            markNotifyFail(params, "回调金额与退款单金额不符");
            throw new BizException(ErrorCode.PAY_ERROR,
                    "回调金额与退款单金额不符: callback=" + params.getAmountFen()
                            + " refund=" + refund.getAmountFen());
        }

        // 4) 仅做状态受理：为仍在 20 的非余额 split 构造结果，终态统一进收敛漏斗 CAS
        List<RefundSplit> splits = refundSplitMapper.selectByRefundNo(refund.getRefundNo());
        List<SplitOutcome> outcomes = new ArrayList<>(splits.size());
        boolean success = "SUCCESS".equalsIgnoreCase(params.getStatus());
        for (RefundSplit split : splits) {
            if (split.getStatus() == null || split.getStatus() != RefundStatuses.PROCESSING.getCode()) {
                continue;
            }
            if (ChannelLimits.isBalance(split.getChannelCode())) {
                // 余额退款由同步段二入账终结，回调不替余额 split 写成功（避免未入账却置 30）
                continue;
            }
            if (success) {
                String channelRefundNo = params.getChannelRefundNo() != null
                        ? params.getChannelRefundNo() : split.getChannelRefundNo();
                outcomes.add(SplitOutcome.success(split.getId(), channelRefundNo));
            } else {
                outcomes.add(SplitOutcome.fail(split.getId(),
                        "渠道回调退款失败: " + params.getChannelCode()));
            }
        }
        RefundConvergeService.ConvergeResult result = convergeService.converge(refund.getId(),
                RefundConvergeService.Trigger.NOTIFY, outcomes,
                success ? null : "渠道回调退款失败: " + params.getChannelCode());
        if (result == RefundConvergeService.ConvergeResult.ILLEGAL_STATE) {
            // 已冲正等非法迁移：不推进状态，登记失败原因但仍对渠道 ACK（避免渠道无限重发），交对账/运营
            markNotifyFail(params, "退款单状态不允许该回调");
        } else {
            markNotifyDone(params);
        }
        return PayAssembler.toRefundDTO(requireRefund(refund.getRefundNo()));
    }

    // ------------------------------------------------------------------
    // B8：主动查询补偿（RefundQueryJob 驱动，无事务；渠道调用内零 DB 写）
    // ------------------------------------------------------------------

    @Override
    public int scanProcessingRefunds(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<RefundOrder> pending = refundMapper.selectProcessingForQuery(
                now.minusSeconds(QUERY_CREATE_GRACE_SECONDS),
                now.minusSeconds(QUERY_INTERVAL_SECONDS),
                limit);
        int handled = 0;
        for (RefundOrder refund : pending) {
            try {
                // touchQuery CAS 落败（他节点/他轮次占位）返回 null，不计入本轮处理数且不调渠道
                if (convergeFromQuery(refund.getId()) != null) {
                    handled++;
                }
            } catch (Exception e) {
                // 单条失败不影响本轮其他单；渠道查询失败下轮重试（ShedLock 保证单节点扫描）
                log.error("[退款查询补偿] 退款单查询收敛失败 refundNo={}", refund.getRefundNo(), e);
            }
        }
        return handled;
    }

    /**
     * 对单笔 PROCESSING(20) 退款单主动查询渠道并收敛。全程无事务：
     * touchQuery CAS 占位（防多节点重复查）→ 事务外逐渠道 split queryRefund → 进同一收敛漏斗。
     * 余额退款不查渠道（由同步路径终结）。
     */
    public RefundConvergeService.ConvergeResult convergeFromQuery(Long refundId) {
        LocalDateTime now = LocalDateTime.now();
        if (refundMapper.touchQuery(refundId, now.minusSeconds(QUERY_INTERVAL_SECONDS), now) == 0) {
            // 他节点/他轮次已占位或单已不在 20：不重复查询
            return null;
        }
        RefundOrder refund = refundMapper.selectById(refundId);
        if (refund == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "退款单不存在: id=" + refundId);
        }
        List<ChannelFlow> flows = channelFlowMapper.selectByPayNo(refund.getPayNo());
        List<RefundSplit> splits = refundSplitMapper.selectByRefundNo(refund.getRefundNo());
        List<SplitOutcome> outcomes = new ArrayList<>(splits.size());
        String firstFailReason = null;
        for (int i = 0; i < splits.size(); i++) {
            RefundSplit split = splits.get(i);
            if (split.getStatus() == null || split.getStatus() != RefundStatuses.PROCESSING.getCode()) {
                continue;
            }
            if (ChannelLimits.isBalance(split.getChannelCode())) {
                continue;
            }
            ChannelFlow flow = matchFlow(flows, split);
            SplitOutcome outcome = queryOneFromChannel(refund, flow, split, i);
            outcomes.add(outcome);
            if (outcome.getState() == SplitOutcome.State.FAIL && firstFailReason == null) {
                firstFailReason = outcome.getFailReason();
            }
        }
        return convergeService.converge(refundId, RefundConvergeService.Trigger.QUERY,
                outcomes, firstFailReason);
    }

    private SplitOutcome queryOneFromChannel(RefundOrder refund, ChannelFlow flow,
                                             RefundSplit split, int index) {
        try {
            ChannelRefundQueryResult result = channelRouter.queryRefund(ChannelRefundQueryRequest.builder()
                    .channelCode(flow.getChannelCode())
                    .channelOrderNo(flow.getChannelOrderNo())
                    .channelTxnNo(flow.getChannelTransactionNo())
                    .channelRefundNo(split.getChannelRefundNo())
                    .refundNo(refund.getRefundNo())
                    .requestNo(refund.getRefundNo() + "-" + index)
                    .amountFen(split.getAmountFen())
                    .build());
            if (result.success()) {
                String channelRefundNo = result.getChannelRefundNo() != null
                        ? result.getChannelRefundNo() : split.getChannelRefundNo();
                return SplitOutcome.success(split.getId(), channelRefundNo);
            }
            if (result.failed()) {
                return SplitOutcome.fail(split.getId(),
                        "渠道查询退款失败: " + flow.getChannelCode() + " " + result.getFailReason());
            }
            // 受理中：保持 20
            return SplitOutcome.pending(split.getId());
        } catch (Exception e) {
            // 查询本身失败：下轮重试/告警，绝不当失败置 40
            log.warn("[退款查询补偿] 渠道查询异常（下轮重试）refundNo={} channel={}",
                    refund.getRefundNo(), flow.getChannelCode(), e);
            return SplitOutcome.pending(split.getId());
        }
    }

    // ------------------------------------------------------------------
    // 退款回调流水
    // ------------------------------------------------------------------

    private boolean recordRefundNotifyReceived(RefundNotifyParams params) {
        NotifyLog logRow = new NotifyLog();
        logRow.setChannelCode(params.getChannelCode());
        logRow.setNotifyId(params.getNotifyId());
        logRow.setRefundNo(params.getRefundNo());
        logRow.setNotifyType(2);
        logRow.setSignStatus(1);
        logRow.setHandleStatus(0);
        logRow.setNotifyBody(params.getSign());
        return notifyLogMapper.insertIgnore(logRow) > 0;
    }

    private void recordSignFailedRefundNotify(RefundNotifyParams params, String reason) {
        NotifyLog logRow = new NotifyLog();
        logRow.setChannelCode(params.getChannelCode());
        logRow.setNotifyId(params.getNotifyId());
        logRow.setRefundNo(params.getRefundNo());
        logRow.setNotifyType(2);
        logRow.setSignStatus(2);
        logRow.setHandleStatus(2);
        logRow.setNotifyBody(params.getSign());
        logRow.setFailReason(reason);
        notifyLogMapper.insertIgnore(logRow);
    }

    private void markNotifyDone(RefundNotifyParams params) {
        NotifyLog logRow = findNotify(params.getChannelCode(), params.getNotifyId());
        if (logRow != null) {
            notifyLogMapper.updateResult(logRow.getId(), 1, 1, null);
        }
    }

    private void markNotifyFail(RefundNotifyParams params, String reason) {
        NotifyLog logRow = findNotify(params.getChannelCode(), params.getNotifyId());
        if (logRow != null) {
            notifyLogMapper.updateResult(logRow.getId(), 1, 3, reason);
        }
    }

    private NotifyLog findNotify(String channelCode, String notifyId) {
        return notifyLogMapper.selectOne(new LambdaQueryWrapper<NotifyLog>()
                .eq(NotifyLog::getChannelCode, channelCode)
                .eq(NotifyLog::getNotifyId, notifyId));
    }

    // ------------------------------------------------------------------

    private void validateRefundable(Payment payment, long amountFen) {
        if (amountFen <= 0) {
            throw new BizException(ErrorCode.REFUND_AMOUNT_ERROR, "退款金额必须大于0");
        }
        int status = payment.getStatus();
        if (status != PayStatuses.SUCCESS.getCode() && status != PayStatuses.REFUNDING.getCode()) {
            throw new BizException(ErrorCode.PAY_ERROR, "支付单状态不允许退款: " + status);
        }
        long refundable = payment.getAmountFen() - nz(payment.getRefundedFen());
        if (amountFen > refundable) {
            throw new BizException(ErrorCode.REFUND_AMOUNT_ERROR,
                    "退款金额超出可退金额: 申请=" + amountFen + " 可退=" + refundable);
        }
    }

    private RefundOrder buildRefundOrder(String refundNo, Payment payment, long amountFen,
                                         Integer payMethod, Integer refundType, Integer source,
                                         Integer operatorType, String aftersaleNo,
                                         String reason, Long userId) {
        RefundOrder refund = new RefundOrder();
        refund.setRefundNo(refundNo != null && !refundNo.isBlank() ? refundNo : payNoGenerator.refundNo());
        refund.setPayNo(payment.getPayNo());
        refund.setOrderNo(payment.getOrderNo());
        refund.setAftersaleNo(aftersaleNo);
        refund.setUserId(userId != null ? userId : payment.getUserId());
        refund.setAmountFen(amountFen);
        refund.setPayMethod(payMethod != null ? payMethod : payment.getPayMethod());
        refund.setRefundType(refundType);
        refund.setSource(source);
        refund.setOperatorType(operatorType);
        refund.setStatus(RefundStatuses.WAIT.getCode());
        refund.setReason(reason);
        refund.setRetryCount(0);
        return refund;
    }

    private ChannelFlow matchFlow(List<ChannelFlow> flows, RefundSplit split) {
        return flows.stream()
                .filter(f -> f.getChannelCode().equals(split.getChannelCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR,
                        "退款明细找不到原渠道流水: " + split.getChannelCode()));
    }

    /**
     * 按订单号定位可退原支付单（P2-5 多次支付尝试防护）：
     * 只认成功系（30/60/70）的活跃行（active_slot=0）。历史 FAIL/CLOSED 尝试行已入墓碑槽位，
     * SQL 显式过滤，防止用户重新支付场景下退款串到死单；后续 addRefundedFen/markRefunded
     * 全部按该成功行 payNo 落账。retry 路径按 payNo 直达，不经此方法。
     */
    private Payment requirePaymentByOrderNo(String orderNo) {
        Payment payment = paymentMapper.selectRefundableByOrderNo(orderNo);
        if (payment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "订单无支付单");
        }
        return payment;
    }

    private Payment requirePaymentByPayNo(String payNo) {
        Payment payment = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPayNo, payNo));
        if (payment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "支付单不存在");
        }
        return payment;
    }

    private RefundOrder requireRefund(String refundNo) {
        RefundOrder refund = findByRefundNo(refundNo);
        if (refund == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "退款单不存在");
        }
        return refund;
    }

    private RefundOrder findByRefundNo(String refundNo) {
        return refundMapper.selectOne(new LambdaQueryWrapper<RefundOrder>()
                .eq(RefundOrder::getRefundNo, refundNo));
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }
}
