package com.shop.api.pay.enums;

/**
 * 退款单状态码值（design 6.4，CONTRACTS.md §4：退款单 10/20/30/40/50）。
 */
public enum RefundStatuses {

    /** 待退款：退款单已创建，尚未提交渠道 */
    WAIT(10, "待退款"),

    /** 退款中：已提交渠道，等待渠道处理 */
    PROCESSING(20, "退款中"),

    /** 退款成功：渠道退款成功，资金已按原路退回 */
    SUCCESS(30, "退款成功"),

    /** 退款失败：渠道退款失败，可重试或人工处理 */
    FAIL(40, "退款失败"),

    /** 已冲正：退款异常被反向冲正（如清算冲正回滚） */
    REVERSED(50, "已冲正");

    private final int code;
    private final String desc;

    RefundStatuses(int code, String desc) {
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
     * 按码值解析退款单状态，码值为空或未知时抛出 IllegalArgumentException。
     */
    public static RefundStatuses of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("退款单状态码值不能为空");
        }
        for (RefundStatuses value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知退款单状态码值: " + code);
    }
}
