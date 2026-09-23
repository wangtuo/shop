package com.shop.product.price.service;

import com.shop.product.price.dto.PriceSnapshotDTO;

/**
 * 价格服务：五价取当前用户可享受最低价，并聚合 SKU 库存。
 */
public interface PriceService {

    /**
     * 生成 SKU 价格库存快照。
     *
     * @param skuId     SKU ID
     * @param userLevel 会员等级 0-4（由调用方传入），L0 不享受会员价
     */
    PriceSnapshotDTO snapshot(Long skuId, Integer userLevel);

    /**
     * 五价选择：返回当前用户可享受最低价。
     *
     * @param salePriceFen      销售价（必有）
     * @param memberPriceFen    会员价（可空）
     * @param promotionPriceFen 促销价（可空）
     * @param seckillPriceFen   秒杀价（可空）
     * @param userLevel         会员等级 0-4
     * @return 最低价（分）
     */
    long selectBestPrice(Long salePriceFen, Long memberPriceFen, Long promotionPriceFen,
                         Long seckillPriceFen, Integer userLevel);

    /**
     * 最低价类型：1 销售价 2 会员价 3 促销价 4 秒杀价；并列时取优先级前者。
     */
    int selectBestPriceType(Long salePriceFen, Long memberPriceFen, Long promotionPriceFen,
                            Long seckillPriceFen, Integer userLevel);
}
