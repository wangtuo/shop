package com.shop.api.product.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * SKU（标准产品单元下的最小可售单元）对外契约。
 *
 * <p>金额字段一律为 Long 分（CONTRACTS.md §2.2）；库存三量
 * {@code availableStock / lockedStock / occupiedStock} 对应 design 3.3 的
 * 可售 / 锁定 / 占用库存。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkuDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** SKU ID */
    private Long skuId;

    /** 所属 SPU ID */
    private Long spuId;

    /** SKU 编码 */
    private String skuCode;

    /** 商家 ID */
    private Long merchantId;

    /** 店铺 ID */
    private Long shopId;

    /** SPU 名称（冗余，便于跨域展示） */
    private String spuName;

    /** SKU 名称 */
    private String skuName;

    /** 规格文本，如 "颜色:红色;尺码:M" */
    private String specText;

    /** SKU 主图 URL */
    private String image;

    /** 销售价（分），日常售价 */
    private Long salePriceFen;

    /** 市场价/吊牌价（分） */
    private Long marketPriceFen;

    /** 会员价（分） */
    private Long memberPriceFen;

    /** 促销价（分，活动期间） */
    private Long promotionPriceFen;

    /** 秒杀价（分，秒杀时段内） */
    private Long seckillPriceFen;

    /** 成本价（分） */
    private Long costPriceFen;

    /** 可售库存 */
    private Long availableStock;

    /** 锁定库存（下单未支付） */
    private Long lockedStock;

    /** 占用库存（已支付待发货） */
    private Long occupiedStock;

    /** 库存预警阈值，默认 10 */
    private Long warnThreshold;

    /** 商品状态，取值见 GoodsStatuses（0 草稿 … 7 已删除） */
    private Integer status;

    /** 三级类目 ID */
    private Long category3Id;

    /** 重量（克），用于运费计算 */
    private Integer weightGram;

    /** 体积（立方厘米），用于运费计算 */
    private Integer volumeCc;

    /** 商品条码（69 码） */
    private String barcode;

    /** 是否预售：0 否 1 是 */
    private Integer presaleFlag;

    /** 库存类型，取值见 StockTypes（1 普通 2 预售 3 秒杀 4 拼团） */
    private Integer stockType;
}
