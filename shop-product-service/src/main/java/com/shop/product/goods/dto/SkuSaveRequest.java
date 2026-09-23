package com.shop.product.goods.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * SPU 新增/编辑时的 SKU 行。skuId 为空表示新增 SKU。
 */
@Data
public class SkuSaveRequest implements Serializable {

    /** 已有 SKU ID（编辑时传入） */
    private Long skuId;

    /** SKU 编码（69 码等商家编码） */
    @NotBlank(message = "SKU 编码不能为空")
    @Size(max = 64)
    private String skuCode;

    /** SKU 名称 */
    @NotBlank(message = "SKU 名称不能为空")
    @Size(max = 180)
    private String skuName;

    /** 规格组合，如 颜色:红色;尺码:M */
    @Size(max = 255)
    private String specText;

    /** SKU 主图 */
    private String image;

    /** 条码 */
    @Size(max = 32)
    private String barcode;

    /** 重量（克） */
    @Min(value = 0, message = "重量不能为负")
    private Integer weightGram;

    /** 体积（立方厘米） */
    @Min(value = 0, message = "体积不能为负")
    private Integer volumeCc;

    /** 原价/吊牌价（分） */
    @NotNull(message = "原价不能为空")
    @Min(value = 0, message = "原价不能为负")
    private Long marketPriceFen;

    /** 销售价（分） */
    @NotNull(message = "销售价不能为空")
    @Min(value = 0, message = "销售价不能为负")
    private Long salePriceFen;

    /** 会员价（分），可空 */
    @Min(value = 0, message = "会员价不能为负")
    private Long memberPriceFen;

    /** 促销价（分），可空 */
    @Min(value = 0, message = "促销价不能为负")
    private Long promotionPriceFen;

    /** 秒杀价（分），可空 */
    @Min(value = 0, message = "秒杀价不能为负")
    private Long seckillPriceFen;

    /** 成本价（分） */
    @NotNull(message = "成本价不能为空")
    @Min(value = 0, message = "成本价不能为负")
    private Long costPriceFen;

    /** 初始可售库存（新建 SKU 时使用），默认 0 */
    @Min(value = 0, message = "库存不能为负")
    private Long stockQty;

    /** 预警阈值，默认 10 */
    @Min(value = 0, message = "预警阈值不能为负")
    private Long warnThreshold;

    /** 是否预售：0 否 1 是 */
    private Integer presaleFlag;

    /** 库存类型：1 普通 2 预售 3 秒杀 4 拼团 */
    private Integer stockType;
}
