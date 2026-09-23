package com.shop.settlement.clearing.enums;

/**
 * 退款清算冲正状态（t_sett_clearing_reverse.status）。
 */
public final class ReverseStatuses {

    /** 商户承担部分已按瀑布全额扣回（待结算 + 可提现 + 保证金） */
    public static final int FULLY_DEDUCTED = 1;
    /** 三档合计仍不足，已扣尽三档余额，缺口挂起待追讨（消息正常 ACK，不无限重试） */
    public static final int PARTIAL_SUSPENDED = 2;

    private ReverseStatuses() {
    }
}
