package com.shop.api.product.enums;

import java.util.Arrays;

/**
 * 库存锁定状态（库存流水/锁定记录状态），对应 CONTRACTS.md §4。
 *
 * <p>流转：锁定中(0) → 已扣减(1)（支付成功）；锁定中(0) → 已释放(2)（取消/超时）；
 * 已扣减(1) → 已回库(3)（售后退货）；已扣减(1) → 已出账(4)（发货出账，占用仓出账）；
 * 预售尾款违约定金扣减后 → 预售回补(5)（回补预售池，TRADE C34）。</p>
 */
public enum StockLockStatuses {

    /** 锁定中：TCC-try 已执行，可售转锁定 */
    LOCKED(0),
    /** 已扣减：TCC-confirm 已执行，锁定转占用 */
    DEDUCTED(1),
    /** 已释放：TCC-cancel 已执行，锁定转回可售 */
    RELEASED(2),
    /** 已回库：售后退货入库 */
    RETURNED(3),
    /** 已出账：发货出账（占用仓出账，库存流水状态码 4，TRADE C34） */
    SHIPPED_ACCOUNTED(4),
    /** 预售回补：定金扣减后尾款违约，回补预售池（库存流水状态码 5，TRADE C34） */
    PRESALE_RECOVER(5);

    private final int code;

    StockLockStatuses(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按状态码解析枚举，非法编码抛出 IllegalArgumentException。
     */
    public static StockLockStatuses fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("库存锁定状态码不能为空");
        }
        return Arrays.stream(values())
                .filter(e -> e.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("非法库存锁定状态码: " + code));
    }
}
