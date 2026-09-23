package com.shop.product.category.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 规格/属性主数据（V4 t_product_attr_key）。
 *
 * <p>category_id=0 表示全局通用属性；{@code valueOptions} 为枚举可选值 JSON 数组；
 * attrsJson 继续做商品快照，本主数据用于建品校验与告警（B13）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_attr_key")
public class ProductAttrKey extends BaseEntity {

    /** 所属类目 ID，0=全局通用属性 */
    private Long categoryId;

    /** 属性名（颜色/尺码/材质…） */
    private String name;

    /** 属性类型：1 关键属性 2 销售规格(SKU) 3 普通属性(SPU) */
    private Integer attrType;

    /** 值类型：1 枚举 2 数值 3 文本 */
    private Integer valueType;

    /** 枚举可选值列表 JSON，如 ["红","蓝"] */
    private String valueOptions;

    /** 数值单位 */
    private String unit;

    /** 是否必填：0 否 1 是 */
    private Integer requiredFlag;

    /** 排序，升序 */
    private Integer sort;

    /** 状态：0 停用 1 启用 */
    private Integer status;
}
