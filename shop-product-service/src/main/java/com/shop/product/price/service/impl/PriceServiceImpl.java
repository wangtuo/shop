package com.shop.product.price.service.impl;

import com.shop.api.product.enums.GoodsStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.mapper.ProductSkuMapper;
import com.shop.product.price.dto.PriceSnapshotDTO;
import com.shop.product.price.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 价格服务实现（design 3.4）：
 * 会员价仅 L1 及以上可享受；促销价/秒杀价配置了即参与取低；
 * 等低价时按 销售价 → 会员价 → 促销价 → 秒杀价 的顺序保持已选，
 * 促销价与秒杀价的互斥/叠加判定由营销域负责，本域不拦截。
 */
@Service
@RequiredArgsConstructor
public class PriceServiceImpl implements PriceService {

    /** 会员价起始享受等级：L1 */
    private static final int MEMBER_MIN_LEVEL = 1;

    private final ProductSkuMapper skuMapper;

    @Override
    public PriceSnapshotDTO snapshot(Long skuId, Integer userLevel) {
        ProductSku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "SKU 不存在：" + skuId);
        }
        long best = selectBestPrice(sku.getSalePriceFen(), sku.getMemberPriceFen(),
                sku.getPromotionPriceFen(), sku.getSeckillPriceFen(), userLevel);
        int type = selectBestPriceType(sku.getSalePriceFen(), sku.getMemberPriceFen(),
                sku.getPromotionPriceFen(), sku.getSeckillPriceFen(), userLevel);
        long available = nz(sku.getAvailableStock());
        return PriceSnapshotDTO.builder()
                .skuId(sku.getId())
                .spuId(sku.getSpuId())
                .merchantId(sku.getMerchantId())
                .marketPriceFen(nz(sku.getMarketPriceFen()))
                .salePriceFen(nz(sku.getSalePriceFen()))
                .memberPriceFen(sku.getMemberPriceFen())
                .promotionPriceFen(sku.getPromotionPriceFen())
                .seckillPriceFen(sku.getSeckillPriceFen())
                .finalPriceFen(best)
                .finalPriceType(type)
                .availableStock(available)
                .lockedStock(nz(sku.getLockedStock()))
                .occupiedStock(nz(sku.getOccupiedStock()))
                .warnThreshold(nz(sku.getWarnThreshold()))
                .status(sku.getStatus())
                .saleable(sku.getStatus() != null
                        && sku.getStatus() == GoodsStatuses.ON_SALE.getCode() && available > 0)
                .build();
    }

    @Override
    public long selectBestPrice(Long salePriceFen, Long memberPriceFen, Long promotionPriceFen,
                                Long seckillPriceFen, Integer userLevel) {
        return pickBest(salePriceFen, memberPriceFen, promotionPriceFen, seckillPriceFen, userLevel).price;
    }

    @Override
    public int selectBestPriceType(Long salePriceFen, Long memberPriceFen, Long promotionPriceFen,
                                   Long seckillPriceFen, Integer userLevel) {
        return pickBest(salePriceFen, memberPriceFen, promotionPriceFen, seckillPriceFen, userLevel).type;
    }

    /**
     * 取当前用户可享受最低价并同步记录类型；严格小于才替换，等低价保持
     * 销售价 → 会员价 → 促销价 → 秒杀价 的先到优先级。
     */
    private Best pickBest(Long salePriceFen, Long memberPriceFen, Long promotionPriceFen,
                          Long seckillPriceFen, Integer userLevel) {
        if (salePriceFen == null || salePriceFen < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "销售价不能为空且不能为负");
        }
        long best = salePriceFen;
        int type = PriceSnapshotDTO.TYPE_SALE;
        if (canEnjoyMember(userLevel) && memberPriceFen != null && memberPriceFen < best) {
            best = memberPriceFen;
            type = PriceSnapshotDTO.TYPE_MEMBER;
        }
        if (promotionPriceFen != null && promotionPriceFen < best) {
            best = promotionPriceFen;
            type = PriceSnapshotDTO.TYPE_PROMOTION;
        }
        if (seckillPriceFen != null && seckillPriceFen < best) {
            best = seckillPriceFen;
            type = PriceSnapshotDTO.TYPE_SECKILL;
        }
        return new Best(best, type);
    }

    private record Best(long price, int type) {
    }

    private boolean canEnjoyMember(Integer userLevel) {
        return userLevel != null && userLevel >= MEMBER_MIN_LEVEL;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }
}
