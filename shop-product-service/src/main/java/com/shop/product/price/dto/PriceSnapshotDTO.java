package com.shop.product.price.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * SKU 价格 + 库存聚合快照（内部/HTTP 共用）。
 *
 * <p>本域只做“当前用户可享受最低价”的静态选择：促销价与秒杀价互斥、不与其他促销叠加的
 * 判定由营销域在下单试算时完成；本快照供营销域/订单域取数与落单价格快照使用。</p>
 */
@Data
@Builder
public class PriceSnapshotDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 价格类型：1 销售价 2 会员价 3 促销价 4 秒杀价 */
    public static final int TYPE_SALE = 1;
    public static final int TYPE_MEMBER = 2;
    public static final int TYPE_PROMOTION = 3;
    public static final int TYPE_SECKILL = 4;

    private Long skuId;
    private Long spuId;
    private Long merchantId;

    /** 原价/吊牌价 */
    private Long marketPriceFen;
    /** 销售价 */
    private Long salePriceFen;
    /** 会员价 */
    private Long memberPriceFen;
    /** 促销价 */
    private Long promotionPriceFen;
    /** 秒杀价 */
    private Long seckillPriceFen;

    /** 当前用户可享受最低价 */
    private Long finalPriceFen;
    /** 最低价类型（1-4） */
    private Integer finalPriceType;

    /** 可售库存 */
    private Long availableStock;
    /** 锁定库存 */
    private Long lockedStock;
    /** 占用库存 */
    private Long occupiedStock;
    /** 预警阈值 */
    private Long warnThreshold;
    /** 商品状态 0-7 */
    private Integer status;
    /** 当前状态+库存是否可售 */
    private Boolean saleable;
}
