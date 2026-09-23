package com.shop.aftersale.mq.listener;

import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.aftersale.mq.service.AftersaleMqService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_CONFIRMED：落收货后 15 天售后期窗口。
 */
@Component
@RequiredArgsConstructor
public class OrderConfirmedListener implements MqListener<OrderConfirmedEvent> {

    private final AftersaleMqService mqService;

    @Override
    public String topic() {
        return MqTopics.ORDER_CONFIRMED;
    }

    @Override
    public String consumerGroup() {
        return "cg_aftersale_confirmed";
    }

    @Override
    public Class<OrderConfirmedEvent> type() {
        return OrderConfirmedEvent.class;
    }

    @Override
    public void onMessage(OrderConfirmedEvent event) {
        mqService.onOrderConfirmed(event);
    }
}
