package com.shop.api.order.enums;

/**
 * 订单类型码值（CONTRACTS.md §4：1 普通 2 秒杀 3 拼团 4 预售 5 换货）。
 *
 * <p>两位业务类型码用于订单号段（CONTRACTS.md §6、design.md 5.1.2）。
 */
public final class OrderTypes {

    /** 普通订单 */
    public static final int NORMAL = 1;

    /** 秒杀订单 */
    public static final int SECKILL = 2;

    /** 拼团订单 */
    public static final int GROUPBUY = 3;

    /** 预售订单 */
    public static final int PRESALE = 4;

    /** 换货订单 */
    public static final int EXCHANGE = 5;

    private OrderTypes() {
    }

    /**
     * 返回订单号使用的两位业务类型码：01 普通 / 02 秒杀 / 03 拼团 / 04 预售 / 05 换货。
     *
     * @param type 订单类型
     * @return 两位字符串，如 {@code "01"}
     * @throws IllegalArgumentException 未知类型时抛出
     */
    public static String bizTypeCodeOf(Integer type) {
        if (type == null) {
            throw new IllegalArgumentException("订单类型不能为空");
        }
        return switch (type) {
            case NORMAL -> "01";
            case SECKILL -> "02";
            case GROUPBUY -> "03";
            case PRESALE -> "04";
            case EXCHANGE -> "05";
            default -> throw new IllegalArgumentException("未知订单类型: " + type);
        };
    }
}
