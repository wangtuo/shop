/**
 * 商品域对外契约根包（对应服务 {@code shop-product-service}，schema {@code shop_product}，Redis DB 1）。
 *
 * <p>本包按 {@code client / dto / event / enums} 四个子包组织商品域对其他服务暴露的全部跨域能力：
 * <ul>
 *   <li>{@code client}：内部 Feign 接口（{@code /inner/product}）——SKU 单个/批量查询、可售校验，
 *       库存 TCC 三阶段（lock 可售→锁定、confirmDeduct 锁定→占用、release 锁定→可售）与售后回库 returnStock；</li>
 *   <li>{@code dto}：SPU/SKU 视图与库存命令对象，金额一律 Long（分）；</li>
 *   <li>{@code event}：库存预警事件（Topic {@code STOCK_WARNING}）；</li>
 *   <li>{@code enums}：商品状态（0 草稿…7 已删除）、库存类型、库存锁定状态、回库原因码值。</li>
 * </ul>
 *
 * <p>硬约束：本包内任何类禁止 import 其他 api 域包，只允许引用 {@code com.shop.common}；
 * Feign 方法签名以 CONTRACTS.md §3 为准，码值以 §4 为准。
 */
package com.shop.api.product;
