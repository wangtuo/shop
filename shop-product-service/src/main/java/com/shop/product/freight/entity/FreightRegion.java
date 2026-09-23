package com.shop.product.freight.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 区域运费规则：可配送区域 + 指定区域费率（B7 / V3 DDL）。
 * region_codes 为行政区划编码 JSON 数组（省/市/区，编码以 USER 地址字典为准）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_freight_region")
public class FreightRegion extends BaseEntity {

    /** 运费模板 ID */
    private Long templateId;

    /** 商家 ID（冗余） */
    private Long merchantId;

    /** 适用行政区划编码列表 JSON 数组，如 ["330000","330100"] */
    private String regionCodes;

    /** 首件单位数 */
    private Integer firstUnit;

    /** 首费（分） */
    private Long firstFeeFen;

    /** 续件单位数 */
    private Integer addUnit;

    /** 续费（分） */
    private Long addFeeFen;

    /** 是否可配送：0 不可配送(拒单) 1 可配送 */
    private Integer deliverable;
}
