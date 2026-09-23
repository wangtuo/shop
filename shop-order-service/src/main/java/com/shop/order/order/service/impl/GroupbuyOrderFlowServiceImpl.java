package com.shop.order.order.service.impl;

import com.shop.api.marketing.enums.GroupbuyOpType;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.order.dto.GroupFailedCommand;
import com.shop.api.order.dto.GroupPayRenewCommand;
import com.shop.api.order.dto.GroupSucceedCommand;
import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.enums.RefundSources;
import com.shop.api.pay.enums.RefundTypes;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.order.mq.message.OrderDelayMessage;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.entity.Order;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.order.service.GroupbuyOrderFlowService;
import com.shop.order.order.service.OrderOperateService;
import com.shop.order.policy.PayTimeoutPolicy;
import com.shop.order.support.FeignResults;
import com.shop.order.support.OrderDelayTopics;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 拼团订单流转实现（B1）。
 *
 * <p>双幂等：listener 入口已有 t_order_mq_consume 的 eventId 闸门；此处再以
 * {@code gbflow:groupNo#type} 业务键落同一幂等表做第二道闸门——无论生产端是「每团员一事件」
 * 还是「一团一事件」、无论 MQ 还是 Feign 先到，成团/失败的批量处理每团每类型只执行一次，
 * 失败整体回滚后由 broker/调用方重试。
 */
@Service
@RequiredArgsConstructor
public class GroupbuyOrderFlowServiceImpl implements GroupbuyOrderFlowService {

    private static final Logger log = LoggerFactory.getLogger(GroupbuyOrderFlowServiceImpl.class);

    /** 业务幂等键前缀（与 eventId 共唯一列）：gbflow:{groupNo}:{type} */
    private static final String BIZ_GATE_PREFIX = "gbflow:";
    /** 拼团失败自动退款单号前缀：GB:{orderNo}，资金侧按 refundNo 幂等。 */
    public static final String GROUP_REFUND_NO_PREFIX = "GB:";
    /** 拼团失败自动退款原因。 */
    private static final String GROUP_FAIL_REFUND_REASON = "拼团失败自动退款";
    /**
     * R4-25：成团续期重投的支付超时延时行 bizKey 后缀（与下单首发行区分，规避 outbox
     * uk(topic,tag,biz_key) 同三元组冲突）；同时使续期消息的合成 eventId 区别于首发消息。
     */
    private static final String RENEW_BIZ_SUFFIX = "#gbrenew";

    private final OrderMapper orderMapper;
    private final MqConsumeService consumeService;
    private final OrderOperateService operateService;
    private final PayClient payClient;
    private final PayTimeoutPolicy payTimeoutPolicy;
    private final OutboxPublisher outboxPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onGroupSuccess(String groupNo) {
        requireGroupNo(groupNo);
        if (!businessFirstTime(groupNo, GroupbuyOpType.SUCCESS.getCode())) {
            return;
        }
        renewUnpaidDeadlines(groupNo, payTimeoutPolicy.groupSuccessExtendSeconds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onGroupFail(String groupNo) {
        requireGroupNo(groupNo);
        if (!businessFirstTime(groupNo, GroupbuyOpType.FAIL.getCode())) {
            return;
        }
        handleFail(groupNo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renewGroupPayDeadline(GroupPayRenewCommand command) {
        Long plus = command.getPlusSeconds();
        long plusSeconds = (plus == null || plus <= 0)
                ? payTimeoutPolicy.groupSuccessExtendSeconds() : plus;
        // 与 MQ SUCCESS 同一业务闸门、同一 CAS：逐单 Feign 与批量事件谁先到都只处理一次
        if (!businessFirstTime(command.getGroupNo(), GroupbuyOpType.SUCCESS.getCode())) {
            return;
        }
        renewUnpaidDeadlines(command.getGroupNo(), plusSeconds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSucceeded(GroupSucceedCommand command) {
        // 成团语义：未付款单续期待其支付后自然转 20；已付款单经支付回调本就是 20，不动。
        if (!businessFirstTime(command.getGroupNo(), GroupbuyOpType.SUCCESS.getCode())) {
            return;
        }
        renewUnpaidDeadlines(command.getGroupNo(), payTimeoutPolicy.groupSuccessExtendSeconds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(GroupFailedCommand command) {
        if (!businessFirstTime(command.getGroupNo(), GroupbuyOpType.FAIL.getCode())) {
            return;
        }
        handleFail(command.getGroupNo());
    }

    // ------------------------------------------------------------------
    // SUCCESS：待付款单 CAS 续期 + 重投支付超时延时
    // ------------------------------------------------------------------

    private void renewUnpaidDeadlines(String groupNo, long plusSeconds) {
        List<Order> groupOrders = orderMapper.selectByGroupNo(groupNo);
        if (groupOrders == null || groupOrders.isEmpty()) {
            log.info("拼团成团续期无订单可处理，跳过 groupNo={}", groupNo);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusSeconds(plusSeconds);
        // 与 UPDATE 谓词一致的待续期快照：status=10 且当前 expire_time 早于新 deadline（只宽不窄）
        List<Order> toExtend = groupOrders.stream()
                .filter(o -> o.getStatus() != null && o.getStatus() == OrderStatuses.WAIT_PAY)
                .filter(o -> o.getExpireTime() != null && o.getExpireTime().isBefore(deadline))
                .toList();
        if (toExtend.isEmpty()) {
            log.info("拼团成团续期无待付款单需要续期 groupNo={} deadline={}", groupNo, deadline);
            return;
        }
        int rows = orderMapper.batchUpdateExpireForUnpaid(groupNo, deadline);
        log.info("拼团成团续期 groupNo={} deadline={} candidates={} updated={}",
                groupNo, deadline, toExtend.size(), rows);
        // 按续期成功的快照逐单重投 ORDER_PAY_TIMEOUT（30min 档）；期间已支付的单子延时到达时
        // PayTimeoutListener 以库内 status/expire_time 为准，status=20 或未到新截止点直接忽略。
        // R4-25：续期行 bizKey 必须与下单首发行（OrderPersister 以 orderNo 登记）区分——
        // outbox uk(topic,tag,biz_key) 行投递后永不删除，同三元组重插会抛 DuplicateKeyException
        // 回滚整个成团事务（expire 续期/成团流水一并回滚，事件重试 16 次进 DLQ）。
        for (Order order : toExtend) {
            OrderDelayMessage message = OrderDelayMessage.builder()
                    .orderNo(order.getOrderNo())
                    .build();
            message.setBizNo(order.getOrderNo());
            outboxPublisher.publishDelay(OrderDelayTopics.ORDER_PAY_TIMEOUT, null,
                    message, order.getOrderNo() + RENEW_BIZ_SUFFIX, plusSeconds);
        }
    }

    // ------------------------------------------------------------------
    // FAIL：未付关单（cancel_type=4）；已付每单一次退款（refundNo 幂等）
    // ------------------------------------------------------------------

    private void handleFail(String groupNo) {
        List<Order> groupOrders = orderMapper.selectByGroupNo(groupNo);
        if (groupOrders == null || groupOrders.isEmpty()) {
            log.info("拼团失败处理无订单可处理，跳过 groupNo={}", groupNo);
            return;
        }
        for (Order order : groupOrders) {
            Integer status = order.getStatus();
            if (status == null) {
                continue;
            }
            if (status == OrderStatuses.WAIT_PAY) {
                // 与超时取消同路径：markCancelled(10→50,cancel_type=4) + ORDER_CANCELLED outbox +
                // OrderResourceReleaser 既有资源释放；支付域成功态拦截防错关
                operateService.groupFailCancel(order.getOrderNo());
            } else if (status == OrderStatuses.WAIT_SHIP) {
                refundPaidOrder(order);
            }
            // 其余状态（50 已取消 / 70 已关闭等）跳过
        }
    }

    private void refundPaidOrder(Order order) {
        long amountFen = order.getPayFen() == null ? 0L : order.getPayFen();
        if (amountFen <= 0L) {
            log.warn("拼团失败已支付单实付金额异常，跳过退款请对账人工处理 orderNo={} payFen={}",
                    order.getOrderNo(), order.getPayFen());
            return;
        }
        String refundNo = groupRefundNo(order.getOrderNo());
        CreateRefundCommand command = CreateRefundCommand.builder()
                .refundNo(refundNo)
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .amountFen(amountFen)
                .payMethod(order.getPayMethod())
                .refundType(RefundTypes.FULL.getCode())
                .source(RefundSources.CLEARING.getCode())
                .operatorType(2)
                .reason(GROUP_FAIL_REFUND_REASON)
                .build();
        // 失败直接抛出：同事务回滚（业务闸门/关单/outbox 均不生效），MQ 重试或 Feign 调用方重试；
        // refundNo 确定性，资金侧幂等，重复调用不会产生第二次资金退动。禁止伪成功。
        FeignResults.unwrap(payClient.refund(command));
        log.info("拼团失败已支付单退款已发起 orderNo={} refundNo={} amountFen={}",
                order.getOrderNo(), refundNo, amountFen);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 业务键第二道幂等闸门：{@code gbflow:groupNo:type}，与 listener 的 eventId 闸门共用
     * t_order_mq_consume 唯一列。必须在业务事务内调用，回滚后重试可再次竞争。
     *
     * @return true 首次拿到该团该类型的处理权；false 已被其它事件/Feign 通路处理
     */
    private boolean businessFirstTime(String groupNo, int type) {
        return consumeService.firstTime(BIZ_GATE_PREFIX + groupNo + ":" + type,
                MqTopics.GROUPBUY_EVENT, groupNo);
    }

    private static void requireGroupNo(String groupNo) {
        if (groupNo == null || groupNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "groupNo 不能为空");
        }
    }

    /** 拼团失败退款单号：GB:{orderNo}，确定性、全平台唯一（订单号唯一）。 */
    public static String groupRefundNo(String orderNo) {
        return GROUP_REFUND_NO_PREFIX + orderNo;
    }
}
