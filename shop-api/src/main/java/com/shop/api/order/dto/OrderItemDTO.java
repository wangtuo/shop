package com.shop.api.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 订单明细 DTO（design.md 5.1.1 订单结构 - OrderItem）。
 *
 * <p>金额单位均为「分」；各项分摊金额按最大余数法分摊，与整单合计不差 1 分。
 * {@code aftersaleStatus} 取值见 {@code ItemAftersaleStatuses}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单明细 ID（雪花 ID） */
    private Long orderItemId;

    /** 所属订单号 */
    private String orderNo;

    /** SKU ID */
    private Long skuId;

    /** SPU ID */
    private Long spuId;

    /** 商户 ID */
    private Long merchantId;

    /** 店铺 ID */
    private Long shopId;

    /** SKU 名称（下单时快照） */
    private String skuName;

    /** 规格文本（下单时快照，如「红色 XL」） */
    private String specText;

    /** 商品主图 URL（下单时快照） */
    private String image;

    /** 成交单价（分） */
    private Long priceFen;

    /** 购买数量 */
    private Integer qty;

    /** 明细商品小计：单价 × 数量（分） */
    private Long itemTotalFen;

    /** 商品/店铺/平台优惠分摊到本明细的金额（分） */
    private Long discountAllocFen;

    /** 积分抵扣分摊到本明细的金额（分） */
    private Long pointsAllocFen;

    /** 运费分摊到本明细的金额（分） */
    private Long freightAllocFen;

    /** 明细实付金额（分） */
    private Long paidFen;

    /** 明细售后状态：0 无 1 申请中 2 退款中 3 已退款 4 退货中 5 换货中 6 已完成 */
    private Integer aftersaleStatus;
}
