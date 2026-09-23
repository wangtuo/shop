package com.shop.aftersale.mq.service.impl;

import com.shop.aftersale.aftersale.entity.AftersaleInsurance;
import com.shop.aftersale.aftersale.entity.AftersaleItem;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.entity.AftersaleWindow;
import com.shop.aftersale.aftersale.entity.PriceProtectRecord;
import com.shop.aftersale.aftersale.enums.AftersaleCodes;
import com.shop.aftersale.aftersale.mapper.AftersaleInsuranceMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleItemMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleRefundMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleWindowMapper;
import com.shop.aftersale.aftersale.mapper.OrderItemRefMapper;
import com.shop.aftersale.aftersale.mapper.PriceProtectRecordMapper;
import com.shop.aftersale.aftersale.mapper.StatusLogMapper;
import com.shop.aftersale.aftersale.entity.StatusLog;
import com.shop.aftersale.mq.mapper.MqConsumeLogMapper;
import com.shop.aftersale.mq.service.AftersaleMqService;
import com.shop.aftersale.support.AftersaleDelayTopics;
import com.shop.aftersale.support.AftersaleEventPublisher;
import com.shop.aftersale.support.AftersalePolicy;
import com.shop.aftersale.support.AftersaleTimeoutMessage;
import com.shop.api.aftersale.enums.AftersaleStatuses;
import com.shop.api.aftersale.enums.AftersaleTypes;
import com.shop.api.aftersale.enums.ResponsibilitySide;
import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.PointsRefundCommand;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.mq.MqConsumeContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 售后域事件消费处理。消费流水插入与业务处理在同一事务，重复 eventId 直接 ACK。
 */
@Service
@RequiredArgsConstructor
public class AftersaleMqServiceImpl implements AftersaleMqService {

    private static final long INSURANCE_DELAY_SECONDS = 72 * 3600L;

