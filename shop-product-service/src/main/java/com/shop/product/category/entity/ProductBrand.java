package com.shop.product.category.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 商品品牌。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_product_brand")
public class ProductBrand extends BaseEntity {

    /** 品牌名称 */
    private String name;

    /** 品牌 LOGO URL */
    private String logo;

    /** 品牌首字母（A-Z） */
    private String initial;

    /** 排序，升序 */
    private Integer sort;

    /** 状态：0 停用 1 启用 */
    private Integer status;
}
