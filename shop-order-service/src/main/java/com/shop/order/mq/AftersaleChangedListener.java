package com.shop.order.mq;

import com.shop.api.aftersale.event.AftersaleChangedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.order.mq.consumer.AftersaleEventConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AFTERSALE_CHANGED 消费者（cg_order_aftersale）：同步明细售后状态与整单售后态。
 */
@Component
@RequiredArgsConstructor
public class AftersaleChangedListener implements MqListener<AftersaleChangedEvent> {

    private final AftersaleEventConsumer consumer;

    @Override
    public String topic() {
        return MqTopics.AFTERSALE_CHANGED;
    }

    @Override
    public String consumerGroup() {
        return "cg_order_aftersale";
    }

    @Override
    public Class<AftersaleChangedEvent> type() {
        return AftersaleChangedEvent.class;
    }

    @Override
    public void onMessage(AftersaleChangedEvent message) {
        consumer.onAftersaleChanged(message);
    }
}
