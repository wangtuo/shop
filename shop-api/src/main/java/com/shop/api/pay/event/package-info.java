/**
 * 支付域 MQ 事件契约包（CONTRACTS.md §5，Topic 常量见 MqTopics）。
 *
 * <ul>
 *     <li>{@link com.shop.api.pay.event.PaymentSucceededEvent} → ORDER_PAID：支付成功后扇出，
 *     驱动订单转待发货、库存锁定转占用、券/秒杀核销、积分扣减与发放、清算登记；</li>
 *     <li>{@link com.shop.api.pay.event.RefundSucceededEvent} → REFUND_SUCCESS：退款成功后扇出，
 *     驱动清算冲正、积分/余额退回、订单售后结果回写。</li>
 * </ul>
 *
 * <p>事件由支付域在渠道异步回调中产生：design 6.2 要求回调必须先验签、再幂等校验，
 * 支付域只做验签 / 幂等 / 落单，跨域状态变更一律通过事件完成；事件体字段自包含，
 * 消费者不得回查支付库，并以 eventId / 业务单号幂等消费。</p>
 *
 * <p>退款事件的金额语义遵循 design 6.4：原路退回，混合支付按比例分别退回，
 * 部分退款累计不超过实付金额。</p>
 */
package com.shop.api.pay.event;
