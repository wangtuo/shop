package com.shop.order.support;

import com.shop.api.product.dto.SkuDTO;
import com.shop.api.product.enums.GoodsStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 限购校验单测：Redis 未配置不限购；历史 + 本次超限拒绝；失效原因三分（下架/售罄/删除）。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseLimitCheckerTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<Integer> bucket;

    private PurchaseLimitChecker checker;

    @BeforeEach
    void setUp() {
        checker = new PurchaseLimitChecker(redissonClient);
    }

    @Test
    void limitOf_readsConventionalRedisKey() {
        when(redissonClient.<Integer>getBucket("shop:order:limit:sku:11")).thenReturn(bucket);
        when(bucket.get()).thenReturn(5);
        assertThat(checker.limitOf(11L)).isEqualTo(5);
    }

    @Test
    void check_noLimitConfigured_alwaysPasses() {
        when(redissonClient.<Integer>getBucket("shop:order:limit:sku:11")).thenReturn(bucket);
        when(bucket.get()).thenReturn(null);
        assertThatCode(() -> checker.check(11L, 999, 0)).doesNotThrowAnyException();
    }

    @Test
    void check_withinLimit_passes() {
        when(redissonClient.<Integer>getBucket("shop:order:limit:sku:11")).thenReturn(bucket);
        when(bucket.get()).thenReturn(10);
        assertThatCode(() -> checker.check(11L, 3, 7)).doesNotThrowAnyException();
    }

    @Test
    void check_exceedLimit_throwsLimitPurchase() {
        when(redissonClient.<Integer>getBucket("shop:order:limit:sku:11")).thenReturn(bucket);
        when(bucket.get()).thenReturn(10);
        assertThatThrownBy(() -> checker.check(11L, 2, 9))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.LIMIT_PURCHASE.getCode());
    }

    @Test
    void invalidReason_categories() {
        SkuDTO onSale = SkuDTO.builder().status(GoodsStatuses.ON_SALE.getCode()).availableStock(5L).build();
        SkuDTO offShelf = SkuDTO.builder().status(4).availableStock(5L).build();
        SkuDTO deleted = SkuDTO.builder().status(GoodsStatuses.DELETED.getCode()).availableStock(5L).build();
        SkuDTO soldOut = SkuDTO.builder().status(GoodsStatuses.ON_SALE.getCode()).availableStock(0L).build();
        SkuDTO noStockField = SkuDTO.builder().status(GoodsStatuses.ON_SALE.getCode()).build();

        assertThat(checker.invalidReason(onSale)).isZero();
        assertThat(checker.invalidReason(offShelf)).isEqualTo(1);
        assertThat(checker.invalidReason(deleted)).isEqualTo(3);
        assertThat(checker.invalidReason(soldOut)).isEqualTo(2);
        assertThat(checker.invalidReason(noStockField)).isEqualTo(2);
        assertThat(checker.invalidReason(null)).isEqualTo(3);
    }
}
