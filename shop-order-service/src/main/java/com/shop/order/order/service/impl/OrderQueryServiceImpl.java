package com.shop.order.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderStatusDTO;
import com.shop.common.constant.BatchSizes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.PageResult;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.invoice.mapper.OrderInvoiceMapper;
import com.shop.order.order.dto.OrderPageQuery;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.order.service.OrderQueryService;
import com.shop.order.support.OrderAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderQueryServiceImpl implements OrderQueryService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderInvoiceMapper invoiceMapper;
    private final OrderAssembler assembler;

    @Override
    public OrderDTO detail(String orderNo, Long userId, Long merchantId) {
        Order order = requireByOrderNo(orderNo);
        boolean owner = userId != null && userId.equals(order.getUserId());
        boolean shop = merchantId != null && merchantId.equals(order.getMerchantId());
        if (!owner && !shop) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该订单");
        }
        return toDTO(order);
    }

    @Override
    public PageResult<OrderDTO> pageUser(Long userId, OrderPageQuery query) {
        return page(buildWrapper(query).eq(Order::getUserId, userId), query);
    }

    @Override
    public PageResult<OrderDTO> pageMerchant(Long merchantId, OrderPageQuery query) {
        return page(buildWrapper(query).eq(Order::getMerchantId, merchantId), query);
    }

    @Override
    public OrderDTO getByOrderNo(String orderNo) {
        return toDTO(requireByOrderNo(orderNo));
    }

    @Override
    public Order requireByOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单号不能为空");
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderNo);
        }
        return order;
    }

    @Override
    public Map<String, OrderStatusDTO> listStatus(List<String> orderNos) {
        if (orderNos == null || orderNos.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = orderNos.stream().distinct().toList();
        if (distinct.size() > BatchSizes.IN_IDS_MAX) {
            throw new BizException(ErrorCode.PARAM_INVALID, "订单号数量不能超过 " + BatchSizes.IN_IDS_MAX);
        }
        List<Order> orders = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getOrderNo, Order::getStatus, Order::getOrderType,
                        Order::getPresaleFinalStage, Order::getCreateTime)
                .in(Order::getOrderNo, distinct));
        Map<String, OrderStatusDTO> result = new LinkedHashMap<>();
        for (Order order : orders) {
            result.put(order.getOrderNo(), OrderStatusDTO.builder()
                    .status(order.getStatus())
                    .orderType(order.getOrderType())
                    .presaleFinalStage(order.getPresaleFinalStage() == null
                            ? null : order.getPresaleFinalStage() == 1)
                    .gmtCreate(order.getCreateTime())
                    .build());
        }
        return result;
    }

    private PageResult<OrderDTO> page(LambdaQueryWrapper<Order> wrapper, OrderPageQuery query) {
        Page<Order> page = new Page<>(query.safePageNum(), query.safePageSize());
        Page<Order> result = orderMapper.selectPage(page, wrapper);
        List<OrderDTO> list = result.getRecords().stream().map(this::toListDTO).toList();
        return PageResult.of(query.safePageNum(), query.safePageSize(), result.getTotal(), list);
    }

    private LambdaQueryWrapper<Order> buildWrapper(OrderPageQuery query) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(query.getStatus() != null, Order::getStatus, query.getStatus())
                .eq(query.getOrderType() != null, Order::getOrderType, query.getOrderType())
                .ge(query.getStartTime() != null, Order::getCreateTime, query.getStartTime())
                .le(query.getEndTime() != null, Order::getCreateTime, query.getEndTime())
                .orderByDesc(Order::getId);
        return wrapper;
    }

    private OrderDTO toDTO(Order order) {
        List<OrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, order.getOrderNo())
                .orderByAsc(OrderItem::getId));
        OrderInvoice invoice = invoiceMapper.selectOne(new LambdaQueryWrapper<OrderInvoice>()
                .eq(OrderInvoice::getOrderNo, order.getOrderNo()));
        return assembler.toDTO(order, items, invoice);
    }

    /** 列表行不含明细，减少批量查询开销 */
    private OrderDTO toListDTO(Order order) {
        OrderInvoice invoice = invoiceMapper.selectOne(new LambdaQueryWrapper<OrderInvoice>()
                .eq(OrderInvoice::getOrderNo, order.getOrderNo()));
        return assembler.toDTO(order, List.of(), invoice);
    }
}
