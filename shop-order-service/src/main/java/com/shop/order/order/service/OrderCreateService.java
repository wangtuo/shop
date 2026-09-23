package com.shop.order.order.service;

import com.shop.order.order.dto.CreateOrderRequest;

/**
 * 下单核心编排服务（design 5.3）。
 */
public interface OrderCreateService {

    /**
     * 提交订单（@Idempotent 以 clientToken 防重复提交）。
     *
     * @return 订单号
     */
    String create(CreateOrderRequest request, Long userId);
}
