package com.shop.pay.mq.listener;

import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.pay.feature.payment.service.PaymentService;
import com.shop.pay.mq.message.PayCheckMessage;
import com.shop.pay.mq.support.MqConsumeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 支付状态主动查询延时消息消费者（超时双保险的消息侧）。
 * 以 eventId 幂等消费；主动查询渠道并推进支付单状态。
 */
@Component
@RequiredArgsConstructor
public class PayCheckDelayListener implements MqListener<PayCheckMessage> {

    public static final String GROUP = "cg_pay_timeout_query";

    private final MqConsumeSupport mqConsumeSupport;
    private final PaymentService paymentService;

    @Override
    public String topic() {
        return MqTopics.PAY_RESULT;
    }

    @Override
    public String tag() {
        return "check";
    }

    @Override
    public String consumerGroup() {
        return GROUP;
    }

    @Override
    public Class<PayCheckMessage> type() {
        return PayCheckMessage.class;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onMessage(PayCheckMessage message) {
        if (!mqConsumeSupport.firstConsume(GROUP, MqTopics.PAY_RESULT, message)) {
            return;
        }
        paymentService.activeQuery(message.getPayNo());
    }
}
