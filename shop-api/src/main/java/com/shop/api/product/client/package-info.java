/**
 * 商品域对外 Feign 客户端契约包。
 *
 * <p>对应服务 shop-product-service 的内部接口前缀 {@code /inner/product}，由订单、营销等域在
 * 下单链路上同步调用（CONTRACTS.md §3）。库存操作遵循 design 3.3 的库存四状态
 * （可售 / 锁定 / 占用 / 物理）与扣减规则：</p>
 * <ul>
 *     <li>下单：可售 → 锁定（TCC-try）；</li>
 *     <li>支付成功：锁定 → 占用（TCC-confirm）；</li>
 *     <li>取消/超时：锁定 → 可售（TCC-cancel）；</li>
 *     <li>发货：占用扣减（物理扣减在 WMS）；</li>
 *     <li>售后退货：按责任判定回可售或入残次。</li>
 * </ul>
 */
package com.shop.api.product.client;
