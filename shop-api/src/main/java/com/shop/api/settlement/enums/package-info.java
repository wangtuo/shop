/**
 * 清算域对外码值契约（枚举常量持有类）。
 *
 * <p>本域不提供 Feign client，对外能力完全通过 MQ 事件暴露（CONTRACTS.md §3 末、§5）。
 *
 * <p>包含：{@link com.shop.api.settlement.enums.ClearingStages} 清算阶段
 * （10 待清算 / 20 待结算 / 30 已结算 / 40 已冲正）、
 * {@link com.shop.api.settlement.enums.MerchantLevels} 商户等级与结算周期、
 * {@link com.shop.api.settlement.enums.AccountRole} 分账账户角色、
 * {@link com.shop.api.settlement.enums.WithdrawStatuses} 提现状态、
 * {@link com.shop.api.settlement.enums.FeeItems} 分账费用项。
 *
 * <p>核心业务规则（design.md）：
 * <ul>
 *     <li><b>7.2.2 分账公式</b>：商户应收 =（商品金额 - 商户承担优惠）×（1 - 佣金率）
 *     - 支付通道费 + 运费；平台佣金 =（商品金额 - 商户承担优惠）× 佣金率；
 *     技术服务费 0.5 元/笔；营销账户支出 = 平台承担优惠；</li>
 *     <li><b>7.3.2 结算周期</b>：S 级 T+1 / 提现 T+0，A 级 T+7 / T+1，
 *     B 级 T+15（售后期结束）/ T+1，C 级 T+30 / T+3；</li>
 *     <li><b>7.4 提现规则</b>：最低提现 100 元，单日上限 50 万元；
 *     每自然月前 3 次免手续费，超出按 0.1% 收取且单笔最低 2 元；</li>
 *     <li><b>7.6 保证金</b>：余额低于应缴额 50% 限制提现并预警；
 *     商户清退后 90 天无售后纠纷方可退还。</li>
 * </ul>
 */
package com.shop.api.settlement.enums;
