package com.shop.api.marketing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 价格试算/营销锁定的单个 SKU 行（字段自包含，分摊所需的商品/商家/店铺/类目属性冗余上送）。
 *
 * <p>规则来源：design.md 4.2.2 分摊规则——优惠按各行原价金额占比（salePriceFen × qty）最大余数法分摊，
 * 合计不差 1 分；售后按分摊后实付退款。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CalcItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SKU ID */
    @NotNull(message = "skuId 不能为空")
    private Long skuId;

    /** SPU ID */
    private Long spuId;

    /** 商户 ID（分账/店铺活动归属） */
    private Long merchantId;

    /** 店铺 ID（店铺满减/店铺券作用域） */
    private Long shopId;

    /** 三级类目 ID（品类券作用域） */
    private Long category3Id;

    /** 购买数量 */
    @NotNull(message = "qty 不能为空")
    @Min(value = 1, message = "qty 必须大于 0")
    private Integer qty;

    /** 当前销售单价（分）；秒杀/拼团/预售由订单域上送活动价 */
    @NotNull(message = "salePriceFen 不能为空")
    private Long salePriceFen;

    /** 活动成交价快照（分）：拼团团长价/砍价成交价；null 时引擎按 {@link #salePriceFen} 取价 */
    private Long activityPriceFen;
}