    private final MqConsumeLogMapper mqConsumeLogMapper;
    private final AftersaleWindowMapper windowMapper;
    private final AftersaleOrderMapper orderMapper;
    private final AftersaleItemMapper itemMapper;
    private final OrderItemRefMapper itemRefMapper;
    private final AftersaleRefundMapper refundMapper;
    private final AftersaleInsuranceMapper insuranceMapper;
    private final PriceProtectRecordMapper priceProtectMapper;
    private final StatusLogMapper statusLogMapper;
    private final UserClient userClient;
    private final AftersalePolicy policy;
    private final AftersaleEventPublisher eventPublisher;
    // P1-1：运费险 72h 理赔延时事件走 outbox，与售后单完成/理赔单登记同事务
    private final OutboxPublisher outboxPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onOrderShipped(OrderShippedEvent e) {
        if (mqConsumeLogMapper.insertIgnore(resolveEventId(e.getEventId(), MqTopics.ORDER_SHIPPED),
                MqTopics.ORDER_SHIPPED, e.getOrderNo()) == 0) {
            return;
        }
        LocalDateTime shipped = e.getOccurredAt() != null ? e.getOccurredAt() : policy.now();
        LocalDateTime deadline = e.getAutoConfirmDeadline() == null ? null
                : Instant.ofEpochMilli(e.getAutoConfirmDeadline())
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        AftersaleWindow w = windowMapper.selectByOrderNo(e.getOrderNo());
        if (w == null) {
            w = new AftersaleWindow();
            w.setOrderNo(e.getOrderNo());
            w.setUserId(e.getUserId());
            w.setMerchantId(0L);
            w.setOrderStatus(30);
            w.setOrderType(1);
            w.setShippedTime(shipped);
            w.setAutoConfirmDeadline(deadline);
            w.setProductPayFen(0L);
            w.setFreightFen(0L);
            w.setUsedPointsFen(0L);
            // B11：购险标记与保费随发货事件落窗口（事件缺失按 0 处理）
            w.setHasFreightInsurance(nzInt(e.getHasFreightInsurance()));
            w.setPremiumFen(nz(e.getInsurancePremiumFen()));
            w.setWarrantyDays(AftersaleCodes.FREE_AFTERSALE_DAYS);
            windowMapper.insert(w);
        } else {
            windowMapper.updateShipped(e.getOrderNo(), 30,
                    w.getOrderType() == null ? 1 : w.getOrderType(), shipped, deadline,
                    nzInt(e.getHasFreightInsurance()), nz(e.getInsurancePremiumFen()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onOrderConfirmed(OrderConfirmedEvent e) {
        if (mqConsumeLogMapper.insertIgnore(resolveEventId(e.getEventId(), MqTopics.ORDER_CONFIRMED),
                MqTopics.ORDER_CONFIRMED, e.getOrderNo()) == 0) {
            return;
        }
        LocalDateTime confirm = e.getOccurredAt() != null ? e.getOccurredAt() : policy.now();
        LocalDateTime freeDeadline = policy.freeAftersaleDeadline(confirm);
        // 质保期按类目：当前无类目质保数据源，统一按 15 天默认（契约缺口见最终报告）
        int warrantyDays = AftersaleCodes.FREE_AFTERSALE_DAYS;
        AftersaleWindow w = windowMapper.selectByOrderNo(e.getOrderNo());
        if (w == null) {
            w = new AftersaleWindow();
            w.setOrderNo(e.getOrderNo());
            w.setUserId(e.getUserId());
            w.setMerchantId(e.getMerchantId());
            w.setOrderStatus(40);
            w.setOrderType(1);
            w.setConfirmTime(confirm);
            w.setFreeAftersaleDeadline(freeDeadline);
            w.setWarrantyDays(warrantyDays);
            w.setWarrantyDeadline(freeDeadline);
            w.setProductPayFen(nz(e.getProductPayFen()));
            w.setFreightFen(nz(e.getFreightFen()));
            w.setUsedPointsFen(nz(e.getPointsDeductFen()));
            // B11：confirmed 落完整购险标记与实缴保费快照
            w.setHasFreightInsurance(nzInt(e.getHasFreightInsurance()));
            w.setPremiumFen(nz(e.getInsurancePremiumFen()));
            windowMapper.insert(w);
        } else {
            windowMapper.updateConfirmed(e.getOrderNo(), 40, e.getMerchantId(), confirm,
                    freeDeadline, warrantyDays, freeDeadline,
                    nz(e.getProductPayFen()), nz(e.getFreightFen()), nz(e.getPointsDeductFen()),
                    nzInt(e.getHasFreightInsurance()), nz(e.getInsurancePremiumFen()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onRefundSuccess(RefundSucceededEvent e) {
        if (mqConsumeLogMapper.insertIgnore(resolveEventId(e.getEventId(), MqTopics.REFUND_SUCCESS),
                MqTopics.REFUND_SUCCESS, e.getRefundNo()) == 0) {
            return;
        }
        AftersaleInsurance insurance = null;
        AftersaleRefundLocal local = markRefundSuccess(e);
        if (local == null) {
            return;
        }
        AftersaleOrder o = local.order;

        // 明细累计退款守恒（累计 <= 实付）
        List<AftersaleItem> items = itemMapper.selectByAftersaleNo(o.getAftersaleNo());
        for (AftersaleItem it : items) {
            if (it.getRefundFen() != null && it.getRefundFen() > 0
                    && itemRefMapper.addRefunded(it.getOrderItemId(), it.getRefundFen(), o.getAftersaleNo()) == 0) {
                throw new BizException(ErrorCode.AFTERSALE_AMOUNT_EXCEED,
                        "明细累计退款超出实付: " + it.getOrderItemId());
            }
        }

        // 积分按比例退还（design 8.4）
        if (o.getPointsRefund() != null && o.getPointsRefund() > 0) {
            Result<Void> r = userClient.refundPoints(PointsRefundCommand.builder()
                    .userId(o.getUserId())
                    .bizNo(o.getAftersaleNo())
                    .points(o.getPointsRefund().longValue())
                    .build());
            // null 必须按失败处理（解码异常/网络故障）：否则消费记录随事务提交，
            // 积分永久不退且 MQ 不会重投。抛出后事务回滚，broker 重试，bizNo 幂等。
            if (r == null || !r.isSuccess()) {
                throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                        "积分退还失败: " + (r == null ? "下游返回空响应" : r.getMessage()));
            }
        }

        // 售后单 40 → 50
        LocalDateTime refundTime = e.getRefundTime() != null ? e.getRefundTime() : policy.now();
        int oldStatus = o.getStatus();
        long finishLogId = 0L;
        if (oldStatus == AftersaleStatuses.REFUNDING) {
            int rows = orderMapper.updateStatus(o.getAftersaleNo(),
                    AftersaleStatuses.REFUNDING, AftersaleStatuses.FINISHED);
            if (rows == 0) {
                throw new BizException(ErrorCode.CONFLICT, "售后单状态并发冲突");
            }
            o.setStatus(AftersaleStatuses.FINISHED);
            o.setRefundTime(refundTime);
            o.setFinishTime(refundTime);
            orderMapper.updateById(o);
            StatusLog l = new StatusLog();
            l.setAftersaleNo(o.getAftersaleNo());
            l.setOldStatus(oldStatus);
            l.setNewStatus(AftersaleStatuses.FINISHED);
            l.setOperatorId(0L);
            l.setOperatorRole(AftersaleCodes.ROLE_SYSTEM);
            l.setRemark("退款成功，售后完成");
            statusLogMapper.insert(l);
            finishLogId = l.getId();
        }

        // 价保记录 → 已补差
        if (o.getType() == AftersaleTypes.PRICE_PROTECT) {
            PriceProtectRecord pp = priceProtectMapper.selectActiveByOrderNo(o.getOrderNo());
            if (pp != null) {
                pp.setStatus(AftersaleCodes.PRICE_PAID);
                priceProtectMapper.updateById(pp);
            }
        }

        // 运费险：退货退款、已购险、责任方符合 → 每单一次，72h 内理赔
        if (o.getType() == AftersaleTypes.RETURN_REFUND) {
            AftersaleWindow w = windowMapper.selectByOrderNo(o.getOrderNo());
            boolean hasInsurance = w != null && Integer.valueOf(1).equals(w.getHasFreightInsurance());
            int side = o.getResponsibilitySide() == null ? ResponsibilitySide.BUYER : o.getResponsibilitySide();
            if (hasInsurance && policy.insuranceEligible(true, side)
                    && insuranceMapper.selectByOrderNo(o.getOrderNo()) == null) {
                insurance = buildInsurance(o, w, refundTime);
                try {
                    insuranceMapper.insert(insurance);
                } catch (DuplicateKeyException dup) {
                    insurance = null;
                }
            }
        }

        if (oldStatus == AftersaleStatuses.REFUNDING) {
            eventPublisher.publish(o, oldStatus, AftersaleStatuses.FINISHED, null, items, finishLogId);
        }
        if (insurance != null) {
            // P2-1：确定性 eventId=TO-INS-{id}-insurance，与 AftersaleClaimJob 扫表共享幂等键
            AftersaleTimeoutMessage msg = AftersaleTimeoutMessage.forInsurance(
                    insurance.getId(), o.getAftersaleNo(), AftersaleDelayTopics.KIND_INSURANCE);
            // P1-1：延时事件在本消费事务内登记 outbox，与 t_aftersale_insurance 同提交/回滚
            outboxPublisher.publishDelay(AftersaleDelayTopics.AFTERSALE_TIMEOUT,
                    AftersaleDelayTopics.KIND_INSURANCE, msg, o.getOrderNo(), INSURANCE_DELAY_SECONDS);
        }
    }

    private AftersaleInsurance buildInsurance(AftersaleOrder o, AftersaleWindow w, LocalDateTime refundTime) {
        AftersaleInsurance ins = new AftersaleInsurance();
        ins.setOrderNo(o.getOrderNo());
        ins.setAftersaleNo(o.getAftersaleNo());
        ins.setUserId(o.getUserId());
        // B11：保费从窗口快照取（购险时随发货/确认事件落库），退款不退保费，仅作理赔单调阅
        ins.setPremiumFen(w == null ? 0L : nz(w.getPremiumFen()));
        // 实际退货运费无上行数据，按封顶 25 元理赔（claim<=2500 由规则类保证）
        ins.setClaimFen(policy.insuranceClaimFen(AftersaleCodes.INSURANCE_CLAIM_CAP_FEN));
        ins.setStatus(AftersaleCodes.INSURANCE_WAIT);
        ins.setRefundTime(refundTime);
        ins.setClaimDeadline(policy.insuranceClaimDeadline(refundTime));
        return ins;
    }

    /**
     * @return 本域售后退款单上下文；非本域退款（无 aftersaleNo）或重复完成返回 null（直接 ACK）
     */
    private AftersaleRefundLocal markRefundSuccess(RefundSucceededEvent e) {
        com.shop.aftersale.aftersale.entity.AftersaleRefund r =
                refundMapper.selectByRefundNo(e.getRefundNo());
        if (r == null) {
            if (!hasText(e.getAftersaleNo())) {
                return null;
            }
            throw new BizException(ErrorCode.NOT_FOUND, "退款单不存在: " + e.getRefundNo());
        }
        if (r.getStatus() != null && r.getStatus() == AftersaleCodes.REFUND_SUCCESS) {
            return null;
        }
        LocalDateTime refundTime = e.getRefundTime() != null ? e.getRefundTime() : policy.now();
        r.setStatus(AftersaleCodes.REFUND_SUCCESS);
        r.setPayMethod(e.getPayMethod());
        r.setRefundTime(refundTime);
        refundMapper.updateById(r);

        AftersaleOrder o = orderMapper.selectByNo(r.getAftersaleNo());
        if (o == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "售后单不存在: " + r.getAftersaleNo());
        }
        return new AftersaleRefundLocal(r, o);
    }

    private record AftersaleRefundLocal(
            com.shop.aftersale.aftersale.entity.AftersaleRefund refund, AftersaleOrder order) {
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * R4-27：消费幂等 eventId 归一化——消息体信封值优先（BaseEvent 随机 UUID，活路径恒非空）；
     * 空白时回退框架 EventNormalizer 绑定的上下文 eventId（header / noid 合成），避免空键
     * 写库；非 MQ 线程误调用且两源皆空属编程错误，fail-fast。
     */
    private static String resolveEventId(String bodyEventId, String topic) {
        if (hasText(bodyEventId)) {
            return bodyEventId;
        }
        String ctx = MqConsumeContext.currentEventId();
        if (!hasText(ctx)) {
            throw new IllegalStateException("消费事件 eventId 为空且无 MQ 上下文，topic=" + topic);
        }
        return ctx;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static int nzInt(Integer v) {
        return v == null ? 0 : v;
    }
}
