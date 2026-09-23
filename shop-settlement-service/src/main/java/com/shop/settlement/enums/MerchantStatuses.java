package com.shop.settlement.enums;

/** 商户状态：0 禁用 1 正常 2 清退中（90天观察期）3 已清退（保证金已退）。 */
public final class MerchantStatuses {

    public static final int DISABLED = 0;
    public static final int NORMAL = 1;
    public static final int RESIGNING = 2;
    public static final int RESIGNED = 3;

    private MerchantStatuses() {
    }
}
