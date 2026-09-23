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
 * 支付超时延时消息消费者（cg_order_pay_timeout）：订单仍为 10 则取消并发 ORDER_CANCELLED。
 * 与定时扫描任务互为双保险，eventId 幂等。
 */
@Component
@RequiredArgsConstructor
public class PayTimeoutListener implements MqListener<OrderDelayMessage> {

    private final MqConsumeService consumeService;
    private final OrderOperateService operateService;

    @Override
    public String topic() {
        return OrderDelayTopics.ORDER_PAY_TIMEOUT;
    }

    @Override
    public String consumerGroup() {
        return "cg_order_pay_timeout";
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
        operateService.timeoutCancel(message.getOrderNo());
    }
}
