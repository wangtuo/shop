package com.shop.api.pay.enums;

/**
 * 支付单状态码值（design 6.3，CONTRACTS.md §4：支付单 10/20/30/40/50/60/70）。
 */
public enum PayStatuses {

    /** 待支付：支付单创建，等待用户支付 */
    WAIT(10, "待支付"),

    /** 支付中：用户正在支付，渠道处理中 */
    PAYING(20, "支付中"),

    /** 支付成功：资金已到账，以渠道回调验签通过为准 */
    SUCCESS(30, "支付成功"),

    /** 支付失败：余额不足、用户取消等 */
    FAIL(40, "支付失败"),

    /** 已关闭：超时未支付关闭 */
    CLOSED(50, "已关闭"),

    /** 退款中：部分/全额退款处理中 */
    REFUNDING(60, "退款中"),

    /** 已退款：全额退款完成 */
    REFUNDED(70, "已退款");

    private final int code;
    private final String desc;

    PayStatuses(int code, String desc) {
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
     * 按码值解析支付单状态，码值为空或未知时抛出 IllegalArgumentException。
     */
    public static PayStatuses of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("支付单状态码值不能为空");
        }
        for (PayStatuses value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知支付单状态码值: " + code);
    }
}
