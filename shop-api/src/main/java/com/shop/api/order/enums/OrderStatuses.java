package com.shop.api.order.enums;

/**
 * 订单状态码值（CONTRACTS.md §4，design.md 5.2 订单状态流转）。
 *
 * <pre>
 * 待付款(10) --支付成功--> 待发货(20) --发货--> 待收货(30) --确认收货/超时--> 已完成(40)
 *   │--取消/超时--&gt; 已取消(50)
 *   待发货/待收货/已完成 --售后--> 退款中(60) / 退货退款中(61) / 换货中(62)
 * 售后终态 --&gt; 已关闭(70)
 * </pre>
 */
public final class OrderStatuses {

    /** 待付款：下单未支付，可支付、取消 */
    public static final int WAIT_PAY = 10;

    /** 待发货：已支付待发货，可申请退款、修改地址 */
    public static final int WAIT_SHIP = 20;

    /** 待收货：已发货在途，可确认收货、申请售后 */
    public static final int WAIT_RECEIVE = 30;

    /** 已完成：交易完成，15 天内可申请售后、评价 */
    public static final int COMPLETED = 40;

    /** 已取消：用户/超时/商家取消，未支付 */
    public static final int CANCELLED = 50;

    /** 退款中：仅退款处理中 */
    public static final int REFUNDING = 60;

    /** 退货退款中：退货处理中 */
    public static final int RETURN_REFUNDING = 61;

    /** 换货中：换货处理中 */
    public static final int EXCHANGING = 62;

    /** 已关闭：售后完成/订单关闭 */
    public static final int CLOSED = 70;

    private OrderStatuses() {
    }
}
