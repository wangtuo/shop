package com.shop.settlement.support;

/**
 * 商户/外部序列化脱敏（M-1）：账号仅露后 4 位，姓名仅留首字。
 */
public final class AccountMask {

    private static final String ACCOUNT_MASK_PREFIX = "**** **** **** ";

    private AccountMask() {
    }

    /**
     * 收款账号脱敏：{@code **** **** **** 1234}；不足 4 位整体打码；空白原样返回。
     */
    public static String maskAccount(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        String trimmed = plain.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return ACCOUNT_MASK_PREFIX + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 收款人姓名脱敏：保留首字（如 张** / 李*）；单字打码。
     */
    public static String maskName(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        if (plain.length() == 1) {
            return "*";
        }
        return plain.charAt(0) + "*".repeat(plain.length() - 1);
    }
}
