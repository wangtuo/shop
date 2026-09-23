package com.shop.order.mq.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.pay.enums.RefundTypes;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.pay.event.RefundSucceededEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.MoneyUtils;
import com.shop.order.invoice.service.InvoiceService;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付/退款事件消费（ORDER_PAID / REFUND_SUCCESS），流水幂等 + 条件更新。
 */
@Service
@RequiredArgsConstructor
public class PayEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PayEventConsumer.class);

    private final MqConsumeService consumeService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final InvoiceService invoiceService;

    /**
     * ORDER_PAID：订单 10→20，回写支付方式/流水号/支付时间。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onPaid(PaymentSucceededEvent e) {
        if (!consumeService.firstTime(e.getEventId(), MqTopics.ORDER_PAID, e.getOrderNo())) {
            return;
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, e.getOrderNo()));
        if (order == null) {
            // FUNDS B10 守卫：保证金缴费（payScene=4）的支付成功事件不属于本域业务订单，
            // 消费记录已落（上面 firstTime），直接 ACK no-op，严禁抛错形成毒丸反复重试。
            if (e.getPayScene() != null && e.getPayScene() == PayScenes.DEPOSIT) {
                log.info("ORDER_PAID 保证金缴费事件无对应业务订单，ACK no-op orderNo={} payNo={}",
                        e.getOrderNo(), e.getPayNo());
                return;
            }
            // 商品订单尚未落库/异常：抛出由 Broker 重试
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "支付事件找不到订单: " + e.getOrderNo());
        }
        LocalDateTime payTime = e.getPaidTime() == null ? LocalDateTime.now() : e.getPaidTime();
        int rows = orderMapper.markPaid(e.getOrderNo(), e.getPayMethod(), e.getPayNo(),
                e.getChannelTransactionNo(), payTime);
        log.info("ORDER_PAID 消费 orderNo={} 状态推进行数={}（0 表示重复/已处理）", e.getOrderNo(), rows);
    }

    /**
     * REFUND_SUCCESS：按 aftersaleNo 回写明细退款；全额退款整单关闭并冲红发票。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onRefunded(RefundSucceededEvent e) {
        if (!consumeService.firstTime(e.getEventId(), MqTopics.REFUND_SUCCESS, e.getRefundNo())) {
            return;
        }
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderNo, e.getOrderNo()));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "退款事件找不到订单: " + e.getOrderNo());
        }

        List<OrderItem> items = List.of();
        if (e.getAftersaleNo() != null && !e.getAftersaleNo().isBlank()) {
            items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                    .eq(OrderItem::getAftersaleNo, e.getAftersaleNo()));
            distributeRefund(items, nz(e.getAmountFen()));
        }

        if (e.getRefundType() != null && e.getRefundType() == RefundTypes.FULL.getCode()) {
            int rows = orderMapper.closeAfterAftersale(e.getOrderNo(), LocalDateTime.now());
            log.info("REFUND_SUCCESS 全额退款关单 orderNo={} rows={}", e.getOrderNo(), rows);
            // 退款自动冲红（监听 REFUND_SUCCESS 标记红冲，design 5.5）
            invoiceService.redFlush(e.getOrderNo());
        } else {
            log.info("REFUND_SUCCESS 部分退款回写完成 orderNo={} amount={}", e.getOrderNo(), e.getAmountFen());
        }
    }

    /**
     * 按明细实付占比分摊本次退款（最大余数法，不差 1 分），回写累计退款额。
     */
    private void distributeRefund(List<OrderItem> items, long amountFen) {
        if (items.isEmpty()) {
            return;
        }
        List<Long> weights = items.stream().map(i -> nz(i.getPaidFen())).toList();
        List<Long> shares = MoneyUtils.allocate(amountFen, weights);
        for (int i = 0; i < items.size(); i++) {
            orderItemMapper.markRefunded(items.get(i).getId(), shares.get(i));
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
