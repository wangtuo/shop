package com.shop.order.order.service;

import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderStatusDTO;
import com.shop.common.result.PageResult;
import com.shop.order.order.dto.OrderPageQuery;
import com.shop.order.order.entity.Order;

import java.util.List;
import java.util.Map;

/**
 * 订单查询服务（用户/商户归属鉴权）。
 */
public interface OrderQueryService {

    /**
     * 订单详情：买家或该单商户可查，其他人禁止。
     */
    OrderDTO detail(String orderNo, Long userId, Long merchantId);

    /** 用户分页（只看自己）。 */
    PageResult<OrderDTO> pageUser(Long userId, OrderPageQuery query);

    /** 商户分页（只看本店）。 */
    PageResult<OrderDTO> pageMerchant(Long merchantId, OrderPageQuery query);

    /** 内部 Feign：按订单号取聚合，无归属限制。 */
    OrderDTO getByOrderNo(String orderNo);

    /** 取订单实体（不存在抛 ORDER_NOT_FOUND）。 */
    Order requireByOrderNo(String orderNo);

    /**
     * 订单状态批量查询（TRADE C33，inner Feign /orders/status）：一次最多 100 个订单号，
     * 不存在的订单号不出现在返回 Map 中。
     */
    Map<String, OrderStatusDTO> listStatus(List<String> orderNos);
}
