package com.shop.product.category.controller;

import com.shop.common.result.Result;
import com.shop.product.category.dto.AttrKeySaveRequest;
import com.shop.product.category.entity.ProductAttrKey;
import com.shop.product.category.service.AttrKeyService;
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

import java.util.List;

/**
 * 规格/属性主数据 HTTP（B13）：读匿名开放（商户建品取属性模板）；
 * 写操作由 Service 内鉴权（平台运营 userType=2，口径同 AdminGoodsController）。
 */
@RestController
@RequestMapping("/attr-keys")
@RequiredArgsConstructor
public class AttrKeyController {

    private final AttrKeyService attrKeyService;

    /** 查类目适用的启用属性（自动并集全局通用属性） */
    @Anonymous
    @GetMapping
    public Result<List<ProductAttrKey>> listByCategory(@RequestParam("categoryId") Long categoryId) {
        return Result.success(attrKeyService.listByCategory(categoryId));
    }

    /** 新建属性主数据（平台运营） */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody AttrKeySaveRequest request) {
        return Result.success(attrKeyService.create(request));
    }

    /** 编辑属性主数据（平台运营） */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AttrKeySaveRequest request) {
        attrKeyService.update(id, request);
        return Result.success();
    }

    /** 删除属性主数据（平台运营） */
    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable Long id) {
        attrKeyService.delete(id);
        return Result.success();
    }
}
