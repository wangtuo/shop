package com.shop.user.mq.listener;

import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.user.mq.service.UserPointsMqService;
import com.shop.user.mq.service.impl.UserPointsMqServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * ORDER_CANCELLED 消费者（cg_user_order_cancel）：释放该订单的冻结积分。
 */
@Component
@RequiredArgsConstructor
public class OrderCancelledListener implements MqListener<OrderCancelledEvent> {

    private final UserPointsMqService mqService;

    @Override
    public String topic() {
        return MqTopics.ORDER_CANCELLED;
    }

    @Override
    public String consumerGroup() {
        return UserPointsMqServiceImpl.GROUP_ORDER_CANCEL;
    }

    @Override
    public Class<OrderCancelledEvent> type() {
        return OrderCancelledEvent.class;
    }

    @Override
    public void onMessage(OrderCancelledEvent message) {
        mqService.handleOrderCancelled(message);
    }
}
