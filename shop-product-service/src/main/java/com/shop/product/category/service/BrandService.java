package com.shop.product.category.service;

import com.shop.product.category.dto.BrandSaveRequest;
import com.shop.product.category.entity.ProductBrand;
import com.shop.common.result.PageResult;

/**
 * 品牌服务：平台运营 CRUD，前台分页/列表查询。
 */
public interface BrandService {

    /** 品牌分页（可按名称模糊、首字母过滤）。 */
    PageResult<ProductBrand> page(Integer pageNum, Integer pageSize, String keyword, String initial);

    Long create(BrandSaveRequest request);

    void update(Long id, BrandSaveRequest request);

    void delete(Long id);
}
