package com.shop.order.mq;

import com.shop.framework.mq.MqListener;
import com.shop.order.mq.message.OrderDelayMessage;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.service.OrderOperateService;
import com.shop.order.support.OrderDelayTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自动确认收货延时消息消费者（cg_order_auto_confirm，发货后 10 天）。
 */
@Component
@RequiredArgsConstructor
public class AutoConfirmListener implements MqListener<OrderDelayMessage> {

    private final MqConsumeService consumeService;
    private final OrderOperateService operateService;

    @Override
    public String topic() {
        return OrderDelayTopics.ORDER_AUTO_CONFIRM;
    }

    @Override
    public String consumerGroup() {
        return "cg_order_auto_confirm";
    }

    @Override
    public Class<OrderDelayMessage> type() {
        return OrderDelayMessage.class;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(OrderDelayMessage message) {
        if (!consumeService.firstTime(message.getEventId(), topic(), message.getOrderNo())) {
            return;
        }
        operateService.autoConfirm(message.getOrderNo());
    }
}
