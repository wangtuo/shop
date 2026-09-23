package com.shop.product.stock.mq;

import com.shop.api.order.event.OrderShippedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.product.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 ORDER_SHIPPED（cg_product_shipped，B13）：库存流水 1 已扣减 → 4 已出账，
 * occupied_stock 出账（行级守卫）。eventId 走 t_product_mq_consume 幂等，
 * 重复发货消息安全跳过；流水缺失/状态冲突抛异常由 Broker 重试。
 */
@Component
@RequiredArgsConstructor
public class OrderShippedStockListener implements MqListener<OrderShippedEvent> {

    public static final String CONSUMER_GROUP = "cg_product_shipped";

    private final StockService stockService;

    @Override
    public String topic() {
        return MqTopics.ORDER_SHIPPED;
    }

    @Override
    public String consumerGroup() {
        return CONSUMER_GROUP;
    }

    @Override
    public Class<OrderShippedEvent> type() {
        return OrderShippedEvent.class;
    }

    @Override
    public void onMessage(OrderShippedEvent event) {
        stockService.handleOrderShipped(event);
    }
}
