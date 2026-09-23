package com.shop.product.category.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.product.category.dto.BrandSaveRequest;
import com.shop.product.category.entity.ProductBrand;
import com.shop.product.category.service.BrandService;
import com.shop.framework.web.Anonymous;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 品牌 HTTP：查询匿名开放；CRUD 由 Service 内鉴权（平台运营 userType=2）。
 */
@RestController
@RequestMapping("/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @Anonymous
    @GetMapping
    public Result<PageResult<ProductBrand>> page(@RequestParam(required = false) Integer pageNum,
                                                 @RequestParam(required = false) Integer pageSize,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String initial) {
        return Result.success(brandService.page(pageNum, pageSize, keyword, initial));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody BrandSaveRequest request) {
        return Result.success(brandService.create(request));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BrandSaveRequest request) {
        brandService.update(id, request);
        return Result.success();
    }

    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable Long id) {
        brandService.delete(id);
        return Result.success();
    }
}
