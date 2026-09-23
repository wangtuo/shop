package com.shop.api.product.enums;

import java.util.Arrays;

/**
 * 售后回库原因，决定退货是否重新进入可售库存（design 3.3 / CONTRACTS.md §5）。
 */
public enum StockReturnReasons {

    /** 买家责任（非质量问题）：退回可售库存 */
    BUYER(1),
    /** 质量问题：不入可售库存，入残次品库 */
    QUALITY(2),
    /** 换货：退回可售库存，换出品按新订单锁定/扣减 */
    EXCHANGE(3);

    private final int code;

    StockReturnReasons(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按原因码解析枚举，非法编码抛出 IllegalArgumentException。
     */
    public static StockReturnReasons fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("回库原因码不能为空");
        }
        return Arrays.stream(values())
                .filter(e -> e.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("非法回库原因码: " + code));
    }
}
