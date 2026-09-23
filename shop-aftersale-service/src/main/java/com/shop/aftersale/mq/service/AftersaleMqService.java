package com.shop.aftersale.mq.service;

import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.api.pay.event.RefundSucceededEvent;

/**
 * 售后域对内事件处理（订单事件投影 / 退款成功推进）。
 */
public interface AftersaleMqService {

    /** 落发货窗口（收货前仅退款时限）。 */
    void onOrderShipped(OrderShippedEvent event);

    /** 落收货窗口（收货后 15 天售后期 / 质保期）。 */
    void onOrderConfirmed(OrderConfirmedEvent event);

    /** 退款成功：推进售后单 40→50、退积分、登记运费险理赔。 */
    void onRefundSuccess(RefundSucceededEvent event);
}
