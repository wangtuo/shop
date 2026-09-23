package com.shop.settlement.mq.listener;

import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.enums.PayScenes;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.settlement.deposit.service.DepositPaySettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_PAID 的保证金缴费场景（payScene=4，消费组 cg_sett_deposit_pay）。
 *
 * <p>与清算消费者 {@code PaymentSucceededListener}（cg_sett_paid）互斥分工：本消费者仅处理
 * scene=4；非该场景（null/1/2/3…）直接 ACK，严禁抛错毒丸。scene=4 守卫必须在 mq_consume
 * 登记之前生效，避免污染消费流水（服务内仍有二次防御）。</p>
 */
@Component
@RequiredArgsConstructor
public class DepositPaySucceededListener implements MqListener<PaymentSucceededEvent> {

    private final DepositPaySettlementService depositPaySettlementService;

    @Override
    public String topic() {
        return MqTopics.ORDER_PAID;
    }

    @Override
    public String consumerGroup() {
        return DepositPaySettlementService.CG_DEPOSIT_PAY;
    }

    @Override
    public Class<PaymentSucceededEvent> type() {
        return PaymentSucceededEvent.class;
    }

    @Override
    public void onMessage(PaymentSucceededEvent message) {
        if (message.getPayScene() == null || message.getPayScene() != PayScenes.DEPOSIT) {
            // 非保证金缴费场景：直接 ACK（清算侧由 cg_sett_paid 处理）
            return;
        }
        depositPaySettlementService.onPaymentSucceeded(message);
    }
}
