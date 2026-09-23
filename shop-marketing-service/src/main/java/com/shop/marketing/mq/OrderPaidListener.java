package com.shop.marketing.mq;

import com.shop.api.marketing.dto.PromotionConfirmCommand;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.marketing.inner.MarketingAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** ORDER_PAID 消费：券核销 / 秒杀锁定转扣减 / 预售尾款核销。eventId 幂等。 */
@Component
@RequiredArgsConstructor
public class OrderPaidListener implements MqListener<PaymentSucceededEvent> {

    private final MqConsumeTemplate consumeTemplate;
    private final MarketingAppService marketingAppService;

    @Override
    public String topic() {
        return MqTopics.ORDER_PAID;
    }

    @Override
    public String consumerGroup() {
        return "cg_marketing_order_paid";
    }

    @Override
    public Class<PaymentSucceededEvent> type() {
        return PaymentSucceededEvent.class;
    }

    @Override
    public void onMessage(PaymentSucceededEvent e) {
        consumeTemplate.runOnce(e.getEventId(), topic(), e.getOrderNo(),
                () -> marketingAppService.confirm(PromotionConfirmCommand.builder()
                        .userId(e.getUserId())
                        .orderNo(e.getOrderNo())
                        .build()));
    }
}
