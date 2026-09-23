package com.shop.api.product.client;

import com.shop.api.product.dto.FreightCalcRequest;
import com.shop.api.product.dto.FreightCalcResponse;
import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.StockDeductCommand;
import com.shop.api.product.dto.StockLockCommand;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.api.product.dto.StockReturnCommand;
import com.shop.common.result.Result;
import jakarta.validation.Valid;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品域跨服务 Feign 契约（CONTRACTS.md §3）。
 *
 * <p>库存 TCC 三阶段（见 design 3.3 库存四状态与扣减规则）：</p>
 * <ul>
 *     <li>{@link #lockStock}：TCC-try，可售库存 → 锁定库存；</li>
 *     <li>{@link #confirmDeduct}：TCC-confirm，锁定库存 → 占用库存（支付成功）；</li>
 *     <li>{@link #releaseStock}：TCC-cancel，锁定库存 → 可售库存（取消/超时未付）；</li>
 *     <li>{@link #returnStock}：售后回库，非质量问题回可售，质量问题入残次。</li>
 * </ul>
 */
@FeignClient(name = "shop-product-service", path = "/inner/product")
public interface ProductClient {

    /**
     * 按主键查询单个 SKU。
     */
    @GetMapping("/sku")
    Result<SkuDTO> getSku(@RequestParam("skuId") Long skuId);

    /**
     * 按主键批量查询 SKU，入参为空时返回空集合。
     */
    @PostMapping("/sku/list")
    Result<List<SkuDTO>> listSkus(@RequestBody List<Long> skuIds);

    /**
     * TCC-try：下单预占库存，可售库存 -= qty，锁定库存 += qty。
     * 以 orderNo + items 幂等。
     */
    @PostMapping("/stock/lock")
    Result<Void> lockStock(@Valid @RequestBody StockLockCommand cmd);

    /**
     * TCC-confirm：支付成功，锁定库存 -= qty，占用库存 += qty。
     */
    @PostMapping("/stock/confirm")
    Result<Void> confirmDeduct(@Valid @RequestBody StockDeductCommand cmd);

    /**
     * TCC-cancel：订单取消/超时未付，锁定库存 -= qty，可售库存 += qty。
     */
    @PostMapping("/stock/release")
    Result<Void> releaseStock(@Valid @RequestBody StockReleaseCommand cmd);

    /**
     * 售后回库：买家责任（非质量问题）回可售库存；质量问题入残次，不回可售。
     */
    @PostMapping("/stock/return")
    Result<Void> returnStock(@Valid @RequestBody StockReturnCommand cmd);

    /**
     * 校验指定 SKU 在对应库存类型下是否有足够可售库存（商品须为已上架状态）。
     */
    @GetMapping("/stock/saleable")
    Result<Boolean> saleable(@RequestParam("skuId") Long skuId, @RequestParam("qty") Integer qty);

    /**
     * 运费试算（TRADE C32，服务端取价为唯一权威口径）。
     *
     * <p>受既有类级前缀约束，实际路径为 {@code POST /inner/product/freight/calc}。
     */
    @PostMapping("/freight/calc")
    Result<FreightCalcResponse> calcFreight(@Valid @RequestBody FreightCalcRequest request);
}
