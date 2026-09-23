/**
 * 商品域 MQ 事件契约包（CONTRACTS.md §5，Topic 常量见 {@code MqTopics}）。
 *
 * <p>当前定义库存预警事件 {@code StockWarningEvent}：当可售库存 ≤ 预警阈值时发出，
 * 是 design 3.3 库存四状态（可售 / 锁定 / 占用 / 物理）与扣减规则的监控出口——
 * 下单可售转锁定、支付成功锁定转占用、取消回滚、售后回库等动作导致可售量触达阈值即预警，
 * 库存为 0 自动下架。事件体字段自包含，消费者不回查商品库。</p>
 */
package com.shop.api.product.event;
