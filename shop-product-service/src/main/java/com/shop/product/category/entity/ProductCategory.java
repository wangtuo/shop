package com.shop.product.category.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品三级类目树节点。pid=0 为一级类目，level 取 1/2/3。
 * attrTemplateJson 为类目属性模板：关键属性 / 销售属性 / 非关键属性。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_category")
public class ProductCategory extends BaseEntity {

    /** 父类目 ID，0 表示一级类目 */
    private Long pid;

    /** 层级：1 一级 2 二级 3 三级 */
    private Integer level;

    /** 类目名称 */
    private String name;

    /** 类目图标 URL */
    private String icon;

    /** 同级排序，升序 */
    private Integer sort;

    /** 状态：0 停用 1 启用 */
    private Integer status;

    /** 属性模板 JSON：{"keyAttrs":[...],"saleAttrs":[...],"attrs":[...]} */
    private String attrTemplateJson;
}
