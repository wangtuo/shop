/**
 * 订单域 MQ 事件契约包（CONTRACTS.md §5，全部 Topic：ORDER_CREATED / ORDER_CANCELLED /
 * ORDER_SHIPPED / ORDER_CONFIRMED / ORDER_COMPLETED）。
 *
 * <p>所有事件继承 {@code BaseEvent}（eventId 幂等键、occurredAt、bizNo），字段自包含，
 * 消费者禁止回查订单库；消费端以 eventId/orderNo 幂等。
 *
 * <p>状态流转见 design.md 5.2：待付款(10) --支付成功--> 待发货(20) --发货--> 待收货(30)
 * --确认收货/超时--> 已完成(40)；取消/超时为已取消(50)；售后中为退款中(60)/
 * 退货退款中(61)/换货中(62)；终态已关闭(70)。
 *
 * <p>支付超时见 design.md 5.3.3：普通订单 30 分钟、秒杀订单 15 分钟、
 * 拼团订单开团后 24 小时内支付且拼团成功后 30 分钟、预售订单尾款期 3 天；
 * ORDER_SHIPPED 的 autoConfirmDeadline 按「发货后 10 天自动收货」计算。
 */
package com.shop.api.order.event;
