package com.shop.product.goods.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品 SKU：五价（原价/销售价/会员价/促销价/秒杀价）+ 成本价；
 * 库存三栏（可售/锁定/占用）+ 残次仓 + 预警阈值。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_sku")
public class ProductSku extends BaseEntity {

    /** 所属 SPU ID */
    private Long spuId;

    /** 所属商家 ID */
    private Long merchantId;

    /** 所属店铺 ID */
    private Long shopId;

    /** SKU 编码 */
    private String skuCode;

    /** SKU 名称 */
    private String skuName;

    /** 规格组合文本，如 颜色:红色;尺码:M */
    private String specText;

    /** SKU 主图 URL */
    private String image;

    /** 商品条码（69 码） */
    private String barcode;

    /** 重量（克） */
    private Integer weightGram;

    /** 体积（立方厘米） */
    private Integer volumeCc;

    /** 三级类目 ID */
    private Long category3Id;

    /** 原价/吊牌价（分） */
    private Long marketPriceFen;

    /** 销售价（分） */
    private Long salePriceFen;

    /** 会员价（分） */
    private Long memberPriceFen;

    /** 促销价（分） */
    private Long promotionPriceFen;

    /** 秒杀价（分） */
    private Long seckillPriceFen;

    /** 成本价（分） */
    private Long costPriceFen;

    /** 可售库存 */
    private Long availableStock;

    /** 锁定库存（下单未支付） */
    private Long lockedStock;

    /** 占用库存（已支付待发货） */
    private Long occupiedStock;

    /** 预售库存数量（定金支付后从此池扣减并入占用，B5） */
    private Long presaleStock;

    /** 残次库存（质量问题退货） */
    private Long defectStock;

    /** 预警阈值，默认 10 */
    private Long warnThreshold;

    /** R4-25 低库存预警武装标记：0未发 1已发；可售恢复阈值以上自动 CAS 复位 */
    private Integer lowStockAlerted;

    /** 是否预售：0 否 1 是 */
    private Integer presaleFlag;

    /** 库存类型：1 普通 2 预售 3 秒杀 4 拼团 */
    private Integer stockType;

    /** 商品状态（随 SPU 0-7） */
    private Integer status;

    /** 乐观锁版本 */
    @Version
    private Integer version;
}
