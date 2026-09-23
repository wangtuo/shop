package com.shop.product.goods.controller;

import com.shop.api.product.dto.SpuDTO;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.product.goods.dto.SpuDetailVO;
import com.shop.product.goods.dto.SpuManageQuery;
import com.shop.product.goods.dto.SpuSaveRequest;
import com.shop.product.goods.dto.SpuSaveResult;
import com.shop.product.goods.service.SpuService;
import com.shop.product.stock.dto.ReplenishRequest;
import com.shop.product.stock.service.StockService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户端商品管理（/merchant/products）。merchantId 取自登录上下文，服务内做归属鉴权。
 */
@RestController
@RequestMapping("/merchant/products")
@RequiredArgsConstructor
public class MerchantGoodsController {

    private final SpuService spuService;
    private final StockService stockService;

    /** 商品管理分页（强制本店） */
    @GetMapping
    public Result<PageResult<SpuDTO>> page(@ModelAttribute SpuManageQuery query) {
        return Result.success(spuService.managePage(query));
    }

    /** 商品详情（本店） */
    @GetMapping("/{spuId}")
    public Result<SpuDetailVO> detail(@PathVariable Long spuId) {
        return Result.success(spuService.manageDetail(spuId));
    }

    /** 新建商品（草稿），返回 SPU ID 与属性主数据校验告警（B13，告警不阻断保存） */
    @PostMapping
    public Result<SpuSaveResult> create(@Valid @RequestBody SpuSaveRequest request) {
        return Result.success(spuService.create(request));
    }

    /** 编辑商品，返回属性主数据校验告警 */
    @PutMapping("/{spuId}")
    public Result<SpuSaveResult> update(@PathVariable Long spuId, @Valid @RequestBody SpuSaveRequest request) {
        return Result.success(spuService.update(spuId, request));
    }

    /** 提交审核 */
    @PostMapping("/{spuId}/submit")
    public Result<Void> submit(@PathVariable Long spuId) {
        spuService.submitAudit(spuId);
        return Result.success();
    }

    /** 上架 */
    @PostMapping("/{spuId}/onsale")
    public Result<Void> onSale(@PathVariable Long spuId) {
        spuService.onSale(spuId);
        return Result.success();
    }

    /** 下架 */
    @PostMapping("/{spuId}/offsale")
    public Result<Void> offSale(@PathVariable Long spuId) {
        spuService.offSale(spuId);
        return Result.success();
    }

    /** 逻辑删除 */
    @PostMapping("/{spuId}/delete")
    public Result<Void> delete(@PathVariable Long spuId) {
        spuService.delete(spuId);
        return Result.success();
    }

    /** SKU 补货（可驱动售罄商品自动上架） */
    @PostMapping("/skus/{skuId}/replenish")
    public Result<Void> replenish(@PathVariable Long skuId, @Valid @RequestBody ReplenishRequest request) {
        stockService.replenish(skuId, request.getQty());
        return Result.success();
    }
}
