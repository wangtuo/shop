package com.shop.order.order.service;

import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.order.event.OrderCompletedEvent;
import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.order.cart.mapper.CartItemMapper;
import com.shop.order.invoice.entity.OrderInvoice;
import com.shop.order.invoice.mapper.OrderInvoiceMapper;
import com.shop.order.mq.message.OrderDelayMessage;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.order.support.OrderDelayTopics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单落库器：订单主表 + 明细 + 发票 + 购物车清理 + 跨域事件登记在同一事务内完成
 * （design 5.3.2，独立 Bean 保证 {@code @Transactional} 代理生效）。
 *
 * <p><b>事务性发件箱（P1-1）：</b>所有 ORDER_* 事件与三个内部延时消息均通过
 * {@link OutboxPublisher} 与业务状态同事务写入 shop_order.t_mq_outbox，由 OutboxRelayJob
 * 异步投递。状态落库与发事件原子：要么都不发生（回滚），要么事件必被中继（at-least-once，
 * 消费端按 orderNo 幂等）。状态条件更新影响 0 行时不登记事件。
 */
@Component
@RequiredArgsConstructor
public class OrderPersister {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderInvoiceMapper invoiceMapper;
    private final CartItemMapper cartItemMapper;
    private final OutboxPublisher outboxPublisher;

    /**
     * 下单落库：主单/明细/发票/购物车清理 + ORDER_CREATED 事件 + 支付超时延时消息同事务。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void persist(OrderAggregate aggregate, Object createdEvent, Object payTimeoutDelay) {
        Order order = aggregate.getOrder();
        orderMapper.insert(order);
        for (OrderItem item : aggregate.getItems()) {
            orderItemMapper.insert(item);
        }
        OrderInvoice invoice = aggregate.getInvoice();
        if (invoice != null) {
            invoiceMapper.insert(invoice);
        }
        List<Long> cartIds = aggregate.getCartIdsToClear();
        if (cartIds != null && !cartIds.isEmpty()) {
            // 逻辑删除值写入各行自身 id：清空后用户可立即重新加购同一 SKU（uk 含 deleted）
            cartItemMapper.update(null, new LambdaUpdateWrapper<com.shop.order.cart.entity.CartItem>()
                    .eq(com.shop.order.cart.entity.CartItem::getUserId, order.getUserId())
                    .in(com.shop.order.cart.entity.CartItem::getId, aggregate.getCartIdsToClear())
                    .setSql("deleted = id"));
        }
        // 事件与订单同提交：提交前崩溃无幽灵消息，提交后崩溃中继必补发
        outboxPublisher.publish(MqTopics.ORDER_CREATED, null, createdEvent, order.getOrderNo());
        outboxPublisher.publishDelay(OrderDelayTopics.ORDER_PAY_TIMEOUT, null, payTimeoutDelay,
                order.getOrderNo(), aggregate.getExpirePaySeconds() == null ? 0L : aggregate.getExpirePaySeconds());
    }

    /**
     * 条件关单（WHERE status 可取消）+ ORDER_CANCELLED 同事务。
     *
     * @return 影响行数；0 表示并发状态冲突，调用方据此抛错且不释放资源
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public int cancel(Order order, int cancelType, LocalDateTime now, OrderCancelledEvent event) {
        int rows = orderMapper.markCancelled(order.getOrderNo(), cancelType, now);
        if (rows > 0) {
            outboxPublisher.publish(MqTopics.ORDER_CANCELLED, null, event, order.getOrderNo());
        }
        return rows;
    }

    /**
     * 条件发货（待发货→待收货）+ ORDER_SHIPPED + 10 天自动确认收货延时消息同事务。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public int ship(String orderNo, String logisticsNo, String logisticsCompany,
                    LocalDateTime now, LocalDateTime autoConfirmDeadline,
                    OrderShippedEvent shippedEvent, OrderDelayMessage autoConfirmDelay) {
        int rows = orderMapper.markShipped(orderNo, logisticsNo, logisticsCompany, now, autoConfirmDeadline);
        if (rows > 0) {
            outboxPublisher.publish(MqTopics.ORDER_SHIPPED, null, shippedEvent, orderNo);
            outboxPublisher.publishDelay(OrderDelayTopics.ORDER_AUTO_CONFIRM, null, autoConfirmDelay,
                    orderNo, MqTopics.DELAY_10_DAY_SECONDS);
        }
        return rows;
    }

    /**
     * 条件确认收货（待收货→已完成）+ ORDER_CONFIRMED + 15 天售后期满延时消息同事务。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public int confirm(Order order, LocalDateTime now, LocalDateTime aftersaleDeadline,
                       OrderConfirmedEvent confirmedEvent, OrderDelayMessage aftersaleWindowDelay) {
        int rows = orderMapper.markConfirmed(order.getOrderNo(), now, aftersaleDeadline);
        if (rows > 0) {
            outboxPublisher.publish(MqTopics.ORDER_CONFIRMED, null, confirmedEvent, order.getOrderNo());
            outboxPublisher.publishDelay(OrderDelayTopics.ORDER_AFTERSALE_WINDOW, null, aftersaleWindowDelay,
                    order.getOrderNo(), MqTopics.DELAY_15_DAY_SECONDS);
        }
        return rows;
    }

    /**
     * 条件关单（已完成→已关闭，售后期满）+ ORDER_COMPLETED 同事务。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public boolean close(String orderNo, LocalDateTime now, OrderCompletedEvent event) {
        int rows = orderMapper.markClosed(orderNo, now);
        if (rows > 0) {
            outboxPublisher.publish(MqTopics.ORDER_COMPLETED, null, event, orderNo);
        }
        return rows > 0;
    }
}
