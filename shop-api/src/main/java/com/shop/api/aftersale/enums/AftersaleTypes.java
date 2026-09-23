package com.shop.api.aftersale.enums;

/**
 * 售后类型码值。
 *
 * <p>规则来源：CONTRACTS.md §4 售后类型；design.md 8.1 售后类型。
 *
 * <ul>
 *     <li>{@link #REFUND_ONLY} 仅退款：不退货只退钱（发货前 / 收货前均可申请）；</li>
 *     <li>{@link #RETURN_REFUND} 退货退款：退回商品后全额退款；</li>
 *     <li>{@link #EXCHANGE} 换货：退回原商品、换发新商品，不发生退款；</li>
 *     <li>{@link #RESHIP} 补发货：漏发 / 错发补发，不退不换；</li>
 *     <li>{@link #PRICE_PROTECT} 价保：降价补差（design 8.8：价保期下单后 7 天，
 *     大促 30 天；不含秒杀、拼团活动价；每单只能申请一次；差价退原支付路径）。</li>
 * </ul>
 */
public final class AftersaleTypes {

    /** 仅退款 */
    public static final int REFUND_ONLY = 1;

    /** 退货退款 */
    public static final int RETURN_REFUND = 2;

    /** 换货（出旧入新，不退款） */
    public static final int EXCHANGE = 3;

    /** 补发货（漏发 / 错发） */
    public static final int RESHIP = 4;

    /** 价格保护（降价补差，每单一次，价保期 7 天） */
    public static final int PRICE_PROTECT = 5;

    private AftersaleTypes() {
    }
}
