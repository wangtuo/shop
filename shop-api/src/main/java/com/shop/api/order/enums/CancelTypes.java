package com.shop.api.order.enums;

/**
 * 订单取消类型码值（CONTRACTS.md §5 ORDER_CANCELLED 事件 cancelType）。
 */
public final class CancelTypes {

    /** 用户主动取消 */
    public static final int USER = 1;

    /** 支付超时，系统自动取消 */
    public static final int TIMEOUT = 2;

    /** 商家取消（缺货等原因） */
    public static final int MERCHANT = 3;

    /** 拼团失败，系统自动取消/转退款 */
    public static final int GROUPBUY_FAIL = 4;

    private CancelTypes() {
    }
}
