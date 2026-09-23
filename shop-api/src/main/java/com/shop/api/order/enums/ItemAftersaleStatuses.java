package com.shop.api.order.enums;

/**
 * 订单明细售后状态码值（订单域内部明细粒度状态）。
 *
 * <p>与售后域的售后单状态相互独立：订单明细据此控制再次发起售后、分账冲正等行为。
 */
public final class ItemAftersaleStatuses {

    /** 无售后 */
    public static final int NONE = 0;

    /** 售后申请中（待商家审核） */
    public static final int APPLYING = 1;

    /** 退款中（仅退款） */
    public static final int REFUNDING = 2;

    /** 已退款 */
    public static final int REFUNDED = 3;

    /** 退货中（退货退款，等待买家退货/商家收货） */
    public static final int RETURNING = 4;

    /** 换货中 */
    public static final int EXCHANGING = 5;

    /** 售后已完成 */
    public static final int FINISHED = 6;

    private ItemAftersaleStatuses() {
    }
}
