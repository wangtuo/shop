package com.shop.settlement.enums;

/** 保证金流水类型：10 缴费 20 退款扣赔 30 违规罚款 40 清退退还。 */
public final class DepositLogTypes {

    public static final int PAY = 10;
    public static final int REFUND_COMPENSATE = 20;
    public static final int FINE = 30;
    public static final int RESIGN_REFUND = 40;

    private DepositLogTypes() {
    }
}
