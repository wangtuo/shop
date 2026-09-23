package com.shop.pay.feature.payment.enums;

/**
 * 支付场景：1 普通支付 2 组合支付 3 好友代付。
 *
 * <p>7 种支付方式见 {@link com.shop.api.pay.enums.PayMethods}；
 * 组合支付/好友代付是在支付方式之上的支付场景，明细落 t_pay_channel_flow。</p>
 */
public enum PayScene {
    NORMAL(1, "普通支付"),
    COMPOSITE(2, "组合支付"),
    FRIEND_PAY(3, "好友代付");

    private final int code;
    private final String desc;

    PayScene(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
