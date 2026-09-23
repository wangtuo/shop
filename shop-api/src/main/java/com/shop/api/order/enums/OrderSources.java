package com.shop.api.order.enums;

/**
 * 订单来源码值（design.md 5.1.1：APP / H5 / 小程序 / PC）。
 */
public final class OrderSources {

    /** APP 端 */
    public static final int APP = 1;

    /** H5 页面 */
    public static final int H5 = 2;

    /** 小程序 */
    public static final int MINI_APP = 3;

    /** PC 端 */
    public static final int PC = 4;

    private OrderSources() {
    }
}
