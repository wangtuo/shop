package com.shop.api.pay.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 退款成功事件（Topic REFUND_SUCCESS，CONTRACTS.md §5）。
 *
 * <p>由支付域在渠道退款成功（原路退回完成）并幂等更新退款单后发出（design 6.4：
 * 原路退回，混合支付按各支付方式实付占比分别退回）。消费者：</p>
 * <ul>
 *     <li>settlement：清算冲正（从商户待结算款/保证金按比例扣回，平台佣金、营销补贴退回）；</li>
 *     <li>user：积分按比例退回、退款入余额账户；</li>
 *     <li>order：回写订单/售后退款结果。</li>
 * </ul>
 *
 * <p>各消费者必须以 eventId / refundNo 幂等消费。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class RefundSucceededEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 退款单号（{@code R}+17 位） */
    private String refundNo;

    /** 原支付单号 */
    private String payNo;

    /** 原业务订单号 */
    private String orderNo;

    /** 关联售后单号（价保 / 清算冲正场景可为空） */
    private String aftersaleNo;

    /** 退款归属用户 ID（收款人） */
    private Long userId;

    /** 本次退款金额，单位：分 */
    private Long amountFen;

    /** 退款渠道/原支付方式，取值见 com.shop.api.pay.enums.PayMethods */
    private Integer payMethod;

    /** 退款类型，取值见 com.shop.api.pay.enums.RefundTypes（1 全额 2 部分） */
    private Integer refundType;

    /** 退款成功时间（渠道异步通知退款成功的时间） */
    private LocalDateTime refundTime;
}
