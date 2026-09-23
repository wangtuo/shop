package com.shop.settlement.mq.listener;

import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.settlement.clearing.service.ClearingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_CONFIRMED：补全/重算分账明细，stage=20 待结算并落 due_date（design 7.3）。
 */
@Component
@RequiredArgsConstructor
public class OrderConfirmedListener implements MqListener<OrderConfirmedEvent> {

    private final ClearingService clearingService;

    @Override
    public String topic() {
        return MqTopics.ORDER_CONFIRMED;
    }

    @Override
    public String consumerGroup() {
        return ClearingService.CG_CONFIRMED;
    }

    @Override
    public Class<OrderConfirmedEvent> type() {
        return OrderConfirmedEvent.class;
    }

    @Override
    public void onMessage(OrderConfirmedEvent message) {
        clearingService.onOrderConfirmed(message);
    }
}
