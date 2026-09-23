package com.shop.api.settlement.enums;

/**
 * 商户等级与结算 / 提现时效。
 *
 * <p>规则来源：design.md 7.3.2 结算周期。
 *
 * <table>
 *   <caption>等级时效表</caption>
 *   <tr><th>等级</th><th>结算周期</th><th>提现到账</th></tr>
 *   <tr><td>S（头部）</td><td>T+1（收货次日结算）</td><td>T+0</td></tr>
 *   <tr><td>A</td><td>T+7（收货后 7 天结算）</td><td>T+1</td></tr>
 *   <tr><td>B</td><td>T+15（售后期结束结算）</td><td>T+1</td></tr>
 *   <tr><td>C（新商户）</td><td>T+30</td><td>T+3</td></tr>
 * </table>
 */
public final class MerchantLevels {

    /** S 级（头部商户）：T+1 结算，提现 T+0 到账 */
    public static final int S = 0;

    /** A 级：T+7 结算，提现 T+1 到账 */
    public static final int A = 1;

    /** B 级：T+15（售后期结束）结算，提现 T+1 到账 */
    public static final int B = 2;

    /** C 级（新商户）：T+30 结算，提现 T+3 到账 */
    public static final int C = 3;

    private MerchantLevels() {
    }
}
