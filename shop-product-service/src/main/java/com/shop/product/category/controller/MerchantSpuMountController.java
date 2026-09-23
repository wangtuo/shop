package com.shop.product.category.controller;

import com.shop.common.result.Result;
import com.shop.product.category.dto.SpuCategoryMountRequest;
import com.shop.product.category.entity.ProductSpuCategory;
import com.shop.product.category.service.SpuCategoryMountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商户端 SPU 虚拟类目挂载（B13，/merchant/spus/{spuId}/categories）。
 * merchantId 取自登录上下文，服务内做 SPU 归属鉴权；主归属不可卸载。
 */
@RestController
@RequestMapping("/merchant/spus/{spuId}/categories")
@RequiredArgsConstructor
public class MerchantSpuMountController {

    private final SpuCategoryMountService mountService;

    /** 查 SPU 的虚拟挂载类目 */
    @GetMapping
    public Result<List<ProductSpuCategory>> list(@PathVariable Long spuId) {
        return Result.success(mountService.listMounts(spuId));
    }

    /** 挂载到虚拟类目（重复挂载幂等） */
    @PostMapping
    public Result<Void> mount(@PathVariable Long spuId,
                              @Valid @RequestBody SpuCategoryMountRequest request) {
        mountService.mount(spuId, request.getCategoryId());
        return Result.success();
    }

    /** 卸载虚拟挂载（主归属 category3_id 拒绝卸载） */
    @PostMapping("/unmount")
    public Result<Void> unmount(@PathVariable Long spuId,
                                @Valid @RequestBody SpuCategoryMountRequest request) {
        mountService.unmount(spuId, request.getCategoryId());
        return Result.success();
    }
}
