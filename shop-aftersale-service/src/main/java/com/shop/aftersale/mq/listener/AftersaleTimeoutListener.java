package com.shop.aftersale.mq.listener;

import com.shop.aftersale.support.AftersaleDelayTopics;
import com.shop.aftersale.support.AftersaleTimeoutMessage;
import com.shop.framework.mq.MqListener;
import com.shop.aftersale.aftersale.service.AftersaleTimeoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 售后超时延时消息（与定时扫描双保险）。
 */
@Component
@RequiredArgsConstructor
public class AftersaleTimeoutListener implements MqListener<AftersaleTimeoutMessage> {

    private final AftersaleTimeoutService timeoutService;

    @Override
    public String topic() {
        return AftersaleDelayTopics.AFTERSALE_TIMEOUT;
    }

    @Override
    public String consumerGroup() {
        return "cg_aftersale_timeout";
    }

    @Override
    public Class<AftersaleTimeoutMessage> type() {
        return AftersaleTimeoutMessage.class;
    }

    @Override
    public void onMessage(AftersaleTimeoutMessage message) {
        // 与订单/退款事件消费者同构：dispatchTracked 同事务写 t_aftersale_mq_consume 幂等流水
        timeoutService.dispatchTracked(message);
    }
}
