package com.shop.product.goods.support;

import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.dto.SpuDTO;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.entity.ProductSpu;

import java.math.BigDecimal;

/**
 * 商品实体 → shop-api 对外 DTO 装配。库存 TCC、浏览、内部接口共用，保证字段口径一致。
 */
public final class GoodsDtoAssembler {

    private GoodsDtoAssembler() {
    }

    public static SpuDTO toSpuDTO(ProductSpu spu) {
        if (spu == null) {
            return null;
        }
        return SpuDTO.builder()
                .spuId(spu.getId())
                .merchantId(spu.getMerchantId())
                .shopId(spu.getShopId())
                .name(spu.getName())
                .brandId(spu.getBrandId())
                .category3Id(spu.getCategory3Id())
                .mainImage(spu.getMainImage())
                .status(spu.getStatus())
                .sales(nz(spu.getSales()))
                .goodCommentCount(nz(spu.getGoodCommentCount()))
                .totalCommentCount(nz(spu.getTotalCommentCount()))
                .goodRate(spu.getGoodRate() == null ? BigDecimal.ZERO : spu.getGoodRate())
                .build();
    }

    public static SkuDTO toSkuDTO(ProductSku sku) {
        if (sku == null) {
            return null;
        }
        return SkuDTO.builder()
                .skuId(sku.getId())
                .spuId(sku.getSpuId())
                .skuCode(sku.getSkuCode())
                .merchantId(sku.getMerchantId())
                .shopId(sku.getShopId())
                .skuName(sku.getSkuName())
                .specText(sku.getSpecText())
                .image(sku.getImage())
                .salePriceFen(sku.getSalePriceFen())
                .marketPriceFen(sku.getMarketPriceFen())
                .memberPriceFen(sku.getMemberPriceFen())
                .promotionPriceFen(sku.getPromotionPriceFen())
                .seckillPriceFen(sku.getSeckillPriceFen())
                .costPriceFen(sku.getCostPriceFen())
                .availableStock(nz(sku.getAvailableStock()))
                .lockedStock(nz(sku.getLockedStock()))
                .occupiedStock(nz(sku.getOccupiedStock()))
                .warnThreshold(nz(sku.getWarnThreshold()))
                .status(sku.getStatus())
                .category3Id(sku.getCategory3Id())
                .weightGram(sku.getWeightGram())
                .volumeCc(sku.getVolumeCc())
                .barcode(sku.getBarcode())
                .presaleFlag(sku.getPresaleFlag())
                .stockType(sku.getStockType())
                .build();
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }
}
