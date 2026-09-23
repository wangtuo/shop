package com.shop.settlement.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分账计算输入（design 7.2.2）。金额单位：分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SplitRequest {

    /** 商品金额（优惠前） */
    private long productAmountFen;
    /** 运费 */
    private long freightFen;
    /** 商户承担优惠（店铺券/店铺满减） */
    private long merchantBearDiscountFen;
    /** 平台承担优惠（平台券/积分抵现，营销账户出资） */
    private long platformBearDiscountFen;
    /** 类目佣金率（万分比，10% = 1000） */
    private int commissionRateBps;
    /**
     * 运费险保费（分）。随单一次性收取、平台保险收入、退款不退（B11）。
     * 仅透传与参与用户实付/资金恒等式，<b>不</b>计入佣金基数与通道费基数，
     * 也不能被优惠券/积分抵扣（order/营销侧已约束）。
     */
    private long insurancePremiumFen;
}
