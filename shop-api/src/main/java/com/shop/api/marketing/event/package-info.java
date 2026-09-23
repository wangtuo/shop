/**
 * 营销域 MQ 事件契约（CONTRACTS.md §5），均继承 {@link com.shop.common.model.BaseEvent}
 * （自带 eventId 幂等键、occurredAt、bizNo），消费者以 eventId/业务单号去重。
 *
 * <ul>
 *   <li>{@code SeckillEvent}（SECKILL_EVENT）：秒杀库存 锁定/扣减/释放，配合下单、ORDER_PAID、ORDER_CANCELLED；</li>
 *   <li>{@code GroupbuyEvent}（GROUPBUY_EVENT）：开团/参团/成团/失败；24h 有效期，2/3/5/10 人团，
 *       成团转待发货、失败自动退款（design.md 4.4）；</li>
 *   <li>{@code PresaleEvent}（PRESALE_EVENT）：定金支付/尾款提醒/取消；尾款期（通常 3 天）超时取消定金不退
 *       （design.md 4.5）。</li>
 * </ul>
 *
 * <p>营销对订单事件的消费语义（CONTRACTS.md §5）：ORDER_CREATED 锁券/秒杀确认，
 * ORDER_PAID 核销券/秒杀扣减，ORDER_CANCELLED 释放；叠加与互斥规则同 design.md 4.2.1/4.2.3——
 * 秒杀互斥一切、同层券 1 张、满减满折互斥、拼团不可用券与积分、预售仅尾款可用券。
 */
package com.shop.api.marketing.event;
