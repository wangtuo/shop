package com.shop.api.settlement.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 结算完成事件（待结算冻结期满，转为可提现余额）。
 *
 * <p>事件链路（CONTRACTS.md §5）：
 * 确认收货后按商户等级周期（S T+1 / A T+7 / B T+15 售后期结束 / C T+30，design 7.3.2）
 * 到期结算，B 级商户在 ORDER_COMPLETED 时转可提现；结算完成发送本事件
 * （Topic：{@code CLEARING_SETTLE}），阶段由 {@code WAIT_SETTLE(20)} 转 {@code SETTLED(30)}。
 *
 * <p>结算后金额进入商户可提现余额，提现须满足 design 7.4 门槛（最低 100 元、单日 50 万上限）
 * 与 design 7.6 保证金约束（余额低于 50% 限提）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class SettlementCompletedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 结算单号（ST 前缀） */
    private String statementNo;

    /** 商户 ID */
    private Long merchantId;

    /** 本次结算（转可提现）金额，单位分 */
    private Long amountFen;

    /** 结算完成时间 */
    private LocalDateTime settleTime;
}
