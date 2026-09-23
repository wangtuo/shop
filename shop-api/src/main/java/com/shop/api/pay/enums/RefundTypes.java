package com.shop.api.pay.enums;

/**
 * 退款类型码值（CONTRACTS.md §4、§5 refundType；design 6.4.3 部分退款）。
 */
public enum RefundTypes {

    /** 全额退款：退回支付单全部实付金额，完成后支付单置为已退款 */
    FULL(1, "全额退款"),

    /** 部分退款：按订单明细/差价退回部分金额，可多次申请，累计不超过实付金额 */
    PART(2, "部分退款");

    private final int code;
    private final String desc;

    RefundTypes(int code, String desc) {
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
     * 按码值解析退款类型，码值为空或未知时抛出 IllegalArgumentException。
     */
    public static RefundTypes of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("退款类型码值不能为空");
        }
        for (RefundTypes value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知退款类型码值: " + code);
    }
}
