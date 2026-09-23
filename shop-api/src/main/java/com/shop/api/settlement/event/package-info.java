/**
 * 清算域对外事件契约（RocketMQ 事件体，均继承 {@link com.shop.common.model.BaseEvent}）。
 *
 * <p>清算 / 售后两域不提供 Feign，跨域协作完全事件驱动（CONTRACTS.md §3 末、§5）。
 *
 * <p>事件链路：
 * <ul>
 *     <li>消费 ORDER_PAID → 按 design 7.2.2 分账公式登记待清算 →
 *     发 {@link com.shop.api.settlement.event.ClearingRegisteredEvent}
 *     （Topic：CLEARING_REGISTER，阶段 WAIT_CLEAR）；</li>
 *     <li>消费 ORDER_CONFIRMED 生成待结算；按商户等级周期
 *     （S T+1 / A T+7 / B T+15 / C T+30）或 ORDER_COMPLETED（B 级售后期结束）到期 →
 *     发 {@link com.shop.api.settlement.event.SettlementCompletedEvent}
 *     （Topic：CLEARING_SETTLE，转 SETTLED 可提现）；</li>
 *     <li>消费 REFUND_SUCCESS 后内部冲正（不对外发 CLEARING_REVERSE，CONTRACTS.md §5）：
 *     佣金按比例退回、通道费不退、先扣待结算款不足扣保证金（design 7.5）；</li>
 *     <li>提现终态发 {@link com.shop.api.settlement.event.WithdrawResultEvent}；
 *     保证金低于 50% 发 {@link com.shop.api.settlement.event.DepositAlertEvent}。</li>
 * </ul>
 *
 * <p>提现规则（design 7.4）：最低 100 元、单日上限 50 万元、每月前 3 次免费、
 * 超出 0.1% 且单笔最低 2 元。保证金规则（design 7.6）：低于 50% 限提，
 * 清退后 90 天无售后纠纷退还。消费者必须以 eventId 幂等去重。
 */
package com.shop.api.settlement.event;
