package com.shop.api.product.enums;

import java.util.Arrays;

/**
 * 库存类型，对应 CONTRACTS.md §4 与 design 3.3.1。
 */
public enum StockTypes {

    /** 普通库存：下单时锁定 */
    NORMAL(1),
    /** 预售库存：支付后扣减 */
    PRESALE(2),
    /** 秒杀库存：独立秒杀库存 */
    SECKILL(3),
    /** 拼团库存：拼团专用库存 */
    GROUPBUY(4);

    private final int code;

    StockTypes(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按类型码解析枚举，非法编码抛出 IllegalArgumentException。
     */
    public static StockTypes fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("库存类型码不能为空");
        }
        return Arrays.stream(values())
                .filter(e -> e.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("非法库存类型码: " + code));
    }
}
