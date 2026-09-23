/**
 * 清算域对外 DTO 契约（清算结果快照与结算单据，字段自包含）。
 *
 * <p>本域不提供 Feign client（CONTRACTS.md §3 末），DTO 随事件序列化或在对账/单据场景复用，
 * 金额一律 {@code Long} 分，禁止 double/float；优惠分摊使用 MoneyUtils.allocate 最大余数法。
 *
 * <p>包含：{@link com.shop.api.settlement.dto.ClearingBreakdown} 清算分账快照、
 * {@link com.shop.api.settlement.dto.MerchantStatementDTO} 商户结算单。
 *
 * <p>分账公式（design.md 7.2.2）：
 * <pre>
 * 用户实付金额 = 商品金额 + 运费 - 优惠总额
 * 商户应收 = (商品金额 - 商户承担优惠) × (1 - 佣金率) - 支付通道费 + 运费
 * 平台佣金 = (商品金额 - 商户承担优惠) × 佣金率
 * 平台技术服务费 = 0.5 元/笔
 * 营销账户支出 = 平台承担的优惠金额
 * </pre>
 * 优惠承担方：店铺券、商户承担满减 → 商户；平台券、积分抵现 → 平台（营销账户）。
 * 退款清算（design 7.5）：佣金按比例退回，支付通道费不退；先扣待结算款，不足扣保证金。
 */
package com.shop.api.settlement.dto;
