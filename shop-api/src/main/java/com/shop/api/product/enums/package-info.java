/**
 * 商品域枚举常量包。
 *
 * <p>包含商品状态（{@code GoodsStatuses}，design 3.2）、库存类型（{@code StockTypes}）、
 * 库存锁定状态（{@code StockLockStatuses}）与售后回库原因（{@code StockReturnReasons}）。
 * 库存相关枚举围绕 design 3.3 的库存四状态（可售 / 锁定 / 占用 / 物理）与扣减规则定义：
 * 下单可售→锁定、支付成功锁定→占用、取消/超时锁定→可售、售后非质量问题回可售而质量问题入残次。</p>
 */
package com.shop.api.product.enums;
