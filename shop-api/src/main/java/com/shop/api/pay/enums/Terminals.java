package com.shop.api.pay.enums;

/**
 * 支付发起终端码值（design 6.1 各支付方式支持终端）。
 */
public enum Terminals {

    /** APP 原生端 */
    APP(1, "APP"),

    /** 移动 H5 页面 */
    H5(2, "H5"),

    /** 小程序（微信小程序等） */
    MINI_APP(3, "小程序"),

    /** PC 网页端 */
    PC(4, "PC");

    private final int code;
    private final String desc;

    Terminals(int code, String desc) {
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
     * 按码值解析终端，码值为空或未知时抛出 IllegalArgumentException。
     */
    public static Terminals of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("终端码值不能为空");
        }
        for (Terminals value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知终端码值: " + code);
    }
}
