package com.shop.settlement.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分账计算结果（design 7.2.2）。金额单位：分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SplitResult {

    /** 商品金额（优惠前） */
    private long productAmountFen;
    /** 运费 */
    private long freightFen;
    /** 商户承担优惠 */
    private long merchantBearDiscountFen;
    /** 平台承担优惠（=营销补贴） */
    private long platformBearDiscountFen;
    /** 用户实付 = 商品额 + 运费 - 商户承担优惠 - 平台承担优惠 */
    private long userPayFen;
    /** 商户应收货款（已扣佣金/通道费、含运费） */
    private long merchantReceivableFen;
    /** 平台佣金 =（商品额-商户承担优惠）× 佣金率 */
    private long platformCommissionFen;
    /** 技术服务费（50 分/笔） */
    private long techFeeFen;
    /** 支付通道费 = 商品额 × 60bps，商户承担 */
    private long channelFeeFen;
    /** 营销补贴 = 平台承担优惠 */
    private long marketingSubsidyFen;
    /**
     * 运费险保费（分）= SplitRequest 透传值。平台保险收入（流水类型 16），
     * 不参与佣金/通道费计算，退款不退。
     */
    private long insurancePremiumFen;
    /** 佣金费率（万分比） */
    private int commissionRateBps;
}
