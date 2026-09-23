/**
 * 售后域对外事件契约（RocketMQ 事件体，继承 {@link com.shop.common.model.BaseEvent}）。
 *
 * <p>售后域不提供 Feign（CONTRACTS.md §3 末），状态流转通过
 * {@link com.shop.api.aftersale.event.AftersaleChangedEvent}
 * （Topic：{@code AFTERSALE_CHANGED}）广播：
 * 订单域更新订单 / 明细售后状态（60 退款中 / 61 退货退款中 / 62 换货中等），
 * 商品域在买家责任事件做退货入库，清算链路不直接由本事件冲正
 * （结算域消费支付域 REFUND_SUCCESS 内部冲正，CONTRACTS.md §5）。
 *
 * <p>时限与规则（design.md）：
 * <ul>
 *     <li>8.5 审核超时：仅退款 / 退货退款 / 换货 2 天自动同意；
 *     商家确认收货 3 天自动确认退款；换货发货 5 天超时转退款；</li>
 *     <li>8.6 运费险：最高赔付 25 元，退款成功后 72 小时理赔到账，每单一次；</li>
 *     <li>8.7 平台介入：3 天举证、5 个工作日终局仲裁；</li>
 *     <li>8.8 价保：下单后 7 天（大促 30 天）、同商品降价补差、每单一次。</li>
 * </ul>
 *
 * <p>消费者必须以 eventId 幂等去重，并依据 oldStatus → newStatus 做状态机校验，
 * 禁止逆向覆盖。
 */
package com.shop.api.aftersale.event;
