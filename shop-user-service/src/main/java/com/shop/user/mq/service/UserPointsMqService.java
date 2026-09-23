package com.shop.user.mq.service;

import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.event.RefundSucceededEvent;

/**
 * 用户域 MQ 事件业务处理（消费流水登记 + 业务处理同事务，eventId 幂等）。
 */
public interface UserPointsMqService {

    /** ORDER_PAID：实扣下单冻结积分 + 按实付金额×等级倍率发积分 + 实付1元1成长值 */
    void handleOrderPaid(PaymentSucceededEvent event);

    /** ORDER_CANCELLED：释放该订单冻结积分 */
    void handleOrderCancelled(OrderCancelledEvent event);

    /** REFUND_SUCCESS：余额支付退款入余额；事件无积分字段，积分由售后域走统一规则 */
    void handleRefundSuccess(RefundSucceededEvent event);
}
