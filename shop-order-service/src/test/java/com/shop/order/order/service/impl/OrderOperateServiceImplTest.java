package com.shop.order.order.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.shop.common.result.Result;
import com.shop.order.cart.service.CartService;
import com.shop.order.mq.message.OrderDelayMessage;
import com.shop.order.order.dto.AddressUpdateRequest;
import com.shop.order.order.dto.ShipRequest;
import com.shop.order.order.entity.Order;
import com.shop.order.order.entity.OrderItem;
import com.shop.order.order.mapper.OrderItemMapper;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.order.service.OrderPersister;
import com.shop.order.order.service.OrderQueryService;
import com.shop.order.policy.OrderTimePolicy;
import com.shop.order.statemachine.OrderStateMachine;
import com.shop.order.support.OrderAssembler;
import com.shop.order.support.OrderResourceReleaser;
import com.shop.order.support.RegionDeliveryChecker;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单操作单测（design 5.2 / 5.3.3）：取消/发货/确认/自动确认/售后期关闭/地址/催发/删除/再来一单，
 * 含归属鉴权、非法状态拒绝、条件更新 0 行处理与事件、10 天/15 天延时双保险。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderOperateServiceImplTest {

    @Mock
    private OrderQueryService orderQueryService;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private OrderResourceReleaser resourceReleaser;
    @Mock
    private OrderAssembler assembler;
    @Mock
    private CartService cartService;
    @Mock
    private UserClient userClient;
    @Mock
    private PayClient payClient;
    @Mock
    private OrderPersister orderPersister;

    private OrderOperateServiceImpl service;

    private static final String NO = "260315011001000001";
    private static final Long USER_ID = 1001L;
    private static final Long MERCHANT_ID = 3001L;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderItem.class);
    }

    @BeforeEach
    void setUp() {
        service = new OrderOperateServiceImpl(orderQueryService, orderMapper, orderItemMapper,
                new OrderStateMachine(), new OrderTimePolicy(), resourceReleaser, assembler,
                cartService, userClient, payClient, new RegionDeliveryChecker(), orderPersister);
        when(orderItemMapper.selectList(any())).thenReturn(List.of());
    }

    private Order order(int status) {
        Order o = new Order();
        o.setId(1L);
        o.setOrderNo(NO);
        o.setUserId(USER_ID);
        o.setMerchantId(MERCHANT_ID);
        o.setStatus(status);
        o.setPayFen(20000L);
        o.setFreightFen(0L);
        o.setPointsDeductFen(0L);
        return o;
    }

    private ShipRequest shipRequest() {
        ShipRequest req = new ShipRequest();
        req.setLogisticsNo("SF123");
        req.setLogisticsCompany("顺丰");
        return req;
    }

    // ---------------- 取消 ----------------

    @Test
    void cancel_waitPay_marksCancelledReleasesAndSendsEvent() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_PAY));
        when(orderPersister.cancel(any(), eq(1), any(), any(OrderCancelledEvent.class))).thenReturn(1);

        service.cancel(NO, USER_ID);

        verify(orderPersister).cancel(any(), eq(1), any(), any(OrderCancelledEvent.class));
        verify(resourceReleaser).releaseAll(any(), any(), eq(true));
    }

    @Test
    void cancel_notOwner_forbidden() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_PAY));
        assertThatThrownBy(() -> service.cancel(NO, 2002L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
        verify(orderPersister, never()).cancel(any(), anyInt(), any(), any());
    }

    @Test
    void cancel_paidOrder_rejectedMustUseAftersale() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_SHIP));
        assertThatThrownBy(() -> service.cancel(NO, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
        verify(orderPersister, never()).cancel(any(), anyInt(), any(), any());
    }

    @Test
    void timeoutCancel_alreadyPaid_isNoop() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_SHIP));
        service.timeoutCancel(NO);
        verify(orderPersister, never()).cancel(any(), anyInt(), any(), any());
    }

    @Test
    void timeoutCancel_waitPay_cancelsWithTimeoutType() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_PAY));
        when(orderPersister.cancel(any(), eq(2), any(), any(OrderCancelledEvent.class))).thenReturn(1);

        service.timeoutCancel(NO);

        verify(orderPersister).cancel(any(), eq(2), any(), any(OrderCancelledEvent.class));
        verify(resourceReleaser).releaseAll(any(), any(), eq(true));
    }

    @Test
    void timeoutCancel_支付域已成功_拦截关单并保留订单() {
        Order o = order(OrderStatuses.WAIT_PAY);
        o.setPayNo("PAY-1");
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(o);
        PaymentDTO paid = new PaymentDTO();
        paid.setStatus(PayStatuses.SUCCESS.getCode());
        when(payClient.getByPayNo("PAY-1")).thenReturn(Result.success(paid));

        service.timeoutCancel(NO);

        verify(orderPersister, never()).cancel(any(), anyInt(), any(), any());
        verify(resourceReleaser, never()).releaseAll(any(), any(), anyBoolean());
    }

    @Test
    void timeoutCancel_支付域未成功_正常超时关单() {
        Order o = order(OrderStatuses.WAIT_PAY);
        o.setPayNo("PAY-2");
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(o);
        PaymentDTO pending = new PaymentDTO();
        pending.setStatus(PayStatuses.WAIT.getCode());
        when(payClient.getByPayNo("PAY-2")).thenReturn(Result.success(pending));
        when(orderPersister.cancel(any(), eq(2), any(), any(OrderCancelledEvent.class))).thenReturn(1);

        service.timeoutCancel(NO);

        verify(orderPersister).cancel(any(), eq(2), any(), any(OrderCancelledEvent.class));
    }

    @Test
    void timeoutCancel_支付域不可达_failSafe跳过等下轮重试() {
        Order o = order(OrderStatuses.WAIT_PAY);
        o.setPayNo("PAY-3");
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(o);
        when(payClient.getByPayNo("PAY-3")).thenThrow(new RuntimeException("feign connect refused"));

        assertThatThrownBy(() -> service.timeoutCancel(NO)).isInstanceOf(RuntimeException.class);
        verify(orderPersister, never()).cancel(any(), anyInt(), any(), any());
    }

    // ---------------- B1 拼团失败关单（cancelType=4，与超时同路径） ----------------

    @Test
    void groupFailCancel_waitPay_cancelsWithGroupbuyFailType() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_PAY));
        when(orderPersister.cancel(any(), eq(4), any(), any(OrderCancelledEvent.class))).thenReturn(1);

        service.groupFailCancel(NO);

        verify(orderPersister).cancel(any(), eq(4), any(), any(OrderCancelledEvent.class));
        verify(resourceReleaser).releaseAll(any(), any(), eq(true));
    }

    @Test
    void groupFailCancel_alreadyPaid_isNoop() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_SHIP));
        service.groupFailCancel(NO);
        verify(orderPersister, never()).cancel(any(), anyInt(), any(), any());
    }

    @Test
    void groupFailCancel_支付域已成功_拦截不错关() {
        Order o = order(OrderStatuses.WAIT_PAY);
        o.setPayNo("PAY-GB");
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(o);
        PaymentDTO paid = new PaymentDTO();
        paid.setStatus(PayStatuses.SUCCESS.getCode());
        when(payClient.getByPayNo("PAY-GB")).thenReturn(Result.success(paid));

        service.groupFailCancel(NO);

        verify(orderPersister, never()).cancel(any(), anyInt(), any(), any());
    }

    // ---------------- 发货 ----------------

    @Test
    void ship_waitShip_marksShippedSendsEventAnd10DayDelay() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_SHIP));
        when(orderPersister.ship(eq(NO), eq("SF123"), eq("顺丰"), any(), any(),
                any(OrderShippedEvent.class), any(OrderDelayMessage.class))).thenReturn(1);

        service.ship(NO, MERCHANT_ID, shipRequest());

        verify(orderPersister).ship(eq(NO), eq("SF123"), eq("顺丰"), any(), any(),
                any(OrderShippedEvent.class), any(OrderDelayMessage.class));
    }

    @Test
    void ship_otherMerchant_forbidden() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_SHIP));
        assertThatThrownBy(() -> service.ship(NO, 9999L, shipRequest()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());
        verify(orderPersister, never()).ship(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void ship_waitPay_illegalStatus() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_PAY));
        assertThatThrownBy(() -> service.ship(NO, MERCHANT_ID, shipRequest()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
    }

    // ---------------- 确认收货 ----------------

    @Test
    void confirm_waitReceive_marksConfirmedSendsEventAnd15DayDelay() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_RECEIVE));
        when(orderPersister.confirm(any(), any(), any(),
                any(OrderConfirmedEvent.class), any(OrderDelayMessage.class))).thenReturn(1);

        service.confirm(NO, USER_ID);

        verify(orderPersister).confirm(any(), any(), any(),
                any(OrderConfirmedEvent.class), any(OrderDelayMessage.class));
    }

    @Test
    void confirm_zeroRows_throwsStatusError() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_RECEIVE));
        when(orderPersister.confirm(any(), any(), any(),
                any(OrderConfirmedEvent.class), any(OrderDelayMessage.class))).thenReturn(0);
        assertThatThrownBy(() -> service.confirm(NO, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
    }

    @Test
    void autoConfirm_duringAftersale_skipsSilently() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.REFUNDING));
        when(orderPersister.confirm(any(), any(), any(),
                any(OrderConfirmedEvent.class), any(OrderDelayMessage.class))).thenReturn(0);

        service.autoConfirm(NO);

        // 0 行（售后中等状态）：事件在落库器内随条件更新，未更新即无事件
        verify(orderPersister).confirm(any(), any(), any(),
                any(OrderConfirmedEvent.class), any(OrderDelayMessage.class));
    }

    // ---------------- 售后期结束 ----------------

    @Test
    void closeAftersaleWindow_completedPastDeadlineNoActiveAftersale_closes() {
        Order o = order(OrderStatuses.COMPLETED);
        o.setAftersaleDeadline(LocalDateTime.now().minusDays(1));
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(o);
        when(orderItemMapper.selectCount(any())).thenReturn(0L);
        when(orderPersister.close(eq(NO), any(), any(OrderCompletedEvent.class))).thenReturn(true);

        service.closeAftersaleWindow(NO);

        verify(orderPersister).close(eq(NO), any(), any(OrderCompletedEvent.class));
    }

    @Test
    void closeAftersaleWindow_activeAftersaleExists_skips() {
        Order o = order(OrderStatuses.COMPLETED);
        o.setAftersaleDeadline(LocalDateTime.now().minusDays(1));
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(o);
        when(orderItemMapper.selectCount(any())).thenReturn(1L);

        service.closeAftersaleWindow(NO);

        verify(orderPersister, never()).close(any(), any(), any());
    }

    @Test
    void closeAftersaleWindow_deadlineNotReached_skips() {
        Order o = order(OrderStatuses.COMPLETED);
        o.setAftersaleDeadline(LocalDateTime.now().plusDays(3));
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(o);

        service.closeAftersaleWindow(NO);

        verify(orderPersister, never()).close(any(), any(), any());
        verify(orderItemMapper, never()).selectCount(any());
    }

    @Test
    void closeAftersaleWindow_notCompleted_skips() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_RECEIVE));
        service.closeAftersaleWindow(NO);
        verify(orderPersister, never()).close(any(), any(), any());
    }

    // ---------------- 地址/催发/删除/再来一单 ----------------

    @Test
    void updateAddress_waitShip_updates() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_SHIP));
        AddressDTO address = AddressDTO.builder()
                .addressId(9002L).userId(USER_ID).receiver("李四")
                .phone("13900000000").province("江苏省").city("南京市")
                .district("玄武区").detailAddress("中山路 1 号").build();
        when(userClient.getAddress(9002L)).thenReturn(Result.success(address));
        when(orderMapper.updateAddress(any(), any(), any(), any(), any(), any(), any(), anyLong()))
                .thenReturn(1);

        AddressUpdateRequest req = new AddressUpdateRequest();
        req.setAddressId(9002L);
        service.updateAddress(NO, USER_ID, req);

        verify(orderMapper).updateAddress(eq(NO), eq("李四"), eq("13900000000"),
                eq("江苏省"), eq("南京市"), eq("玄武区"), eq("中山路 1 号"), eq(9002L));
    }

    @Test
    void updateAddress_waitReceive_rejected() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_RECEIVE));
        AddressUpdateRequest req = new AddressUpdateRequest();
        req.setAddressId(9002L);
        assertThatThrownBy(() -> service.updateAddress(NO, USER_ID, req))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
    }

    @Test
    void remindShip_waitShip_marksOnly() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_SHIP));
        when(orderMapper.markRemind(eq(NO), any())).thenReturn(1);

        service.remindShip(NO, USER_ID);

        verify(orderMapper).markRemind(eq(NO), any());
        // 催发仅标记，不产生跨域事件（无任何状态流转落库器调用）
        verify(orderPersister, never()).ship(any(), any(), any(), any(), any(), any(), any());
        verify(orderPersister, never()).confirm(any(), any(), any(), any(), any());
    }

    @Test
    void delete_cancelledOrClosed_succeeds() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.CANCELLED));
        service.delete(NO, USER_ID);
        verify(orderMapper).deleteById(1L);

        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.CLOSED));
        service.delete(NO, USER_ID);
        verify(orderMapper, times(2)).deleteById(1L);
    }

    @Test
    void delete_activeOrder_rejected() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.WAIT_RECEIVE));
        assertThatThrownBy(() -> service.delete(NO, USER_ID))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_STATUS_ERROR.getCode());
        verify(orderMapper, never()).deleteById(anyLong());
    }

    @Test
    void rebuy_backfillsCartWithQtyCappedAt99() {
        when(orderQueryService.requireByOrderNo(NO)).thenReturn(order(OrderStatuses.CLOSED));
        OrderItem i1 = new OrderItem();
        i1.setSkuId(11L);
        i1.setQty(2);
        OrderItem i2 = new OrderItem();
        i2.setSkuId(12L);
        i2.setQty(150);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(i1, i2));

        service.rebuy(NO, USER_ID);

        verify(cartService).add(eq(USER_ID), org.mockito.ArgumentMatchers.argThat(
                r -> r.getSkuId() == 11L && r.getQty() == 2));
        verify(cartService).add(eq(USER_ID), org.mockito.ArgumentMatchers.argThat(
                r -> r.getSkuId() == 12L && r.getQty() == 99));
    }
}
