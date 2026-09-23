package com.shop.api.order.event;

import com.shop.common.model.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单确认收货事件（Topic ORDER_CONFIRMED，CONTRACTS.md §5）。
 *
 * <p>生产者：order（用户主动确认或发货后 10 天超时自动确认，design.md 5.3.3）；
 * 消费者：settlement（据各金额字段生成待结算单）。
 */
@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderConfirmedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    /** 订单号（18 位） */
    private String orderNo;

    /** 下单用户 ID */
    private Long userId;

    /** 商户 ID */
    private Long merchantId;

    /** 商品实付金额（分） */
    private Long productPayFen;

    /** 运费（分） */
    private Long freightFen;

    /** 店铺优惠金额（分） */
    private Long shopDiscountFen;

    /** 平台优惠券金额（分） */
    private Long platformCouponFen;

    /** 积分抵扣金额（分） */
    private Long pointsDeductFen;

    /** 订单总实付金额（分） */
    private Long totalPayFen;

    /** 是否购买运费险：0 否 1 是 */
    private Integer hasFreightInsurance;

    /** 运费险保费（分，未购险为 0/null） */
    private Long insurancePremiumFen;

    /** 订单明细 */
    @lombok.Builder.Default
    private List<OrderItemMessage> items = new ArrayList<>();
}
