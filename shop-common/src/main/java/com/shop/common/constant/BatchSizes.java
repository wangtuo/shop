package com.shop.common.constant;

/**
 * 批量入参的统一上限制值，供 Bean Validation（{@code @Size(max = BatchSizes.ITEMS_MAX)}）
 * 与各服务批处理逻辑共享，避免各处魔法数字不一致。
 *
 * <p>超限非法大报文由穿透执行改为入口 400（TRADE C35）。
 */
public final class BatchSizes {

    /** 明细行（库存 items 等）单次最大条数 */
    public static final int ITEMS_MAX = 100;

    /** 购物车 ID 列表单次最大条数 */
    public static final int CART_IDS_MAX = 100;

    /** SKU ID 列表单次最大条数 */
    public static final int SKUS_MAX = 100;

    /** 通用 IN 查询 ID 列表单次最大条数 */
    public static final int IN_IDS_MAX = 100;

    /** 图片等媒体 URL 列表单次最大条数 */
    public static final int IMAGES_MAX = 10;

    private BatchSizes() {
    }
}
