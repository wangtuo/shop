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
 * 售后期结束延时消息消费者（cg_order_aftersale_window，确认收货后 15 天）：
 * 40→70 并发 ORDER_COMPLETED。
 */
@Component
@RequiredArgsConstructor
public class AftersaleWindowListener implements MqListener<OrderDelayMessage> {

    private final MqConsumeService consumeService;
    private final OrderOperateService operateService;

    @Override
    public String topic() {
        return OrderDelayTopics.ORDER_AFTERSALE_WINDOW;
    }

    @Override
    public String consumerGroup() {
        return "cg_order_aftersale_window";
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
        operateService.closeAftersaleWindow(message.getOrderNo());
    }
}
