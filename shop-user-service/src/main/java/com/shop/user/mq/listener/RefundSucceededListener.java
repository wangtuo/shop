package com.shop.user.mq.listener;

import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.user.mq.service.UserPointsMqService;
import com.shop.user.mq.service.impl.UserPointsMqServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * REFUND_SUCCESS 消费者（cg_user_refund）：
 * 余额支付退款入余额（payMethod=余额，bizNo=refundNo 幂等）；事件无积分字段不处理积分。
 */
@Component
@RequiredArgsConstructor
public class RefundSucceededListener implements MqListener<RefundSucceededEvent> {

    private final UserPointsMqService mqService;

    @Override
    public String topic() {
        return MqTopics.REFUND_SUCCESS;
    }

    @Override
    public String consumerGroup() {
        return UserPointsMqServiceImpl.GROUP_REFUND;
    }

    @Override
    public Class<RefundSucceededEvent> type() {
        return RefundSucceededEvent.class;
    }

    @Override
    public void onMessage(RefundSucceededEvent message) {
        mqService.handleRefundSuccess(message);
    }
}
