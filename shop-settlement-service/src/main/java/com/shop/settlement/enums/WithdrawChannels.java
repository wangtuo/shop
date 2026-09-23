package com.shop.settlement.enums;

/** 提现渠道：1 银行卡 2 支付宝（design 7.4）。 */
public final class WithdrawChannels {

    public static final int BANK_CARD = 1;
    public static final int ALIPAY = 2;

    private WithdrawChannels() {
    }

    public static boolean valid(Integer channel) {
        return channel != null && (channel == BANK_CARD || channel == ALIPAY);
    }
}
