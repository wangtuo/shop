package com.shop.marketing.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.marketing.enums.PresaleOpType;
import com.shop.api.marketing.event.PresaleEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.PresaleOrder;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.PresaleOrderMapper;
import com.shop.marketing.activity.support.ActivityRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预售服务（design.md 4.5）：定金 + 尾款，定金膨胀尾款抵扣，尾款窗口通常 3 天，
 * 超时未付自动取消且定金不退；MQ 延时消息 + 定时扫描双保险。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresaleService {

    /** 默认尾款窗口 3 天 */
    public static final int DEFAULT_FINAL_DAYS = 3;

    /**
     * R4-25：尾款超时【延时行】bizKey 后缀。延时消息到期触发的即时取消事件以裸 orderNo
     * 为 bizKey（见 {@link #sendEvent}），两者必须不同，否则 outbox 复合唯一键冲突。
     */
    public static final String FINAL_TIMEOUT_BIZ_SUFFIX = "#final";

    private final ActivityMapper activityMapper;
    private final PresaleOrderMapper presaleOrderMapper;
    private final OutboxPublisher outboxPublisher;

    /** 定金支付登记（orderNo 幂等），发定金事件 + 尾款超时取消延时消息。 */
    @Transactional(rollbackFor = Exception.class)
    public void register(Long userId, Long activityId, String orderNo) {
        if (presaleOrderMapper.selectOne(new LambdaQueryWrapper<PresaleOrder>()
                .eq(PresaleOrder::getOrderNo, orderNo)) != null) {
            return;
        }
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getStatus() != 1) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "预售活动不存在或已下架");
        }
        ActivityRule rule = JsonUtils.fromJson(activity.getRuleJson(), ActivityRule.class);
        if (rule == null || rule.getDepositFen() == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "预售活动规则不完整");
        }
        int days = rule.getFinalPayDays() == null ? DEFAULT_FINAL_DAYS : rule.getFinalPayDays();
        LocalDateTime finalStart = activity.getEndTime();
        LocalDateTime finalEnd = finalStart.plusDays(days);

        PresaleOrder order = new PresaleOrder();
        order.setActivityId(activityId);
        order.setUserId(userId);
        order.setOrderNo(orderNo);
        order.setDepositFen(rule.getDepositFen());
        order.setInflateDeductFen(rule.getInflateDeductFen() == null ? 0L : rule.getInflateDeductFen());
        order.setFinalPayFen(rule.getFinalPayFen() == null ? 0L : rule.getFinalPayFen());
        order.setFinalStartTime(finalStart);
        order.setFinalEndTime(finalEnd);
        order.setStatus(0);
        presaleOrderMapper.insert(order);

        sendEvent(activityId, userId, orderNo, PresaleOpType.DEPOSIT_PAID.getCode(), finalEnd);
        // 双保险：登记尾款超时取消延时事件（与 PresaleFinalJob 条件更新天然幂等）。
        // P1-1：延时事件随预售单同事务提交/回滚，relay 在 delaySeconds 后投递。
        // R4-25：延时行 bizKey 加 #final 后缀——它在到期消费后会由 cancelByOrderNo/扫表
        // 再发布一笔同 (shop_presale_event, tag=CANCEL, bizKey) 的即时取消事件；outbox
        // uk(topic,tag,biz_key) 行永不删除，不加后缀则定金登记事务（本行）或取消事务
        // （sendEvent）必有一方因 DuplicateKeyException 回滚，预售单无法自动取消。
        long delaySeconds = Math.max(1L, Duration.between(LocalDateTime.now(), finalEnd).getSeconds());
        outboxPublisher.publishDelay(MqTopics.PRESALE_EVENT, String.valueOf(PresaleOpType.CANCEL.getCode()),
                buildEvent(activityId, userId, orderNo, PresaleOpType.CANCEL.getCode(), finalEnd),
                orderNo + FINAL_TIMEOUT_BIZ_SUFFIX, delaySeconds);
    }

    /** 尾款支付成功：定金已付 → 尾款已付（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String orderNo) {
        presaleOrderMapper.markFinalPaid(orderNo);
    }

    /**
     * 尾款单关联定金单（B5）：优先按 parentOrderNo（C23，订单域已封板）直查；
     * parentOrderNo 缺失（order V3 已作废无 parent 列的历史单）时按 activityId + userId + status=0
     * 反查 t_presale_order 兜底——唯一记录即关联，0 条/多条硬失败，不允许猜测关联。
     */
    public PresaleOrder resolveDepositOrder(Long activityId, Long userId, String parentOrderNo) {
        if (parentOrderNo != null && !parentOrderNo.isBlank()) {
            PresaleOrder deposit = presaleOrderMapper.selectOne(new LambdaQueryWrapper<PresaleOrder>()
                    .eq(PresaleOrder::getOrderNo, parentOrderNo)
                    .eq(PresaleOrder::getActivityId, activityId)
                    .eq(PresaleOrder::getUserId, userId));
            if (deposit == null) {
                throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "尾款关联的预售定金单不存在：" + parentOrderNo);
            }
            return deposit;
        }
        List<PresaleOrder> openOrders = presaleOrderMapper.selectList(new LambdaQueryWrapper<PresaleOrder>()
                .eq(PresaleOrder::getActivityId, activityId)
                .eq(PresaleOrder::getUserId, userId)
                .eq(PresaleOrder::getStatus, 0)
                .last("LIMIT 2"));
        if (openOrders.isEmpty()) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "未找到待付尾款的预售定金单");
        }
        if (openOrders.size() > 1) {
            throw new BizException(ErrorCode.CONFLICT, "存在多条待付尾款预售单，尾款单必须显式携带 parentOrderNo");
        }
        return openOrders.get(0);
    }

    /**
     * 尾款营销锁校验（B5）：定金单处于 status=0 且当前时间落在 [finalStartTime, finalEndTime] 窗口内。
     * 定金不退语义由取消链路（{@link #cancelByOrderNo} / {@link #timeoutScan()}）保持，本方法不触发退款；
     * 营销释放对预售维持 no-op（买家原因放弃尾款，定金不退，无券/库存需回补）。
     */
    public void validateFinalStage(PresaleOrder deposit) {
        if (deposit == null || deposit.getStatus() == null || deposit.getStatus() != 0) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "预售定金单状态不可支付尾款");
        }
        LocalDateTime now = LocalDateTime.now();
        if (deposit.getFinalStartTime() != null && now.isBefore(deposit.getFinalStartTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "未进入尾款支付窗口");
        }
        if (deposit.getFinalEndTime() != null && now.isAfter(deposit.getFinalEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "尾款支付窗口已结束");
        }
    }

    /**
     * 尾款超时扫描：窗口内未付 → 已取消（定金不退），发取消事件。
     * 延时消息到达时也可直接调用本方法（按 orderNo 单条）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int timeoutScan() {
        LocalDateTime now = LocalDateTime.now();
        List<PresaleOrder> timeout = presaleOrderMapper.selectList(new LambdaQueryWrapper<PresaleOrder>()
                .eq(PresaleOrder::getStatus, 0)
                .lt(PresaleOrder::getFinalEndTime, now));
        int cancelled = 0;
        for (PresaleOrder order : timeout) {
            if (presaleOrderMapper.markTimeoutByOrderNo(order.getOrderNo(), now) <= 0) {
                continue;
            }
            sendEvent(order.getActivityId(), order.getUserId(), order.getOrderNo(),
                    PresaleOpType.CANCEL.getCode(), order.getFinalEndTime());
            cancelled++;
        }
        return cancelled;
    }

    /** 延时消息驱动：单条取消（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelByOrderNo(String orderNo) {
        PresaleOrder order = presaleOrderMapper.selectOne(new LambdaQueryWrapper<PresaleOrder>()
                .eq(PresaleOrder::getOrderNo, orderNo));
        if (order == null || order.getStatus() != 0 || !LocalDateTime.now().isAfter(order.getFinalEndTime())) {
            return;
        }
        if (presaleOrderMapper.markTimeoutByOrderNo(orderNo, LocalDateTime.now()) > 0) {
            sendEvent(order.getActivityId(), order.getUserId(), orderNo,
                    PresaleOpType.CANCEL.getCode(), order.getFinalEndTime());
        }
    }

    private PresaleEvent buildEvent(Long activityId, Long userId, String orderNo, int type, LocalDateTime deadline) {
        PresaleEvent event = PresaleEvent.builder()
                .activityId(activityId).userId(userId).orderNo(orderNo).type(type)
                .finalPayDeadline(deadline == null ? null : deadline.atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli())
                .build();
        event.setBizNo(orderNo);
        return event;
    }

    private void sendEvent(Long activityId, Long userId, String orderNo, int type, LocalDateTime deadline) {
        // P1-1：事件与预售单状态同事务提交，relay 至少一次投递，消费端按 orderNo 幂等
        outboxPublisher.publish(MqTopics.PRESALE_EVENT, String.valueOf(type),
                buildEvent(activityId, userId, orderNo, type, deadline), orderNo);
    }
}
