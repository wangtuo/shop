package com.shop.api.pay.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 支付成功事件（Topic ORDER_PAID，CONTRACTS.md §5）。
 *
 * <p>由支付域在第三方支付回调验签通过、支付单幂等落库后发出（design 6.2：回调必须验签 +
 * 幂等，支付域只做验签 / 幂等 / 落单，不直接调用他域）。消费者：</p>
 * <ul>
 *     <li>order：订单 待付款 → 待发货；</li>
 *     <li>product：锁定库存 → 占用库存（TCC-confirm）；</li>
 *     <li>marketing：核销优惠券 / 秒杀扣减；</li>
 *     <li>user：扣冻结积分、发放积分与成长值；</li>
 *     <li>settlement：登记待清算。</li>
 * </ul>
 *
 * <p>各消费者必须以 eventId / payNo 幂等消费。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PaymentSucceededEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 支付单号（{@code P}+17 位） */
    private String payNo;

    /** 业务订单号 */
    private String orderNo;

    /** 付款用户 ID */
    private Long userId;

    /** 支付方式，取值见 com.shop.api.pay.enums.PayMethods */
    private Integer payMethod;

    /** 实付金额，单位：分 */
    private Long amountFen;

    /** 渠道交易流水号 */
    private String channelTransactionNo;

    /** 支付成功时间（渠道回调中的付款时间） */
    private LocalDateTime paidTime;

    /** 支付场景，取值见 com.shop.api.pay.enums.PayScenes（1 普通商品 2 组合 3 好友代付 4 保证金缴费）；null 视为普通商品（历史消息兼容）。scene=4 仅保证金缴费消费者处理 */
    private Integer payScene;

    /** 订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货；null 视为普通单（历史消息兼容） */
    private Integer orderType;

    /** 预售尾款阶段标记：true=尾款支付，false/null=定金/非预售；null 视为历史消息 */
    private Boolean presaleFinalStage;
}
