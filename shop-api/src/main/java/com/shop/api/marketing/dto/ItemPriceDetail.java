package com.shop.api.marketing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * SKU 级价格分摊明细（design.md 4.2.2）。售后退款以本行 {@link #paidFen}（含分摊运费）为基数，
 * 优惠券不退、积分按退款比例退回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemPriceDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SKU ID */
    private Long skuId;

    /** 数量 */
    private Integer qty;

    /** 原价小计：salePriceFen × qty（分） */
    private Long originalFen;

    /** 商品级优惠分摊：限时折扣/秒杀（分） */
    private Long productPromoFen;

    /** 店铺级优惠分摊：满减/满折/第N件（分） */
    private Long shopPromoFen;

    /** 三类券（品类/店铺/平台）优惠分摊合计（分） */
    private Long couponAllocFen;

    /** 积分抵现分摊（分） */
    private Long pointsAllocFen;

    /** 运费分摊（免邮券抵扣前的应付运费按行分摊，分） */
    private Long freightAllocFen;

    /** 该明细实付金额（分）：原价小计 - 各层优惠分摊 + 运费分摊 */
    private Long paidFen;

    /** 是否满赠赠品：1 是（原价 0 实付）0 否 */
    private Integer giftFlag;
}
