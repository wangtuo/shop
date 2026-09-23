/**
 * 支付域码值枚举包：支付方式、支付/退款状态、退款类型与来源、终端、对账差异类型。
 *
 * <p>码值与 CONTRACTS.md §4、design 第六章严格一致，跨服务传输一律使用 Integer 码值
 * （DTO 字段），服务内部可通过各枚举的 {@code of(Integer)} 解析。</p>
 *
 * <p>三类对账差错（design 6.5，每日凌晨 T+1 拉取渠道账单与系统支付单逐笔比对）：</p>
 * <ul>
 *     <li>{@link com.shop.api.pay.enums.ReconcileDiffTypes#LONG} 长款：渠道有、系统无，核实后补单补发货；</li>
 *     <li>{@link com.shop.api.pay.enums.ReconcileDiffTypes#SHORT} 短款：系统有、渠道无，核实未支付则关单；</li>
 *     <li>{@link com.shop.api.pay.enums.ReconcileDiffTypes#AMOUNT_MISMATCH} 金额不符：以渠道为准调账并挂账，
 *     无法自动处理的转人工差错工单。</li>
 * </ul>
 */
package com.shop.api.pay.enums;
