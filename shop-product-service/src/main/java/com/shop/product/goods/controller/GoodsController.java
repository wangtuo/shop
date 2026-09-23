package com.shop.product.goods.controller;

import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.SpuDTO;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.web.Anonymous;
import com.shop.product.goods.dto.SpuBrowseQuery;
import com.shop.product.goods.dto.SpuDetailVO;
import com.shop.product.goods.service.SpuService;
import com.shop.product.price.dto.PriceSnapshotDTO;
import com.shop.product.price.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * C 端商品浏览：SPU 详情（Redis 版本缓存）、类目/关键词分页、SKU 列表、价格库存快照。
 * 全部匿名可访问。
 */
@Anonymous
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class GoodsController {

    private final SpuService spuService;
    private final PriceService priceService;

    /** 在售商品分页：按三级类目 / 关键词 */
    @GetMapping
    public Result<PageResult<SpuDTO>> browse(@ModelAttribute SpuBrowseQuery query) {
        return Result.success(spuService.browsePage(query));
    }

    /** SPU 详情（带版本缓存，仅上架/售罄可见） */
    @GetMapping("/{spuId}")
    public Result<SpuDetailVO> detail(@PathVariable Long spuId) {
        return Result.success(spuService.browseDetail(spuId));
    }

    /** SPU 下 SKU 列表 */
    @GetMapping("/{spuId}/skus")
    public Result<List<SkuDTO>> skus(@PathVariable Long spuId) {
        return Result.success(spuService.browseDetail(spuId).getSkus());
    }

    /** SKU 价格 + 库存聚合快照；userLevel 由调用方传入（会员价门槛） */
    @GetMapping("/skus/{skuId}/price")
    public Result<PriceSnapshotDTO> price(@PathVariable Long skuId,
                                          @RequestParam(required = false, defaultValue = "0") Integer userLevel) {
        return Result.success(priceService.snapshot(skuId, userLevel));
    }
}
