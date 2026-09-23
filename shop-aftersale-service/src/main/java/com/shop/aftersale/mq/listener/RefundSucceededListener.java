package com.shop.aftersale.mq.listener;

import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.aftersale.mq.service.AftersaleMqService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 REFUND_SUCCESS：推进售后单 40→50、积分退还、运费险登记。
 */
@Component
@RequiredArgsConstructor
public class RefundSucceededListener implements MqListener<RefundSucceededEvent> {

    private final AftersaleMqService mqService;

    @Override
    public String topic() {
        return MqTopics.REFUND_SUCCESS;
    }

    @Override
    public String consumerGroup() {
        return "cg_aftersale_refund_success";
    }

    @Override
    public Class<RefundSucceededEvent> type() {
        return RefundSucceededEvent.class;
    }

    @Override
    public void onMessage(RefundSucceededEvent event) {
        mqService.onRefundSuccess(event);
    }
}
