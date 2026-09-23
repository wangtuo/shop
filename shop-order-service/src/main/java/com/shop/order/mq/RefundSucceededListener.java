package com.shop.order.mq;

import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.order.mq.consumer.PayEventConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * REFUND_SUCCESS 消费者（cg_order_refund）：明细退款回写、全额关单、发票冲红。
 */
@Component
@RequiredArgsConstructor
public class RefundSucceededListener implements MqListener<RefundSucceededEvent> {

    private final PayEventConsumer consumer;

    @Override
    public String topic() {
        return MqTopics.REFUND_SUCCESS;
    }

    @Override
    public String consumerGroup() {
        return "cg_order_refund";
    }

    @Override
    public Class<RefundSucceededEvent> type() {
        return RefundSucceededEvent.class;
    }

    @Override
    public void onMessage(RefundSucceededEvent message) {
        consumer.onRefunded(message);
    }
}
