package com.shop.product.category.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 类目树节点。
 */
@Data
public class CategoryTreeDTO implements Serializable {

    private Long id;
    private Long pid;
    private Integer level;
    private String name;
    private String icon;
    private Integer sort;
    private Integer status;

    /** 关键属性名 */
    private List<String> keyAttrs = new ArrayList<>();
    /** 销售属性名 */
    private List<String> saleAttrs = new ArrayList<>();
    /** 非关键属性名 */
    private List<String> attrs = new ArrayList<>();

    /** 子类目 */
    private List<CategoryTreeDTO> children = new ArrayList<>();
}
