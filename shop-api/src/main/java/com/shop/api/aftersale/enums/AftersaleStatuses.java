package com.shop.api.aftersale.enums;

/**
 * 售后单状态码值。
 *
 * <p>规则来源：CONTRACTS.md §4 售后状态；design.md 8.2 售后状态流转。
 *
 * <p>主干：10 待商家审核 → 20 待买家退货 → 30 商家收货中 → 40 退款中 → 50 已完成；
 * 换货支线：41 待换货发货 → 42 换货已发货 → 43 换货待收货 → 50 已完成；
 * 异常支线：55 已拒绝（待用户处理，可修改重提或申请平台介入）、
 * 80 平台介入中、90 已撤销。
 *
 * <p>商家审核超时（CONTRACTS.md §4；design 8.5）：仅退款 / 退货退款 / 换货
 * <b>2 天</b>未审核自动同意；商家确认收货 <b>3 天</b>超时自动确认并退款；
 * 换货发货 <b>5 天</b>超时自动转退款。
 */
public final class AftersaleStatuses {

    /** 待商家审核（2 天超时自动同意） */
    public static final int WAIT_MERCHANT_AUDIT = 10;

    /** 待买家退货（商家已同意，等待用户填写物流） */
    public static final int WAIT_BUYER_RETURN = 20;

    /** 商家收货中（3 天超时自动确认收货并退款） */
    public static final int MERCHANT_RECEIVING = 30;

    /** 退款中（已通知支付域发起退款） */
    public static final int REFUNDING = 40;

    /** 待换货发货（商家收货确认后，5 天超时自动转退款） */
    public static final int WAIT_EXCHANGE_SHIP = 41;

    /** 换货已发货（新商品已发出） */
    public static final int EXCHANGE_SHIPPED = 42;

    /** 换货待收货（等待用户签收新商品） */
    public static final int EXCHANGE_WAIT_RECEIVE = 43;

    /** 已完成（退款成功 / 换货签收 / 补发 / 价保补差到账） */
    public static final int FINISHED = 50;

    /** 已拒绝（待用户处理：修改重提或申请平台介入） */
    public static final int REJECTED = 55;

    /** 平台介入中（双方 3 天内举证，平台 5 个工作日内仲裁） */
    public static final int PLATFORM_INTERVENING = 80;

    /** 已撤销（用户主动撤销） */
    public static final int CANCELED = 90;

    private AftersaleStatuses() {
    }
}
