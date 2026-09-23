package com.shop.product.category.controller;

import com.shop.common.result.Result;
import com.shop.product.category.dto.CategorySaveRequest;
import com.shop.product.category.dto.CategoryTreeDTO;
import com.shop.product.category.service.CategoryService;
import com.shop.framework.web.Anonymous;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 类目 HTTP：树查询对前台匿名开放；写操作由 Service 内鉴权（平台运营 userType=2）。
 */
@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /** 三级类目树 */
    @Anonymous
    @GetMapping("/tree")
    public Result<List<CategoryTreeDTO>> tree() {
        return Result.success(categoryService.tree());
    }

    /** 新建类目（平台运营） */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CategorySaveRequest request) {
        return Result.success(categoryService.create(request));
    }

    /** 编辑类目 / 属性模板（平台运营） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveRequest request) {
        categoryService.update(id, request);
        return Result.success();
    }

    /** 删除类目（平台运营，存在子类目拒绝） */
    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
