package com.shop.marketing.mq;

import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.marketing.inner.MarketingAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** ORDER_CANCELLED 消费：预核销券退回、秒杀占位释放、拼团退出。eventId 幂等。 */
@Component
@RequiredArgsConstructor
public class OrderCancelledListener implements MqListener<OrderCancelledEvent> {

    private final MqConsumeTemplate consumeTemplate;
    private final MarketingAppService marketingAppService;

    @Override
    public String topic() {
        return MqTopics.ORDER_CANCELLED;
    }

    @Override
    public String consumerGroup() {
        return "cg_marketing_order_cancelled";
    }

    @Override
    public Class<OrderCancelledEvent> type() {
        return OrderCancelledEvent.class;
    }

    @Override
    public void onMessage(OrderCancelledEvent e) {
        consumeTemplate.runOnce(e.getEventId(), topic(), e.getOrderNo(),
                () -> marketingAppService.release(PromotionReleaseCommand.builder()
                        .userId(e.getUserId())
                        .orderNo(e.getOrderNo())
                        .build()));
    }
}
