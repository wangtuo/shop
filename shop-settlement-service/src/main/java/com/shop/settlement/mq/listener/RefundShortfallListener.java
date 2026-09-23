package com.shop.settlement.mq.listener;

import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.settlement.clearing.event.RefundShortfallEvent;
import com.shop.settlement.clearing.service.ShortfallWorkOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 shop_refund_shortfall（P0-1 穿仓缺口工单）：
 * 消费组 cg_sett_shortfall（create-topics.sh 预建，勿改名），tag "*" 全订阅。
 * 落库/告警语义与三层幂等见 {@link ShortfallWorkOrderService#onShortfall}。
 */
@Component
@RequiredArgsConstructor
public class RefundShortfallListener implements MqListener<RefundShortfallEvent> {

    private final ShortfallWorkOrderService shortfallWorkOrderService;

    @Override
    public String topic() {
        return MqTopics.REFUND_SHORTFALL;
    }

    @Override
    public String consumerGroup() {
        return ShortfallWorkOrderService.CG_SHORTFALL;
    }

    @Override
    public Class<RefundShortfallEvent> type() {
        return RefundShortfallEvent.class;
    }

    @Override
    public void onMessage(RefundShortfallEvent message) {
        shortfallWorkOrderService.onShortfall(message);
    }
}
