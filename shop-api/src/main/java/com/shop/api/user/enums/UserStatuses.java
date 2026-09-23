package com.shop.api.user.enums;

/**
 * 用户账户状态常量。
 *
 * <p>规则来源：CONTRACTS.md §4 用户域状态约定。
 */
public final class UserStatuses {

    /** 正常 */
    public static final int NORMAL = 0;

    /** 冻结（违规/风控冻结，禁止交易） */
    public static final int FROZEN = 1;

    /** 已注销 */
    public static final int CANCELLED = 2;

    private UserStatuses() {
    }
}
