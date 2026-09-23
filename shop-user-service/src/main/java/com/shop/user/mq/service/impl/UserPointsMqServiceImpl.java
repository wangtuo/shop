package com.shop.user.mq.service.impl;

import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.api.user.dto.AmountCommand;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.dto.PointsDeductCommand;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.api.user.enums.GrowthScene;
import com.shop.api.user.enums.MemberLevels;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.constant.MqTopics;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.GrowthService;
import com.shop.user.member.PointsCalc;
import com.shop.user.mq.service.MqConsumeService;
import com.shop.user.mq.service.UserPointsMqService;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付/订单事件消费处理。
 *
 * <p>幂等：t_user_mq_consume(event_id) + 各业务自身 bizNo 幂等双保险；
 * 消费流水与业务变更在同一事务，失败抛异常由 Broker 重试。
 */
@Service
@RequiredArgsConstructor
public class UserPointsMqServiceImpl implements UserPointsMqService {

    private static final Logger log = LoggerFactory.getLogger(UserPointsMqServiceImpl.class);

    public static final String GROUP_ORDER_PAID = "cg_user_order_paid";
    public static final String GROUP_ORDER_CANCEL = "cg_user_order_cancel";
    public static final String GROUP_REFUND = "cg_user_refund";

    private final MqConsumeService consumeService;
    private final AccountService accountService;
    private final GrowthService growthService;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderPaid(PaymentSucceededEvent event) {
        if (!consumeService.beginConsume(MqTopics.ORDER_PAID,
                GROUP_ORDER_PAID, event.getOrderNo())) {
            return;
        }
        // 1. 下单冻结积分支付成功后实扣（未使用积分时内部 no-op）
        accountService.deductPoints(PointsDeductCommand.builder()
                .userId(event.getUserId())
                .bizNo(event.getOrderNo())
                .build());

        long amountFen = event.getAmountFen() == null ? 0L : event.getAmountFen();
        User user = userMapper.selectById(event.getUserId());
        if (user == null) {
            log.warn("ORDER_PAID 对应用户不存在，跳过积分成长值发放 userId={}", event.getUserId());
            return;
        }
        // 2. 消费返积分：实付金额（元）× 等级积分倍率，四舍五入
        long points = PointsCalc.consumePoints(amountFen, MemberLevels.pointsRateOf(user.getLevel()));
        if (points > 0) {
            accountService.grantPoints(GrantPointsCommand.builder()
                    .userId(event.getUserId())
                    .bizNo(event.getOrderNo())
                    .points(points)
                    .scene(PointsScene.CONSUME)
                    .build());
        }
        // 3. 成长值：实付 1 元 = 1
        long growth = PointsCalc.consumeGrowth(amountFen);
        if (growth > 0) {
            growthService.addGrowth(GrowthCommand.builder()
                    .userId(event.getUserId())
                    .bizNo(event.getOrderNo())
                    .growth((int) Math.min(growth, Integer.MAX_VALUE))
                    .scene(GrowthScene.CONSUME)
                    .build());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderCancelled(OrderCancelledEvent event) {
        if (!consumeService.beginConsume(MqTopics.ORDER_CANCELLED,
                GROUP_ORDER_CANCEL, event.getOrderNo())) {
            return;
        }
        // 释放下单冻结积分（无冻结记录时内部 no-op；支付后取消不会命中冻结记录）
        accountService.releasePoints(PointsReleaseCommand.builder()
                .userId(event.getUserId())
                .bizNo(event.getOrderNo())
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleRefundSuccess(RefundSucceededEvent event) {
        if (!consumeService.beginConsume(MqTopics.REFUND_SUCCESS,
                GROUP_REFUND, event.getRefundNo())) {
            return;
        }
        // 退款入余额：仅原支付方式为余额（3）时；bizNo=refundNo 幂等
        if (event.getPayMethod() != null && event.getPayMethod() == PayMethods.BALANCE.getCode()) {
            accountService.creditMoney(AmountCommand.builder()
                    .userId(event.getUserId())
                    .bizNo(event.getRefundNo())
                    .amountFen(event.getAmountFen())
                    .remark("余额支付退款原路退回 " + event.getOrderNo())
                    .build());
        }
        // 事件未携带退回积分字段：本系统退款退积分由售后域按统一规则调用
        // UserClient.refundPoints 完成，此处不处理积分。
    }
}
