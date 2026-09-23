package com.shop.settlement.mq.listener;

import com.shop.api.order.event.OrderCompletedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.settlement.clearing.service.ClearingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_COMPLETED：B 级商户售后期结束转结算的兜底（design 7.3.2）。
 */
@Component
@RequiredArgsConstructor
public class OrderCompletedListener implements MqListener<OrderCompletedEvent> {

    private final ClearingService clearingService;

    @Override
    public String topic() {
        return MqTopics.ORDER_COMPLETED;
    }

    @Override
    public String consumerGroup() {
        return ClearingService.CG_COMPLETED;
    }

    @Override
    public Class<OrderCompletedEvent> type() {
        return OrderCompletedEvent.class;
    }

    @Override
    public void onMessage(OrderCompletedEvent message) {
        clearingService.onOrderCompleted(message);
    }
}
