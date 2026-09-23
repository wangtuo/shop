/**
 * 营销域跨服务 Feign 客户端契约（CONTRACTS.md §3 MarketingClient，Wave-1 逐字实现）。
 *
 * <p>{@code @FeignClient(name="shop-marketing-service", path="/inner/marketing")}，
 * 订单域在确认订单页与下单链路同步调用四个方法：
 * <ol>
 *   <li>{@code calculate}：确认订单页/下单前价格试算（纯计算，不锁资源）；</li>
 *   <li>{@code lockPromotion}：下单（ORDER_CREATED）预核销券、锁定秒杀库存/拼团/预售资源，orderNo 幂等；</li>
 *   <li>{@code confirmPromotion}：支付成功（ORDER_PAID）正式核销券、秒杀扣减；</li>
 *   <li>{@code releasePromotion}：取消/超时（ORDER_CANCELLED）释放预核销券与锁定库存。</li>
 * </ol>
 *
 * <p>试算与锁定遵循 design.md 4.2.1 叠加顺序：限时折扣/秒杀 → 满减/满折/第N件 →
 * 品类券 → 店铺券 → 平台通用券 → 积分抵现；4.2.3 互斥规则：秒杀互斥一切、同层券限 1 张、
 * 满减与满折互斥（取优惠最大）、拼团不可用券与积分、预售仅尾款阶段可用券。
 */
package com.shop.api.marketing.client;
