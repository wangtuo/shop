package com.shop.user.profile.util;

/**
 * 用户隐私字段脱敏工具。
 */
public final class UserPrivacyUtils {

    private UserPrivacyUtils() {
    }

    /**
     * 手机号脱敏：保留前 3 后 4，如 13812345678 -> 138****5678。
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        int len = phone.length();
        return phone.substring(0, 3) + "*".repeat(len - 7) + phone.substring(len - 4);
    }
}
