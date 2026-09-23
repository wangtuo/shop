package com.shop.product.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.product.category.dto.CategorySaveRequest;
import com.shop.product.category.dto.CategoryTreeDTO;
import com.shop.product.category.entity.ProductCategory;
import com.shop.product.category.mapper.ProductCategoryMapper;
import com.shop.product.category.service.CategoryService;
import com.shop.product.support.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类目服务实现：层级由父节点推导，最多三级；写操作仅平台运营。
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private static final long ROOT_PID = 0L;
    private static final int MAX_LEVEL = 3;

    private final ProductCategoryMapper categoryMapper;

    @Override
    public List<CategoryTreeDTO> tree() {
        List<ProductCategory> all = categoryMapper.selectList(
                new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getStatus, 1)
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getId));
        Map<Long, CategoryTreeDTO> indexed = new LinkedHashMap<>();
        for (ProductCategory c : all) {
            indexed.put(c.getId(), toTreeDTO(c));
        }
        List<CategoryTreeDTO> roots = new ArrayList<>();
        for (CategoryTreeDTO node : indexed.values()) {
            if (node.getPid() != null && node.getPid() != ROOT_PID) {
                CategoryTreeDTO parent = indexed.get(node.getPid());
                if (parent != null) {
                    parent.getChildren().add(node);
                    continue;
                }
            }
            roots.add(node);
        }
        return roots;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveRequest request) {
        AuthUtils.requirePlatform();
        long pid = request.getPid() == null ? ROOT_PID : request.getPid();
        int level = 1;
        if (pid != ROOT_PID) {
            ProductCategory parent = requireCategory(pid);
            level = parent.getLevel() + 1;
            if (level > MAX_LEVEL) {
                throw new BizException(ErrorCode.PARAM_INVALID, "类目最多三级");
            }
        }
        ProductCategory category = new ProductCategory();
        category.setPid(pid);
        category.setLevel(level);
        category.setName(request.getName());
        category.setIcon(request.getIcon());
        category.setSort(request.getSort() == null ? 0 : request.getSort());
        category.setStatus(request.getStatus() == null ? 1 : request.getStatus());
        category.setAttrTemplateJson(buildTemplateJson(request));
        categoryMapper.insert(category);
        return category.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, CategorySaveRequest request) {
        AuthUtils.requirePlatform();
        ProductCategory category = requireCategory(id);
        category.setName(request.getName());
        category.setIcon(request.getIcon());
        if (request.getSort() != null) {
            category.setSort(request.getSort());
        }
        if (request.getStatus() != null) {
            category.setStatus(request.getStatus());
        }
        category.setAttrTemplateJson(buildTemplateJson(request));
        categoryMapper.updateById(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AuthUtils.requirePlatform();
        requireCategory(id);
        Long childCount = categoryMapper.selectCount(
                new LambdaQueryWrapper<ProductCategory>().eq(ProductCategory::getPid, id));
        if (childCount > 0) {
            throw new BizException(ErrorCode.CONFLICT, "存在子类目，不能删除");
        }
        categoryMapper.deleteById(id);
    }

    private ProductCategory requireCategory(Long id) {
        ProductCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "类目不存在");
        }
        return category;
    }

    private String buildTemplateJson(CategorySaveRequest request) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("keyAttrs", request.getKeyAttrs());
        template.put("saleAttrs", request.getSaleAttrs());
        template.put("attrs", request.getAttrs());
        return JsonUtils.toJson(template);
    }

    @SuppressWarnings("unchecked")
    private CategoryTreeDTO toTreeDTO(ProductCategory c) {
        CategoryTreeDTO dto = new CategoryTreeDTO();
        dto.setId(c.getId());
        dto.setPid(c.getPid());
        dto.setLevel(c.getLevel());
        dto.setName(c.getName());
        dto.setIcon(c.getIcon());
        dto.setSort(c.getSort());
        dto.setStatus(c.getStatus());
        if (c.getAttrTemplateJson() != null && !c.getAttrTemplateJson().isBlank()) {
            Map<String, Object> template = JsonUtils.fromJson(c.getAttrTemplateJson(), Map.class);
            if (template != null) {
                dto.setKeyAttrs(toStringList(template.get("keyAttrs")));
                dto.setSaleAttrs(toStringList(template.get("saleAttrs")));
                dto.setAttrs(toStringList(template.get("attrs")));
            }
        }
        return dto;
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    result.add(o.toString());
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
}
