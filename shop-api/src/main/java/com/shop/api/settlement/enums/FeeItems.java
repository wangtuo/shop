package com.shop.api.settlement.enums;

/**
 * 分账费用项。
 *
 * <p>规则来源：design.md 7.2.1 分账比例、7.2.2 分账公式、7.5.1 退款资金来源。
 *
 * <ul>
 *     <li>{@link #COMMISSION} 平台佣金：按类目费率 5%-15%，
 *     基数 =（商品金额 - 商户承担优惠），退款时按比例退回；</li>
 *     <li>{@link #TECH_FEE} 技术服务费：每笔 0.5 元（或按比例 0.1%）；</li>
 *     <li>{@link #CHANNEL_FEE} 支付通道费：按实际通道成本（约 0.6%），由商户承担，
 *     退款时<b>不退回</b>（已实际产生）。</li>
 * </ul>
 */
public final class FeeItems {

    /** 佣金：（商品金额 - 商户承担优惠）× 佣金率 */
    public static final int COMMISSION = 1;

    /** 技术服务费：0.5 元/笔（或 0.1%） */
    public static final int TECH_FEE = 2;

    /** 支付通道费：约 0.6%，商户承担，退款不退 */
    public static final int CHANNEL_FEE = 3;

    private FeeItems() {
    }
}
