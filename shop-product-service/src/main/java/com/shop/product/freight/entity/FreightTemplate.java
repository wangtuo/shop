package com.shop.product.freight.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 运费模板（商家维度，一个店铺可多模板、至多一个默认模板，GAP_PLAN_TRADE B7 / V3 DDL）。
 *
 * <p>chargeType：1 按件 2 按重量(g) 3 按体积(cm³)；默认首续费率在
 * {@code region_codes} 未命中时使用；freeConditionFen=0 表示不设满额包邮。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_freight_template")
public class FreightTemplate extends BaseEntity {

    /** 商家 ID */
    private Long merchantId;

    /** 模板名称 */
    private String name;

    /** 计费方式：1 按件 2 按重量 3 按体积 */
    private Integer chargeType;

    /** 默认首件数/重(g)/体积(cm³)单位数 */
    private Integer defaultFirst;

    /** 默认首费（分） */
    private Long defaultFirstFee;

    /** 默认续件单位数 */
    private Integer defaultAdd;

    /** 默认续费（分） */
    private Long defaultAddFee;

    /** 满额包邮门槛（分），0 不包邮 */
    private Long freeConditionFen;

    /** 是否店铺默认：0 否 1 是（同店唯一，CAS 保证） */
    private Integer isDefault;

    /** 0 停用 1 启用 */
    private Integer status;
}
