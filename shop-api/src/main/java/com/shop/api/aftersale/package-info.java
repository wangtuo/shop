/**
 * 售后域对外契约根包（对应服务 {@code shop-aftersale-service}，schema {@code shop_aftersale}，Redis DB 6）。
 *
 * <p>本包按 {@code dto / event / enums} 三个子包组织售后域对外能力——本域<b>不提供 Feign</b>，
 * 状态变更完全通过事件广播（CONTRACTS.md §3 末、§5）：
 * <ul>
 *   <li>{@code dto}：售后明细行消息 AftersaleItemMessage（随状态事件携带），金额一律 Long（分）；</li>
 *   <li>{@code event}：售后单状态变更事件（Topic {@code AFTERSALE_CHANGED}），
 *       订单域据此更新明细售后状态，商品域在买家责任事件退货入库；</li>
 *   <li>{@code enums}：售后类型（1 仅退款…5 价保）、售后状态机（10/20/30/40/41/42/43/50/55/80/90）、
 *       运费责任方、平台仲裁结果码值。</li>
 * </ul>
 *
 * <p>时限规则（design 8.5、CONTRACTS §4）：商家审核 2 天超时自动同意，确认收货 3 天，换货发货 5 天转退款；
 * 退款金额口径见 design 8.4（优惠券不退、积分按比例退）；价保见 design 8.8。
 *
 * <p>硬约束：本包内任何类禁止 import 其他 api 域包，只允许引用 {@code com.shop.common}；码值以 CONTRACTS.md §4 为准。
 */
package com.shop.api.aftersale;
