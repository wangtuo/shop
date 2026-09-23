package com.shop.api.aftersale.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 售后单明细行消息（订单维度的售后拆行）。
 *
 * <p>金额单位统一为「分」（{@code Long}）。一行对应一个订单明细（orderItem）的售后申请，
 * 同一订单明细累计退款不得超过其实付金额，且同一时刻只能有一笔进行中的售后
 * （design 8.3.2）。
 *
 * <p>退款金额按 design 8.4 计算：可退金额 = 实付金额（含分摊运费）- 已退金额，
 * 明细行金额已按优惠 / 积分 / 运费分摊后的结果填写；优惠券不退，积分按退款比例退回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AftersaleItemMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单明细 ID */
    private Long orderItemId;

    /** SKU ID */
    private Long skuId;

    /** 本次售后数量 */
    private Integer qty;

    /** 本行退款金额（分，含分摊优惠 / 积分 / 运费后的实付口径） */
    private Long refundFen;
}
