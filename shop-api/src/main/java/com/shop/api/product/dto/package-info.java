/**
 * 商品域数据传输对象包：SKU/SPU 视图对象与库存 TCC 命令对象。
 *
 * <p>库存命令对应 design 3.3 的库存四状态（可售 / 锁定 / 占用 / 物理）与扣减规则：
 * 下单时可售 → 锁定（{@code StockLockCommand}），支付成功锁定 → 占用
 * （{@code StockDeductCommand}），取消/超时锁定 → 可售（{@code StockReleaseCommand}），
 * 售后退货按责任回可售或入残次（{@code StockReturnCommand}）。
 * 金额字段统一为 Long 分，集合字段统一初始化为空集合。</p>
 */
package com.shop.api.product.dto;
