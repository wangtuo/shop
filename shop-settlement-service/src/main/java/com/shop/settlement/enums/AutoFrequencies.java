package com.shop.settlement.enums;

/** 自动提现频率：1 每日 2 每周（design 7.4）。 */
public final class AutoFrequencies {

    public static final int DAILY = 1;
    public static final int WEEKLY = 2;

    private AutoFrequencies() {
    }

    public static boolean valid(Integer frequency) {
        return frequency != null && (frequency == DAILY || frequency == WEEKLY);
    }
}
