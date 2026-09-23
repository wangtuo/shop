package com.shop.product.stock.mq;

import com.shop.api.order.event.OrderCreatedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.product.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_CREATED（cg_product_order_created）：对 items 逐 SKU lockStock，
 * 库存类型按订单活动映射（秒杀3/拼团4/预售2/默认普通1）。eventId + 流水表双重幂等。
 */
@Component
@RequiredArgsConstructor
public class OrderCreatedStockListener implements MqListener<OrderCreatedEvent> {

    private final StockService stockService;

    @Override
    public String topic() {
        return MqTopics.ORDER_CREATED;
    }

    @Override
    public String consumerGroup() {
        return "cg_product_order_created";
    }

    @Override
    public Class<OrderCreatedEvent> type() {
        return OrderCreatedEvent.class;
    }

    @Override
    public void onMessage(OrderCreatedEvent event) {
        stockService.handleOrderCreated(event);
    }
}
