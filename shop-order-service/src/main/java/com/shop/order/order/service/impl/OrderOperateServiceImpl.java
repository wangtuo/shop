package com.shop.order.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.order.enums.CancelTypes;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.api.order.event.OrderCompletedEvent;
import com.shop.api.order.event.OrderConfirmedEvent;
import com.shop.api.order.event.OrderShippedEvent;
import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.AddressDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.order.cart.dto.CartAddRequest;
import com.shop.order.cart.service.CartService;
import com.shop.order.order.dto.AddressUpdateRequest;
import com.shop.order.order.dto.ShipRequest;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.order.service.OrderOperateService;
import com.shop.order.order.service.OrderPersister;
import com.shop.order.order.service.OrderQueryService;
import com.shop.order.policy.OrderTimePolicy;
import com.shop.order.statemachine.OrderStateMachine;
import com.shop.order.support.FeignResults;
import com.shop.order.support.OrderAssembler;
import com.shop.order.support.OrderResourceReleaser;
import com.shop.order.support.RegionDeliveryChecker;
import com.shop.order.mq.message.OrderDelayMessage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单操作服务实现（design 5.2 / 5.3.3 / 5.4）。
 *
 * <p>所有状态变更 DB 均带 {@code WHERE status=?} 条件，影响 0 行视为并发冲突；
 * 关键超时（支付超时/10 天自动收货/15 天售后期满）由延时消息 + ShedLock 扫描双保险触发。
 */
@Service
@RequiredArgsConstructor
public class OrderOperateServiceImpl implements OrderOperateService {

    private static final Logger log = LoggerFactory.getLogger(OrderOperateServiceImpl.class);

    private final OrderQueryService orderQueryService;
    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final OrderStateMachine stateMachine;
    private final OrderTimePolicy timePolicy;
    private final OrderResourceReleaser resourceReleaser;
    private final OrderAssembler assembler;
    private final CartService cartService;
    private final UserClient userClient;
    private final PayClient payClient;
    private final RegionDeliveryChecker regionDeliveryChecker;
    private final OrderPersister orderPersister;

