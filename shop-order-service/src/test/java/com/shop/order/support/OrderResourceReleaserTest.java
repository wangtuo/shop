package com.shop.order.support;

import com.shop.api.marketing.client.MarketingClient;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.api.user.client.UserClient;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 取消/超时资源释放单测：积分→营销→库存逆序；积分条件；单步失败不阻断后续释放。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderResourceReleaserTest {

    @Mock
    private ProductClient productClient;
    @Mock
    private MarketingClient marketingClient;
    @Mock
    private UserClient userClient;

    @InjectMocks
    private OrderResourceReleaser releaser;

    private Order order() {
        Order o = new Order();
        o.setOrderNo("O1");
        o.setUserId(1001L);
        o.setOrderType(1);
        o.setUsedPoints(500L);
        return o;
    }

    private OrderItem item(long skuId, int qty, int stockType) {
        OrderItem i = new OrderItem();
        i.setSkuId(skuId);
        i.setQty(qty);
        i.setStockType(stockType);
        return i;
    }

    @Test
    void releaseAll_withPoints_releasesAllThree() {
        when(userClient.releasePoints(any())).thenReturn(Result.success());
        when(marketingClient.releasePromotion(any())).thenReturn(Result.success());
        when(productClient.releaseStock(any())).thenReturn(Result.success());

        releaser.releaseAll(order(), List.of(item(11L, 2, 1)), true);

        verify(userClient).releasePoints(any());
        verify(marketingClient).releasePromotion(any());
        ArgumentCaptor<StockReleaseCommand> captor = ArgumentCaptor.forClass(StockReleaseCommand.class);
        verify(productClient).releaseStock(captor.capture());
        StockReleaseCommand cmd = captor.getValue();
        assertThat(cmd.getOrderNo()).isEqualTo("O1");
        assertThat(cmd.getItems()).hasSize(1);
        assertThat(cmd.getItems().get(0).getSkuId()).isEqualTo(11L);
        assertThat(cmd.getItems().get(0).getQty()).isEqualTo(2);
    }

    @Test
    void releaseAll_withoutPoints_skipsPointsButReleasesRest() {
        releaser.releaseAll(order(), List.of(), false);
        verify(userClient, never()).releasePoints(any());
        verify(marketingClient).releasePromotion(any());
        verify(productClient).releaseStock(any());
    }

    @Test
    void releaseAll_zeroUsedPoints_skipsPoints() {
        Order o = order();
        o.setUsedPoints(0L);
        releaser.releaseAll(o, List.of(), true);
        verify(userClient, never()).releasePoints(any());
    }

    @Test
    void releaseAll_pointsFailure_stillReleasesPromotionAndStock() {
        when(userClient.releasePoints(any()))
                .thenReturn(Result.fail(ErrorCode.DEPENDENCY_FAIL, "用户域不可用"));
        releaser.releaseAll(order(), List.of(item(11L, 1, 1)), true);
        verify(marketingClient).releasePromotion(any());
        verify(productClient).releaseStock(any());
    }

    @Test
    void releaseAll_promotionFailure_stillReleasesStockAndDoesNotThrow() {
        when(marketingClient.releasePromotion(any()))
                .thenThrow(new BizException(ErrorCode.DEPENDENCY_FAIL, "营销域不可用"));
        assertThatCode(() -> releaser.releaseAll(order(), List.of(), true)).doesNotThrowAnyException();
        verify(userClient).releasePoints(any());
        verify(productClient).releaseStock(any());
    }
}
