package com.shop.settlement.mq.listener;

import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.settlement.clearing.service.ClearingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_PAID：登记清算单 stage=10 待清算（design 7.3）。
 */
@Component
@RequiredArgsConstructor
public class PaymentSucceededListener implements MqListener<PaymentSucceededEvent> {

    private final ClearingService clearingService;

    @Override
    public String topic() {
        return MqTopics.ORDER_PAID;
    }

    @Override
    public String consumerGroup() {
        return ClearingService.CG_PAID;
    }

    @Override
    public Class<PaymentSucceededEvent> type() {
        return PaymentSucceededEvent.class;
    }

    @Override
    public void onMessage(PaymentSucceededEvent message) {
        clearingService.onPaymentSucceeded(message);
    }
}
