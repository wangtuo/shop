package com.shop.api.pay.enums;

/**
 * 支付方式码值（design 6.1，CONTRACTS.md §4）。
 */
public enum PayMethods {

    /** 微信支付（全端，单笔限额 5 万，实时到账） */
    WECHAT(1, "微信支付"),

    /** 支付宝（全端，单笔限额 5 万，实时到账） */
    ALIPAY(2, "支付宝"),

    /** 余额支付（全端，受账户余额限制，实时到账） */
    BALANCE(3, "余额支付"),

    /** 银行卡支付（APP/H5，按银行限额，实时到账） */
    BANK_CARD(4, "银行卡支付"),

    /** 云闪付（APP/H5，单笔限额 5 万，实时到账） */
    UNIONPAY(5, "云闪付"),

    /** 花呗分期（APP，按额度，实时到账，退款恢复额度） */
    HUABEI(6, "花呗分期"),

    /** 白条支付（APP，按额度，实时到账，退款恢复额度） */
    BAITIAO(7, "白条支付");

    private final int code;
    private final String desc;

    PayMethods(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按码值解析支付方式，码值为空或未知时抛出 IllegalArgumentException。
     */
    public static PayMethods of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("支付方式码值不能为空");
        }
        for (PayMethods value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知支付方式码值: " + code);
    }
}
