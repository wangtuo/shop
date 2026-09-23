package com.shop.order.mq;

import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.order.mq.consumer.PayEventConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ORDER_PAID 消费者（cg_order_paid）：订单 10→20。
 */
@Component
@RequiredArgsConstructor
public class OrderPaidListener implements MqListener<PaymentSucceededEvent> {

    private final PayEventConsumer consumer;

    @Override
    public String topic() {
        return MqTopics.ORDER_PAID;
    }

    @Override
    public String consumerGroup() {
        return "cg_order_paid";
    }

    @Override
    public Class<PaymentSucceededEvent> type() {
        return PaymentSucceededEvent.class;
    }

    @Override
    public void onMessage(PaymentSucceededEvent message) {
        consumer.onPaid(message);
    }
}
