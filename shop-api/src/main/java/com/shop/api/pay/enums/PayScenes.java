package com.shop.api.pay.enums;

/**
 * 支付场景码值（t_pay_order.pay_scene）。
 */
public final class PayScenes {

    /** 普通商品 */
    public static final int NORMAL = 1;

    /** 组合（拼团/活动组合单） */
    public static final int GROUP = 2;

    /** 好友代付 */
    public static final int FRIEND_PAY = 3;

    /** 保证金缴费 */
    public static final int DEPOSIT = 4;

    private PayScenes() {
    }
}
