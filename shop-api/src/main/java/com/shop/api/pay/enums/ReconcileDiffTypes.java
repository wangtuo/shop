package com.shop.api.pay.enums;

/**
 * T+1 对账差异类型码值（design 6.5 对账机制）。
 */
public enum ReconcileDiffTypes {

    /** 长款：渠道账单有、系统无对应支付单（疑似漏单），处理：核实后补单、补发货 */
    LONG(1, "长款"),

    /** 短款：系统有支付成功单、渠道账单无（疑似重复记账），处理：核实支付状态，未支付则关闭订单 */
    SHORT(2, "短款"),

    /** 金额不符：两边都有单据但金额不一致，处理：以渠道为准调整系统金额并挂账 */
    AMOUNT_MISMATCH(3, "金额不符");

    private final int code;
    private final String desc;

    ReconcileDiffTypes(int code, String desc) {
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
     * 按码值解析对账差异类型，码值为空或未知时抛出 IllegalArgumentException。
     */
    public static ReconcileDiffTypes of(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("对账差异类型码值不能为空");
        }
        for (ReconcileDiffTypes value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知对账差异类型码值: " + code);
    }
}
