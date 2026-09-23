package com.shop.product.stock.mq;

import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.product.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_CANCELLED（cg_product_order_cancel）：锁定 → 可售（TCC-cancel）。
 */
@Component
@RequiredArgsConstructor
public class OrderCancelledStockListener implements MqListener<OrderCancelledEvent> {

    private final StockService stockService;

    @Override
    public String topic() {
        return MqTopics.ORDER_CANCELLED;
    }

    @Override
    public String consumerGroup() {
        return "cg_product_order_cancel";
    }

    @Override
    public Class<OrderCancelledEvent> type() {
        return OrderCancelledEvent.class;
    }

    @Override
    public void onMessage(OrderCancelledEvent event) {
        stockService.handleOrderCancelled(event);
    }
}
