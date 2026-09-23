package com.shop.settlement.clearing.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 退款冲正挂起缺口事件（P1-10）：商户承担部分按
 * 「待结算 → 可提现余额 → 保证金」瀑布扣尽后仍不足时，随冲正明细同事务登记到 outbox。
 *
 * <p>消费侧（当前为对账/告警链路）据此催缴或挂起追讨；t_sett_clearing_reverse.status=2
 * 且 shortfall_fen&gt;0 为持久化的可追踪记录，REFUND_SUCCESS 消息本身正常 ACK、不无限重试。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class RefundShortfallEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 商户 ID */
    private Long merchantId;
    /** 原订单号 */
    private String orderNo;
    /** 退款单号 */
    private String refundNo;
    /** 冲正流水号 */
    private String reverseNo;
    /** 本次应向商户扣回的总额（分） */
    private Long merchantPartFen;
    /** 已从待结算扣回（分） */
    private Long fromPendingFen;
    /** 已从可提现余额扣回（分） */
    private Long fromAvailableFen;
    /** 已从保证金扣回（分） */
    private Long fromDepositFen;
    /** 挂起待追讨缺口（分，= merchantPart - 三档已扣合计） */
    private Long shortfallFen;
}
