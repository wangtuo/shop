package com.shop.api.settlement.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 保证金预警事件（商户保证金余额触及阈值）。
 *
 * <p>规则来源：design.md 7.6 保证金机制：
 * <ul>
 *     <li>入驻保证金 1000-50000 元（按类目），用于赔付用户退款、违规罚款；</li>
 *     <li>余额低于应缴额 50% 时<b>限制提现</b>并通知商户补足；</li>
 *     <li>商户清退时，保证金在 90 天后且无售后纠纷方可退还。</li>
 * </ul>
 * 退款扣款顺序（design 7.5.2）：先扣待结算款，不足部分从保证金扣。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class DepositAlertEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 商户 ID */
    private Long merchantId;

    /**
     * R4-25 预警类型（实例级去重维度）：
     * FINE 罚款扣后跌破阈值 / CLAW 退款瀑布扣赔跌破阈值 /
     * HANG 清退无收款账户人工挂起 / FAIL 渠道打款失败。
     */
    private String alertType;

    /** R4-25 触发预警的业务单据号：FINE/FAIL/HANG=保证金流水 logNo，CLAW=退款单号 refundNo。 */
    private String refNo;

    /** 当前保证金余额，单位分 */
    private Long balanceFen;

    /** 预警阈值（单位分，通常为应缴保证金的 50%） */
    private Long thresholdFen;
}
