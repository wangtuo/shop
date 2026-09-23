package com.shop.aftersale.mq.listener;

import com.shop.api.order.event.OrderShippedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.aftersale.mq.service.AftersaleMqService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_SHIPPED：落可申请窗口（收货前仅退款）。
 */
@Component
@RequiredArgsConstructor
public class OrderShippedListener implements MqListener<OrderShippedEvent> {

    private final AftersaleMqService mqService;

    @Override
    public String topic() {
        return MqTopics.ORDER_SHIPPED;
    }

    @Override
    public String consumerGroup() {
        return "cg_aftersale_shipped";
    }

    @Override
    public Class<OrderShippedEvent> type() {
        return OrderShippedEvent.class;
    }

    @Override
    public void onMessage(OrderShippedEvent event) {
        mqService.onOrderShipped(event);
    }
}
