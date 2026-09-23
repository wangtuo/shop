/**
 * 营销域对外契约根包（对应服务 {@code shop-marketing-service}，schema {@code shop_marketing}，Redis DB 2）。
 *
 * <p>本包按 {@code client / dto / event / enums} 四个子包组织营销域对其他服务暴露的全部跨域能力：
 * <ul>
 *   <li>{@code client}：内部 Feign 接口（{@code /inner/marketing}）——确认订单页价格试算 calculate，
 *       下单锁券/锁活动资源 lockPromotion，支付成功确认 confirmPromotion，取消释放 releasePromotion；</li>
 *   <li>{@code dto}：试算命令/结果（含 SKU 级分摊明细）与营销资源 TCC 命令；</li>
 *   <li>{@code event}：秒杀/拼团/预售状态事件（Topic {@code SECKILL_EVENT}/{@code GROUPBUY_EVENT}/{@code PRESALE_EVENT}）；</li>
 *   <li>{@code enums}：活动类型、券类型/状态/发放方式、叠加层级与各活动操作类型码值。</li>
 * </ul>
 *
 * <p>核心规则：优惠按 design 4.2.1 六层叠加、4.2.2 最大余数法分摊、4.2.3 互斥
 * （秒杀互斥一切、拼团不可用券与积分、预售仅尾款可用券）。
 *
 * <p>硬约束：本包内任何类禁止 import 其他 api 域包，只允许引用 {@code com.shop.common}；
 * Feign 方法签名以 CONTRACTS.md §3 为准，码值以 §4 为准。
 */
package com.shop.api.marketing;
