package com.shop.settlement.mq.listener;

import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.settlement.clearing.service.ClearingReverseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 REFUND_SUCCESS：退款清算冲正（design 7.5），佣金按比例回退、补贴回营销账户、
 * 商户部分先扣待结算不足扣保证金、通道费/技服费不退。
 */
@Component
@RequiredArgsConstructor
public class RefundSucceededListener implements MqListener<RefundSucceededEvent> {

    private final ClearingReverseService reverseService;

    @Override
    public String topic() {
        return MqTopics.REFUND_SUCCESS;
    }

    @Override
    public String consumerGroup() {
        return ClearingReverseService.CG_REFUND;
    }

    @Override
    public Class<RefundSucceededEvent> type() {
        return RefundSucceededEvent.class;
    }

    @Override
    public void onMessage(RefundSucceededEvent message) {
        reverseService.onRefundSucceeded(message);
    }
}
