package com.shop.aftersale.aftersale.enums;

/**
 * 售后域内部码值（对外码值使用 com.shop.api.aftersale.enums.*）。
 */
public final class AftersaleCodes {

    private AftersaleCodes() {
    }

    // ---- 操作方（状态日志） ----
    public static final int ROLE_USER = 1;
    public static final int ROLE_MERCHANT = 2;
    public static final int ROLE_PLATFORM = 3;
    public static final int ROLE_SYSTEM = 4;

    // ---- 平台介入单状态 ----
    public static final int DISPUTE_EVIDENCING = 10;
    public static final int DISPUTE_WAIT_ARBITRATE = 20;
    public static final int DISPUTE_DONE = 30;

    // ---- 举证方 ----
    public static final int SIDE_BUYER = 1;
    public static final int SIDE_MERCHANT = 2;

    // ---- 凭证类型 ----
    public static final int EVIDENCE_IMAGE = 1;
    public static final int EVIDENCE_VIDEO = 2;
    public static final int EVIDENCE_TEXT = 3;

    // ---- 退款单状态（与 pay 域 RefundStatuses 对齐） ----
    public static final int REFUND_WAIT = 10;
    public static final int REFUND_PROCESSING = 20;
    public static final int REFUND_SUCCESS = 30;
    public static final int REFUND_FAIL = 40;

    // ---- 运费险状态 ----
    public static final int INSURANCE_WAIT = 10;
    public static final int INSURANCE_PAID = 20;
    public static final int INSURANCE_INVALID = 30;

    // ---- 价保记录状态 ----
    public static final int PRICE_TRIAL = 10;
    public static final int PRICE_APPLIED = 20;
    public static final int PRICE_PAID = 30;
    public static final int PRICE_INVALID = 40;

    // ---- 运费险规则（design 8.6） ----
    public static final long INSURANCE_CLAIM_CAP_FEN = 2500L;
    public static final long INSURANCE_PREMIUM_MIN_FEN = 50L;
    public static final long INSURANCE_PREMIUM_MAX_FEN = 500L;
    public static final long INSURANCE_CLAIM_DELAY_HOURS = 72L;

    // ---- 时限（天） ----
    public static final int AUDIT_TIMEOUT_DAYS = 2;
    public static final int RECEIVE_TIMEOUT_DAYS = 3;
    public static final int EXCHANGE_SHIP_TIMEOUT_DAYS = 5;
    public static final int FREE_AFTERSALE_DAYS = 15;
    public static final int DISPUTE_EVIDENCE_DAYS = 3;
    public static final int DISPUTE_ARBITRATE_WORKDAYS = 5;
    public static final int PRICE_PROTECT_DAYS = 7;
    public static final int PRICE_PROTECT_BIG_PROMO_DAYS = 30;
}
