package com.shop.api.product.enums;

import java.util.Arrays;

/**
 * 商品（SPU/SKU）状态，对应 CONTRACTS.md §4 与 design 3.2。
 */
public enum GoodsStatuses {

    /** 草稿：编辑中 */
    DRAFT(0),
    /** 待审核：提交审核 */
    PENDING_AUDIT(1),
    /** 审核拒绝 */
    AUDIT_REJECT(2),
    /** 已上架：正常销售 */
    ON_SALE(3),
    /** 已下架：手动下架 */
    OFF_SALE(4),
    /** 售罄：库存为 0 */
    SOLD_OUT(5),
    /** 违规下架：平台处罚 */
    VIOLATION_OFF(6),
    /** 已删除：逻辑删除 */
    DELETED(7);

    private final int code;

    GoodsStatuses(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 按状态码解析枚举，非法编码抛出 IllegalArgumentException。
     */
    public static GoodsStatuses fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("商品状态码不能为空");
        }
        return Arrays.stream(values())
                .filter(e -> e.code == code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("非法商品状态码: " + code));
    }
}
