package com.shop.api.marketing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 价格试算结果。
 *
 * <p>金额恒等式（分）：
 * {@code payFen = originalProductFen - productPromoFen - shopPromoFen
 * - categoryCouponFen - shopCouponFen - platformCouponFen - pointsDeductFen + freightFen
 * + insurancePremiumFen}，
 * 其中 freightFen 已扣除 freightCouponFen（免邮券）。各层优惠均按 design.md 4.2.2 分摊到 itemDetails，
 * 整单层金额与明细分摊合计不差 1 分（最大余数法）。
 *
 * <p>叠加顺序（design.md 4.2.1）：商品原价 → 1 限时折扣/秒杀 → 2 满减/满折/第N件 →
 * 3 品类券 → 4 店铺券 → 5 平台通用券 → 6 积分抵现 → 实付。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceCalcResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品原价总额（Σ 单行原价小计，分） */
    private Long originalProductFen;

    /** 第 1 层商品级优惠：限时折扣/秒杀（分） */
    private Long productPromoFen;

    /** 第 2 层店铺级优惠：满减/满折/第N件（分） */
    private Long shopPromoFen;

    /** 第 3 层品类券优惠（分） */
    private Long categoryCouponFen;

    /** 第 4 层店铺券优惠（分） */
    private Long shopCouponFen;

    /** 第 5 层平台通用券优惠（分） */
    private Long platformCouponFen;

    /** 免邮券抵扣的运费金额（分） */
    private Long freightCouponFen;

    /** 第 6 层积分抵现金额（分，封顶商品金额 50%） */
    private Long pointsDeductFen;

    /** 最终应付运费（分，= 原始运费 - 免邮券抵扣） */
    private Long freightFen;

    /** 运费险保费（分，未购险为 0/null） */
    private Long insurancePremiumFen;

    /** 拼团团长价应付总额（分，仅拼团单且当前用户为团长时非空；团员/非拼团为 null） */
    private Long leaderPriceFen;

    /** 团长标记：true=团长（拼团单按团长价试算），false=团员，null=非拼团/历史调用 */
    private Boolean leaderFlag;

    /** 整单应付金额（分） */
    private Long payFen;

    /** SKU 级分摊明细（售后退款依据） */
    @Builder.Default
    private List<ItemPriceDetail> itemDetails = new ArrayList<>();

    /** 本次试算实际生效的用户券 ID 列表（下单锁定时回传） */
    @Builder.Default
    private List<Long> usedUserCouponIds = new ArrayList<>();

    /** 满赠命中的赠品 SKU ID 列表 */
    @Builder.Default
    private List<Long> giftSkuIds = new ArrayList<>();

    /** 价格快照 JSON（随订单落库；支付/售后均以此快照为准，保证事后可追溯） */
    private String snapshotJson;
}
