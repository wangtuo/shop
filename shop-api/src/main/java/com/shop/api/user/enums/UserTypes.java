package com.shop.api.user.enums;

/**
 * 用户类型常量。
 *
 * <p>规则来源：design.md 2.1.1 用户类型、CONTRACTS.md §4。
 */
public final class UserTypes {

    /** 游客（未登录） */
    public static final int VISITOR = -1;

    /** 普通用户（C 端买家） */
    public static final int NORMAL = 0;

    /** 商户用户（商家运营/管理员） */
    public static final int MERCHANT = 1;

    /** 平台运营（平台管理员） */
    public static final int PLATFORM = 2;

    private UserTypes() {
    }
}
