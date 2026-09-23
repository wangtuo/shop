/**
 * 清算域对外契约根包（对应服务 {@code shop-settlement-service}，schema {@code shop_settlement}，Redis DB 5）。
 *
 * <p>本包按 {@code dto / event / enums} 三个子包组织清算域对外能力——本域<b>不提供 Feign</b>，
 * 跨域协作完全事件驱动（CONTRACTS.md §3 末、§5）：
 * <ul>
 *   <li>{@code dto}：清算分账快照 ClearingBreakdown、商户结算单 MerchantStatementDTO，金额一律 Long（分）；</li>
 *   <li>{@code event}：清算登记（消费 ORDER_PAID 后发，Topic {@code CLEARING_REGISTER}）、
 *       结算完成转可提现（Topic {@code CLEARING_SETTLE}）、提现结果、保证金预警事件；</li>
 *   <li>{@code enums}：清算阶段（10/20/30/40）、商户等级（S/A/B/C 结算周期）、
 *       分账账户角色、费用项、提现状态码值。</li>
 * </ul>
 *
 * <p>分账公式见 design 7.2.2；退款冲正（消费 REFUND_SUCCESS 内部完成，不对外发 CLEARING_REVERSE）：
 * 佣金按比例退回、通道费不退、先扣待结算款不足扣保证金（design 7.5）。
 *
 * <p>硬约束：本包内任何类禁止 import 其他 api 域包，只允许引用 {@code com.shop.common}；码值以 CONTRACTS.md §4 为准。
 */
package com.shop.api.settlement;
