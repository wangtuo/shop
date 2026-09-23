package com.shop.order.order.service;

import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 下单编排中间产物：已组装好待落库的订单聚合 + 事件发送所需参数。
 */
@Data
@Builder
@AllArgsConstructor
public class OrderAggregate {

    private Order order;
    private List<OrderItem> items;
    private OrderInvoice invoice;
    /** 支付超时秒数（5.3.3 矩阵） */
    private Long expirePaySeconds;
    /** 试算实际生效的用户券 ID（可能多张） */
    private List<Long> usedCouponIds;
    /** 是否锁定过积分（补偿判断） */
    private boolean pointsLocked;
    /** 下单成功后需要从购物车清理的条目 ID */
    private List<Long> cartIdsToClear;
}
