package com.shop.order.support;

import com.shop.api.product.enums.GoodsStatuses;
import com.shop.api.product.dto.SkuDTO;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 限购规则校验（design 5.3.1 第 4 项）。
 *
 * <p>限购 N 的来源：Redis key {@code shop:order:limit:sku:{skuId}}（由商品/营销域按活动配置，
 * 订单域只读取），未配置视为不限购。历史成交量取 t_order_item 中已支付（非待付款/已取消）
 * 订单的同 user+SKU 数量合计。
 */
@Component
@RequiredArgsConstructor
public class PurchaseLimitChecker {

    private final RedissonClient redissonClient;

    /** 读取 SKU 限购件数，未配置返回 null（不限购）。 */
    public Integer limitOf(Long skuId) {
        RBucket<Integer> bucket = redissonClient.getBucket("shop:order:limit:sku:" + skuId);
        return bucket.get();
    }

    /**
     * 校验历史成交量 + 本次购买量是否超限。
     *
     * @param skuId       SKU ID
     * @param requestQty  本次购买数量
     * @param purchasedQty 历史成交数量
     * @throws com.shop.common.exception.BizException 超限时抛 LIMIT_PURCHASE
     */
    public void check(Long skuId, int requestQty, long purchasedQty) {
        Integer limit = limitOf(skuId);
        if (limit != null && limit > 0 && purchasedQty + requestQty > limit) {
            throw new com.shop.common.exception.BizException(
                    com.shop.common.exception.ErrorCode.LIMIT_PURCHASE,
                    "该商品每人限购 " + limit + " 件，已购 " + purchasedQty + " 件");
        }
    }

    /**
     * 加购/结算前失效判定：非在售状态或库存为 0 即失效（design 5.4）。
     *
     * @return 0 表示有效；否则为失效原因 1 已下架 2 售罄/库存 0 3 已删除
     */
    public int invalidReason(SkuDTO sku) {
        if (sku == null) {
            return 3;
        }
        Integer status = sku.getStatus();
        if (status != null && (status == GoodsStatuses.DELETED.getCode())) {
            return 3;
        }
        if (status == null || status != GoodsStatuses.ON_SALE.getCode()) {
            return 1;
        }
        if (sku.getAvailableStock() == null || sku.getAvailableStock() <= 0) {
            return 2;
        }
        return 0;
    }
}
