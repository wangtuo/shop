package com.shop.user.mq.listener;

import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.user.mq.service.UserPointsMqService;
import com.shop.user.mq.service.impl.UserPointsMqServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ORDER_PAID 消费者（cg_user_order_paid）：
 * 实扣下单冻结积分 + 实付金额×等级倍率发积分 + 实付 1 元 1 成长值（bizNo=orderNo 防重）。
 */
@Component
@RequiredArgsConstructor
public class OrderPaidListener implements MqListener<PaymentSucceededEvent> {

    private final UserPointsMqService mqService;

    @Override
    public String topic() {
        return MqTopics.ORDER_PAID;
    }

    @Override
    public String consumerGroup() {
        return UserPointsMqServiceImpl.GROUP_ORDER_PAID;
    }

    @Override
    public Class<PaymentSucceededEvent> type() {
        return PaymentSucceededEvent.class;
    }

    @Override
    public void onMessage(PaymentSucceededEvent message) {
        mqService.handleOrderPaid(message);
    }
}