    @Override
    public void cancel(String orderNo, Long userId) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        doCancel(order, CancelTypes.USER);
    }

    @Override
    public void timeoutCancel(String orderNo) {
        cancelUnpaidWithPayGuard(orderNo, CancelTypes.TIMEOUT, "支付超时", true);
    }

    @Override
    public void groupFailCancel(String orderNo) {
        cancelUnpaidWithPayGuard(orderNo, CancelTypes.GROUPBUY_FAIL, "拼团失败", false);
    }

    private void cancelUnpaidWithPayGuard(String orderNo, int cancelType, String reason,
                                          boolean requireExpired) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        // 订单仍为待付款才取消（已支付则忽略，幂等）
        if (order.getStatus() == null || order.getStatus() != OrderStatuses.WAIT_PAY) {
            log.info("{}关单消息到达但订单非待付款，忽略 orderNo={} status={}", reason, orderNo, order.getStatus());
            return;
        }
        // R4-25：拼团成团会把未付款单 expire_time 顺延（并重投一轮新的支付超时延时消息）。
        // 下单时登记的原始超时消息在旧截止点到达，必须以库内 expire_time 为准放行：
        // 未到新截止点说明是已被续期取代的旧消息，忽略；新截止点的续期消息/60s 扫表兜底关单。
        // 拼团失败关单不受截止点约束（requireExpired=false）。
        if (requireExpired && order.getExpireTime() != null
                && LocalDateTime.now().isBefore(order.getExpireTime())) {
            log.info("{}关单消息先于（可能已被拼团续期的）支付截止点到达，忽略 orderNo={} expireTime={}",
                    reason, orderNo, order.getExpireTime());
            return;
        }
        // P1-7 修复：取消前必须向支付域反查。异步回调可能在超时点前后才落账，
        // 仅凭本域 10 状态关单会造成「钱已收、单已关」。支付单成功 → 保留订单等事件/对账追平；
        // 支付域暂时不可达 → 抛错本轮跳过，下轮扫描重试（fail-safe，宁晚关不错关）。
        if (order.getPayNo() != null && !order.getPayNo().isBlank()) {
            PaymentDTO payment = FeignResults.unwrap(payClient.getByPayNo(order.getPayNo()));
            if (payment != null && PayStatuses.SUCCESS.getCode() == payment.getStatus()) {
                log.warn("{}关单被支付域成功态拦截，保留订单等待履约/对账 orderNo={} payNo={}",
                        reason, orderNo, order.getPayNo());
                return;
            }
        }
        doCancel(order, cancelType);
    }

    private void doCancel(Order order, int cancelType) {
        stateMachine.assertCancelable(order.getStatus());
        List<OrderItem> items = loadItems(order.getOrderNo());
        OrderCancelledEvent event = OrderCancelledEvent.builder()
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .cancelType(cancelType)
                .usedPointsFen(order.getPointsDeductFen())
                .userCouponId(order.getUserCouponId())
                .seckillActivityId(order.getSeckillActivityId())
                .items(assembler.toMessages(items))
                .build();
        event.setBizNo(order.getOrderNo());
        // 条件关单 + ORDER_CANCELLED 发件箱同事务（独立 Bean 保证事务边界）
        int rows = orderPersister.cancel(order, cancelType, LocalDateTime.now(), event);
        if (rows == 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "订单状态已变化，取消失败");
        }
        // 事务提交后直接逆序释放（与事件消费双保险，各域按 orderNo 幂等）
        resourceReleaser.releaseAll(order, items, true);
    }

    @Override
    public void ship(String orderNo, Long merchantId, ShipRequest request) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (merchantId == null || !merchantId.equals(order.getMerchantId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能为本店铺订单发货");
        }
        stateMachine.assertTransition(order.getStatus(), OrderStatuses.WAIT_RECEIVE);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = timePolicy.autoConfirmDeadline(now);
        List<OrderItem> items = loadItems(orderNo);
        OrderShippedEvent event = OrderShippedEvent.builder()
                .orderNo(orderNo)
                .userId(order.getUserId())
                .logisticsNo(request.getLogisticsNo())
                .autoConfirmDeadline(deadline.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                // FUNDS B11：运费险标识/保费随发货事件下发，售后域据此建理赔窗口
                .hasFreightInsurance(order.getHasFreightInsurance() == null ? 0 : order.getHasFreightInsurance())
                .insurancePremiumFen(nz(order.getInsurancePremiumFen()))
                .items(assembler.toMessages(items))
                .build();
        event.setBizNo(orderNo);

        // 双保险之一：发货后 10 天自动确认收货延时消息
        OrderDelayMessage delay = OrderDelayMessage.builder().orderNo(orderNo).build();
        delay.setBizNo(orderNo);
        // 条件发货 + ORDER_SHIPPED + 自动确认延时同事务
        int rows = orderPersister.ship(orderNo, request.getLogisticsNo(),
                request.getLogisticsCompany() == null ? "" : request.getLogisticsCompany(),
                now, deadline, event, delay);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "发货失败，订单状态已变化");
        }
    }

    @Override
    public void confirm(String orderNo, Long userId) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        stateMachine.assertTransition(order.getStatus(), OrderStatuses.COMPLETED);
        doConfirm(order);
    }

    @Override
    public void autoConfirm(String orderNo) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        // 条件更新本身只接受 30→40：售后中(60/61/62)等状态自动跳过
        Integer updated = doConfirmIfWaitReceive(order);
        if (updated == null || updated == 0) {
            log.info("自动收货跳过：订单非待收货 orderNo={} status={}", orderNo, order.getStatus());
        }
    }

    private Integer doConfirmIfWaitReceive(Order order) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime aftersaleDeadline = timePolicy.aftersaleDeadline(now);
        List<OrderItem> items = loadItems(order.getOrderNo());
        long freight = nz(order.getFreightFen());
        long totalPay = nz(order.getPayFen());
        OrderConfirmedEvent event = OrderConfirmedEvent.builder()
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .merchantId(order.getMerchantId())
                .productPayFen(Math.max(0L, totalPay - freight))
                .freightFen(freight)
                .shopDiscountFen(nz(order.getShopDiscountFen()))
                .platformCouponFen(nz(order.getPlatformDiscountFen()))
                .pointsDeductFen(nz(order.getPointsDeductFen()))
                .totalPayFen(totalPay)
                // FUNDS B11：运费险标识/保费随确认事件下发（保费不退，清算单列平台保险收入）
                .hasFreightInsurance(order.getHasFreightInsurance() == null ? 0 : order.getHasFreightInsurance())
                .insurancePremiumFen(nz(order.getInsurancePremiumFen()))
                .items(assembler.toMessages(items))
                .build();
        event.setBizNo(order.getOrderNo());

        // 双保险之一：完成后 15 天售后期结束延时消息
        OrderDelayMessage delay = OrderDelayMessage.builder().orderNo(order.getOrderNo()).build();
        delay.setBizNo(order.getOrderNo());
        // 条件确认收货 + ORDER_CONFIRMED + 售后期满延时同事务
        return orderPersister.confirm(order, now, aftersaleDeadline, event, delay);
    }

    private void doConfirm(Order order) {
        int rows = doConfirmIfWaitReceive(order);
        if (rows == 0) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "确认收货失败，订单状态已变化");
        }
    }

    @Override
    public void closeAftersaleWindow(String orderNo) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (order.getStatus() == null || order.getStatus() != OrderStatuses.COMPLETED) {
            return;
        }
        if (order.getAftersaleDeadline() == null || order.getAftersaleDeadline().isAfter(LocalDateTime.now())) {
            return;
        }
        // 仍有进行中售后的订单不关单
        Long active = orderItemMapper.selectCount(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo)
                .in(OrderItem::getAftersaleStatus, 1, 2, 4, 5));
        if (active != null && active > 0) {
            return;
        }
        OrderCompletedEvent event = OrderCompletedEvent.builder()
                .orderNo(orderNo)
                .userId(order.getUserId())
                .merchantId(order.getMerchantId())
                .build();
        event.setBizNo(orderNo);
        // 条件关单 + ORDER_COMPLETED 同事务
        orderPersister.close(orderNo, LocalDateTime.now(), event);
    }

    @Override
    public void updateAddress(String orderNo, Long userId, AddressUpdateRequest request) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatuses.WAIT_SHIP) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "仅待发货订单可以修改地址");
        }
        AddressDTO address = FeignResults.unwrap(userClient.getAddress(request.getAddressId()));
        regionDeliveryChecker.check(address, userId);
        int rows = orderMapper.updateAddress(orderNo, address.getReceiver(), address.getPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(),
                address.getDetailAddress(), request.getAddressId());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "修改地址失败，订单状态已变化");
        }
    }

    @Override
    public void remindShip(String orderNo, Long userId) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (order.getStatus() == null || order.getStatus() != OrderStatuses.WAIT_SHIP) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "仅待发货订单可以提醒发货");
        }
        int rows = orderMapper.markRemind(orderNo, LocalDateTime.now());
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT, "提醒失败，订单状态已变化");
        }
        // 只做通知标记，不产生跨域写动作
        log.info("买家提醒发货 orderNo={} userId={}", orderNo, userId);
    }

    @Override
    public void delete(String orderNo, Long userId) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (order.getStatus() == null
                || (order.getStatus() != OrderStatuses.CANCELLED && order.getStatus() != OrderStatuses.CLOSED)) {
            throw new BizException(ErrorCode.ORDER_STATUS_ERROR, "仅已取消/已关闭订单可以删除");
        }
        orderMapper.deleteById(order.getId());
    }

    @Override
    public void rebuy(String orderNo, Long userId) {
        Order order = orderQueryService.requireByOrderNo(orderNo);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        List<OrderItem> items = loadItems(orderNo);
        for (OrderItem item : items) {
            CartAddRequest request = new CartAddRequest();
            request.setSkuId(item.getSkuId());
            request.setQty(Math.min(item.getQty(), 99));
            cartService.add(userId, request);
        }
    }

    private List<OrderItem> loadItems(String orderNo) {
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderNo, orderNo)
                .orderByAsc(OrderItem::getId));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
