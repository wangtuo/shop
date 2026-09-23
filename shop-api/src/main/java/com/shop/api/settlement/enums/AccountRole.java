package com.shop.api.settlement.enums;

/**
 * 清算账户角色（分账资金去向）。
 *
 * <p>规则来源：design.md 7.1.1 角色与账户、7.2.2 分账公式。
 *
 * <ul>
 *     <li>{@link #PLATFORM} 平台收入账户：收取佣金、技术服务费；</li>
 *     <li>{@link #MERCHANT} 商户结算账户：货款收入，冻结期满后可提现；</li>
 *     <li>{@link #USER_BALANCE} 用户余额账户：充值、退款入账；</li>
 *     <li>{@link #MARKETING} 营销补贴账户：平台承担优惠（平台券、积分抵现等）的资金来源。</li>
 * </ul>
 */
public final class AccountRole {

    /** 平台收入账户（佣金、技术服务费） */
    public static final int PLATFORM = 1;

    /** 商户结算账户（货款收入，可提现） */
    public static final int MERCHANT = 2;

    /** 用户余额账户（充值、退款） */
    public static final int USER_BALANCE = 3;

    /** 营销补贴账户（平台承担优惠的资金来源） */
    public static final int MARKETING = 4;

    private AccountRole() {
    }
}
