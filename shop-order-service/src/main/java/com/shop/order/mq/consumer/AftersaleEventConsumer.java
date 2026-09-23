package com.shop.order.mq.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.aftersale.dto.AftersaleItemMessage;
import com.shop.api.aftersale.event.AftersaleChangedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.support.AftersaleStatusMapping;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AFTERSALE_CHANGED 消费：同步明细售后状态，推进订单整单 60/61/62/70。
 */
@Service
@RequiredArgsConstructor
public class AftersaleEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AftersaleEventConsumer.class);

    private final MqConsumeService consumeService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final AftersaleStatusMapping mapping;

    @Transactional(rollbackFor = Exception.class)
    public void onAftersaleChanged(AftersaleChangedEvent e) {
        if (!consumeService.firstTime(e.getEventId(), MqTopics.AFTERSALE_CHANGED, e.getAftersaleNo())) {
            return;
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, e.getOrderNo()));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "售后事件找不到订单: " + e.getOrderNo());
        }

        // 1. 明细状态同步（申请中/退货中/换货中/退款中/完成）
        if (e.getItems() != null) {
            for (AftersaleItemMessage line : e.getItems()) {
                OrderItem item = orderItemMapper.selectById(line.getOrderItemId());
                if (item == null || !e.getOrderNo().equals(item.getOrderNo())) {
                    log.warn("售后事件明细不存在或订单不匹配 aftersaleNo={} itemId={}",
                            e.getAftersaleNo(), line.getOrderItemId());
                    continue;
                }
                int target = mapping.mapItemStatus(nz(e.getType()), nz(e.getNewStatus()));
                orderItemMapper.forceAftersaleStatus(item.getId(), target, e.getAftersaleNo());
            }
        }

        // 2. 订单整单状态推进
        int targetOrder = mapping.mapOrderStatus(nz(e.getType()), nz(e.getNewStatus()));
        if (targetOrder == AftersaleStatusMapping.ORDER_RESUME) {
            int rows = orderMapper.resumeAftersaleByNo(e.getOrderNo());
            log.info("售后终结恢复履约 orderNo={} rows={}", e.getOrderNo(), rows);
        } else if (targetOrder == AftersaleStatusMapping.ORDER_KEEP) {
            log.info("售后终结等待 REFUND_SUCCESS 决定关单 orderNo={}", e.getOrderNo());
        } else {
            int rows = orderMapper.enterAftersale(e.getOrderNo(), targetOrder);
            if (rows == 0) {
                // 已在售后态：允许 60/61/62 之间随售后类型迁移
                rows = orderMapper.moveWithinAftersale(e.getOrderNo(), targetOrder);
            }
            log.info("售后事件推进订单状态 orderNo={} target={} rows={}", e.getOrderNo(), targetOrder, rows);
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
