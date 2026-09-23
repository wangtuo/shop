package com.shop.product.stock.controller;

import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.FreightCalcRequest;
import com.shop.api.product.dto.FreightCalcResponse;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.StockDeductCommand;
import com.shop.api.product.dto.StockLockCommand;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.api.product.dto.StockReturnCommand;
import com.shop.common.constant.BatchSizes;
import com.shop.common.result.Result;
import com.shop.framework.web.Anonymous;
import com.shop.product.freight.service.FreightPricingService;
import com.shop.product.stock.service.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品域内部接口，路径与 {@link ProductClient} 的
 * {@code @FeignClient(path="/inner/product")} 及各 @*Mapping 子路径逐字一致：
 * GET  /sku、POST /sku/list、POST /stock/lock、/stock/confirm、
 *      /stock/release、/stock/return、GET /stock/saleable、POST /freight/calc。
 * 内网服务间调用，免网关登录鉴权。
 */
@Anonymous
@RestController
@RequestMapping("/inner/product")
@RequiredArgsConstructor
@Validated
public class ProductInnerController {

    private final StockService stockService;
    private final FreightPricingService freightPricingService;

    @GetMapping("/sku")
    public Result<SkuDTO> getSku(@RequestParam("skuId") Long skuId) {
        return Result.success(stockService.getSku(skuId));
    }

    @PostMapping("/sku/list")
    public Result<List<SkuDTO>> listSkus(@RequestBody
                                         @Size(max = BatchSizes.IN_IDS_MAX, message = "SKU ID 列表单次最多 "
                                                 + BatchSizes.IN_IDS_MAX + " 个")
                                         List<Long> skuIds) {
        return Result.success(stockService.listSkus(skuIds));
    }

    @PostMapping("/stock/lock")
    public Result<Void> lockStock(@Valid @RequestBody StockLockCommand cmd) {
        stockService.lockStock(cmd);
        return Result.success();
    }

    @PostMapping("/stock/confirm")
    public Result<Void> confirmDeduct(@Valid @RequestBody StockDeductCommand cmd) {
        stockService.confirmDeduct(cmd);
        return Result.success();
    }

    @PostMapping("/stock/release")
    public Result<Void> releaseStock(@Valid @RequestBody StockReleaseCommand cmd) {
        stockService.releaseStock(cmd);
        return Result.success();
    }

    @PostMapping("/stock/return")
    public Result<Void> returnStock(@Valid @RequestBody StockReturnCommand cmd) {
        stockService.returnStock(cmd);
        return Result.success();
    }

    @GetMapping("/stock/saleable")
    public Result<Boolean> saleable(@RequestParam("skuId") Long skuId, @RequestParam("qty") Integer qty) {
        return Result.success(stockService.saleable(skuId, qty));
    }

    /**
     * 运费试算（TRADE C32）：服务端取价为唯一权威口径，返回运费与命中规则快照 JSON。
     * 路径与 {@link ProductClient#calcFreight} 逐字一致。
     */
    @PostMapping("/freight/calc")
    public Result<FreightCalcResponse> calcFreight(@Valid @RequestBody FreightCalcRequest request) {
        return Result.success(freightPricingService.calc(request));
    }
}
