package com.shop.product.stock.mq;

import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.product.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_PAID（cg_product_order_paid）：锁定 → 占用（TCC-confirm）。
 * 事件体不含 items，以本域 t_product_stock_log 锁定流水为准。
 */
@Component
@RequiredArgsConstructor
public class OrderPaidStockListener implements MqListener<PaymentSucceededEvent> {

    private final StockService stockService;

    @Override
    public String topic() {
        return MqTopics.ORDER_PAID;
    }

    @Override
    public String consumerGroup() {
        return "cg_product_order_paid";
    }

    @Override
    public Class<PaymentSucceededEvent> type() {
        return PaymentSucceededEvent.class;
    }

    @Override
    public void onMessage(PaymentSucceededEvent event) {
        stockService.handleOrderPaid(event);
    }
}
