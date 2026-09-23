package com.shop.product.category.service;

import com.shop.product.category.dto.CategorySaveRequest;
import com.shop.product.category.dto.CategoryTreeDTO;

import java.util.List;

/**
 * 类目服务：三级类目树查询与平台运营 CRUD。
 */
public interface CategoryService {

    /** 全量类目树（仅启用节点）。 */
    List<CategoryTreeDTO> tree();

    /** 创建类目，返回新类目 ID。层级由父类目推导（最深三级）。 */
    Long create(CategorySaveRequest request);

    /** 编辑类目基础信息与属性模板。 */
    void update(Long id, CategorySaveRequest request);

    /** 删除类目；存在启用子类目时拒绝。 */
    void delete(Long id);
}
