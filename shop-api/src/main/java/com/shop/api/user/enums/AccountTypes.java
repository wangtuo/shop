package com.shop.api.user.enums;

/**
 * 用户账户类型常量。
 *
 * <p>规则来源：design.md 2.2.1 账户体系。
 */
public final class AccountTypes {

    /** 余额账户：充值、退款、消费，可提现 */
    public static final int BALANCE = 1;

    /** 赠金账户：活动赠送、补偿，不可提现 */
    public static final int GIFT = 2;

    /** 积分账户：积分获取/消耗，不可提现 */
    public static final int POINTS = 3;

    /** 优惠券账户：优惠券持有，不可提现 */
    public static final int COUPON = 4;

    private AccountTypes() {
    }
}
