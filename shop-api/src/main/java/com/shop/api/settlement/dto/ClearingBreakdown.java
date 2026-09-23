package com.shop.api.settlement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 清算结果快照（分账明细），供清算单据与清算事件复用。
 *
 * <p>金额单位统一为「分」（{@code Long}），禁止使用 double/float。
 *
 * <p>规则来源：design.md 7.2.2 分账公式：
 * <pre>
 * 用户实付金额 = 商品金额 + 运费 - 优惠总额
 * 商户应收 = (商品金额 - 商户承担优惠) × (1 - 佣金率) - 支付通道费 + 运费
 * 平台佣金 = (商品金额 - 商户承担优惠) × 佣金率
 * 平台技术服务费 = 0.5 元/笔
 * 营销账户支出 = 平台承担的优惠金额
 * </pre>
 * 优惠承担方：店铺券 / 满减由商户承担；平台券、积分抵现由平台（营销账户）承担。
 *
 * <p>{@code stage} 取值见 {@code com.shop.api.settlement.enums.ClearingStages}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClearingBreakdown implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 清算单号（CL 前缀，CONTRACTS.md §6） */
    private String clearingNo;

    /** 业务订单号 */
    private String orderNo;

    /** 商户 ID（货款归属） */
    private Long merchantId;

    /** 商品金额（分，优惠前） */
    private Long productAmountFen;

    /** 商户承担优惠金额（分：店铺券、商户承担满减等） */
    private Long merchantBearDiscountFen;

    /** 平台承担优惠金额（分：平台券、积分抵现等，由营销账户支出） */
    private Long platformBearDiscountFen;

    /** 商户应收货款（分，已扣佣金与通道费、含运费） */
    private Long merchantReceivableFen;

    /** 平台佣金（分） */
    private Long platformCommissionFen;

    /** 技术服务费（分，0.5 元/笔或 0.1%） */
    private Long techFeeFen;

    /** 支付通道费（分，约 0.6%，商户承担，退款不退） */
    private Long channelFeeFen;

    /** 营销补贴金额（分，平台承担优惠部分） */
    private Long marketingSubsidyFen;

    /** 运费（分，结算时计入商户应收） */
    private Long freightFen;

    /** 运费险保费（分，清算快照透传保费，ClearingRegisteredEvent 自动携带；未购险为 0/null） */
    private Long insurancePremiumFen;

    /** 佣金费率（万分比，如 10% = 1000） */
    private Integer commissionRateBps;

    /** 清算阶段：见 ClearingStages（10 待清算 / 20 待结算 / 30 已结算 / 40 已冲正） */
    private Integer stage;
}
