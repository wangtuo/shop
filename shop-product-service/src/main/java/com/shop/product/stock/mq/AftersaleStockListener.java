package com.shop.product.stock.mq;

import com.shop.api.aftersale.event.AftersaleChangedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.product.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 消费 AFTERSALE_CHANGED（cg_product_aftersale）：售后单完成且为退货退款/换货时回库；
 * 买家责任/换货回可售，商家责任（质量问题）入残次仓。
 */
@Component
@RequiredArgsConstructor
public class AftersaleStockListener implements MqListener<AftersaleChangedEvent> {

    private final StockService stockService;

    @Override
    public String topic() {
        return MqTopics.AFTERSALE_CHANGED;
    }

    @Override
    public String consumerGroup() {
        return "cg_product_aftersale";
    }

    @Override
    public Class<AftersaleChangedEvent> type() {
        return AftersaleChangedEvent.class;
    }

    @Override
    public void onMessage(AftersaleChangedEvent event) {
        stockService.handleAftersaleChanged(event);
    }
}
