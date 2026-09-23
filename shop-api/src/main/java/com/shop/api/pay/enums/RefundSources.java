package com.shop.api.pay.enums;

/**
 * 退款来源码值。
 */
public enum RefundSources {

    /** 售后退款：仅退款 / 退货退款 / 换货转退款等售后流程触发（design 第八章） */
    AFTERSALE(1, "售后退款"),

    /** 价保退款：价保期内降价补差，直接退回原支付路径（design 8.8） */
    PRICE_PROTECT(2, "价保退款"),

    /** 清算冲正：清算/对账环节发起的资金冲正（design 6.5、7.5） */
    CLEARING(3, "清算冲正");

    private final int code;
    private final String desc;

    RefundSources(int code, String desc) {
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
     * 按码值解析退款来源，码值为空或未知时抛出 IllegalArgumentException。
     */
    public static RefundSources of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("退款来源码值不能为空");
        }
        for (RefundSources value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知退款来源码值: " + code);
    }
}
