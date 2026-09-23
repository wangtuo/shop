package com.shop.order.support;

import com.shop.api.marketing.client.MarketingClient;
import com.shop.api.marketing.dto.PromotionReleaseCommand;
import com.shop.api.product.client.ProductClient;
import com.shop.api.product.dto.StockItemCommand;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.PointsReleaseCommand;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 订单取消/超时/补偿时的资源释放：逆序调用积分 → 营销 → 库存。
 * 各域 release 均以 orderNo 幂等；单步失败仅记录日志，不阻断取消主流程。
 */
@Component
@RequiredArgsConstructor
public class OrderResourceReleaser {

    private static final Logger log = LoggerFactory.getLogger(OrderResourceReleaser.class);

    private final ProductClient productClient;
    private final MarketingClient marketingClient;
    private final UserClient userClient;

    public void releaseAll(Order order, List<OrderItem> items, boolean withPoints) {
        if (withPoints && order.getUsedPoints() != null && order.getUsedPoints() > 0) {
            try {
                userClient.releasePoints(PointsReleaseCommand.builder()
                        .userId(order.getUserId())
                        .bizNo(order.getOrderNo())
                        .build());
            } catch (Exception e) {
                log.error("取消订单释放冻结积分失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        try {
            marketingClient.releasePromotion(PromotionReleaseCommand.builder()
                    .userId(order.getUserId())
                    .orderNo(order.getOrderNo())
                    .build());
        } catch (Exception e) {
            log.error("取消订单释放营销资源失败 orderNo={}", order.getOrderNo(), e);
        }
        try {
            List<StockItemCommand> stockItems = items.stream()
                    .map(i -> StockItemCommand.builder()
                            .skuId(i.getSkuId())
                            .qty(i.getQty())
                            .stockType(i.getStockType())
                            .activityId(i.getActivityId())
                            .build())
                    .toList();
            productClient.releaseStock(StockReleaseCommand.builder()
                    .orderNo(order.getOrderNo())
                    .orderType(order.getOrderType())
                    .items(stockItems)
                    .build());
        } catch (Exception e) {
            log.error("取消订单释放库存失败 orderNo={}", order.getOrderNo(), e);
        }
    }
}
