package com.shop.api.aftersale.enums;

/**
 * 售后责任方（决定退货运费承担与库存去向）。
 *
 * <p>规则来源：design.md 8.4 退款金额计算-运费承担、8.6 运费险。
 *
 * <ul>
 *     <li>{@link #MERCHANT} 商家责任（质量问题、发错货等）：商家承担运费，
 *     退货回可售 / 残次按品况判定；</li>
 *     <li>{@link #BUYER} 买家责任（7 天无理由、不喜欢等）：买家承担运费，
 *     商品正常回可售库存；</li>
 *     <li>{@link #INSURANCE} 运费险：用户下单购买运费险后，退货退款场景由保险公司
 *     赔付退货运费，<b>最高 25 元</b>；退款成功后 <b>72 小时</b>内自动理赔到用户账户，
 *     同一订单<b>只能理赔一次</b>（design 8.6）。</li>
 * </ul>
 */
public final class ResponsibilitySide {

    /** 商家责任：质量问题、发错货等，商家承担退货运费 */
    public static final int MERCHANT = 1;

    /** 买家责任：7 天无理由、不喜欢等，买家承担退货运费 */
    public static final int BUYER = 2;

    /** 运费险：保险公司赔付退货运费（最高 25 元，72 小时理赔，每单一次） */
    public static final int INSURANCE = 3;

    private ResponsibilitySide() {
    }
}
