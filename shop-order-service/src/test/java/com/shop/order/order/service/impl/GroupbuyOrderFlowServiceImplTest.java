package com.shop.order.order.service.impl;

import com.shop.api.order.dto.GroupFailedCommand;
import com.shop.api.order.dto.GroupPayRenewCommand;
import com.shop.api.order.dto.GroupSucceedCommand;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.pay.client.PayClient;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.common.result.Result;
import com.shop.order.mq.message.OrderDelayMessage;
import com.shop.order.mq.service.MqConsumeService;
import com.shop.order.order.entity.Order;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.order.service.OrderOperateService;
import com.shop.order.policy.PayTimeoutPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 拼团订单流转单测（B1，卡 232 行 6 用例）：
 * SUCCESS 仅续期待付款/已支付不动/重复无更新/deadline 不回退；
 * FAIL 未付→50 走 cancel_type=4 关单、已付退款恰好一次且重复不二次退、调用失败抛错等重试；
 * 无订单安全跳过；inner Feign 与 MQ 双路共享业务闸门幂等。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupbuyOrderFlowServiceImplTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private MqConsumeService consumeService;
    @Mock
    private OrderOperateService operateService;
    @Mock
    private PayClient payClient;
    @Mock
    private OutboxPublisher outboxPublisher;

    private GroupbuyOrderFlowServiceImpl service;

    private static final String GROUP_NO = "GB20260315001";
    private static final String UNPAID_1 = "260315031001000001";
    private static final String UNPAID_2 = "260315031001000002";
    private static final String PAID_1 = "260315031001000003";

    /** 模拟 t_order_mq_consume 的 event_id 唯一列：同一 eventId/业务键第二次起 false。 */
    private final Set<String> gates = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        service = new GroupbuyOrderFlowServiceImpl(orderMapper, consumeService, operateService,
                payClient, new PayTimeoutPolicy(), outboxPublisher);
        when(consumeService.firstTime(anyString(), anyString(), anyString()))
                .thenAnswer(inv -> gates.add(inv.getArgument(0)));
    }

    private Order order(String orderNo, int status, LocalDateTime expireTime) {
        Order o = new Order();
        o.setOrderNo(orderNo);
        o.setGroupNo(GROUP_NO);
        o.setUserId(1001L);
        o.setStatus(status);
        o.setExpireTime(expireTime);
        o.setPayFen(status == OrderStatuses.WAIT_SHIP ? 19900L : 0L);
        o.setPayMethod(2);
        return o;
    }

    // ---------------- SUCCESS ----------------

    @Test
    void onGroupSuccess_onlyRenewsUnpaid_andResendsTimeoutDelay() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(UNPAID_1, OrderStatuses.WAIT_PAY, LocalDateTime.now().plusMinutes(5)),
                order(PAID_1, OrderStatuses.WAIT_SHIP, LocalDateTime.now().plusHours(23))));
        when(orderMapper.batchUpdateExpireForUnpaid(eq(GROUP_NO), any())).thenReturn(1);

        service.onGroupSuccess(GROUP_NO);

        verify(orderMapper).batchUpdateExpireForUnpaid(eq(GROUP_NO), any());
        // 仅待付款单重投一笔 ORDER_PAY_TIMEOUT（30min 档）
        verify(outboxPublisher).publishDelay(anyString(), any(),
                any(OrderDelayMessage.class), eq(UNPAID_1 + "#gbrenew"), eq(1800L));
        verify(outboxPublisher, never()).publishDelay(anyString(), any(),
                any(OrderDelayMessage.class), eq(PAID_1), anyLong());
        // 已支付单不动
        verify(operateService, never()).groupFailCancel(anyString());
    }

    @Test
    void onGroupSuccess_duplicateSecondTime_noUpdateNoDelay() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(UNPAID_1, OrderStatuses.WAIT_PAY, LocalDateTime.now().plusMinutes(5))));
        when(orderMapper.batchUpdateExpireForUnpaid(eq(GROUP_NO), any())).thenReturn(1);

        service.onGroupSuccess(GROUP_NO);
        service.onGroupSuccess(GROUP_NO);

        verify(orderMapper, org.mockito.Mockito.times(1)).batchUpdateExpireForUnpaid(eq(GROUP_NO), any());
        verify(outboxPublisher, org.mockito.Mockito.times(1)).publishDelay(anyString(), any(),
                any(OrderDelayMessage.class), eq(UNPAID_1 + "#gbrenew"), eq(1800L));
    }

    @Test
    void onGroupSuccess_deadlineAlreadyWider_neverRollsBack() {
        // 当前 expire_time 已宽于 now+30min：CAS 谓词不命中，不重投延时（只宽不窄）
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(UNPAID_1, OrderStatuses.WAIT_PAY, LocalDateTime.now().plusHours(2))));
        when(orderMapper.batchUpdateExpireForUnpaid(eq(GROUP_NO), any())).thenReturn(0);

        service.onGroupSuccess(GROUP_NO);

        verify(outboxPublisher, never()).publishDelay(anyString(), any(),
                any(OrderDelayMessage.class), anyString(), anyLong());
    }

    // ---------------- FAIL ----------------

    @Test
    void onGroupFail_unpaid_cancelsWithType4() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(UNPAID_1, OrderStatuses.WAIT_PAY, LocalDateTime.now().plusMinutes(5))));

        service.onGroupFail(GROUP_NO);

        // 复用超时取消同路径（内部 markCancelled 10→50 cancel_type=4 + ORDER_CANCELLED outbox）
        verify(operateService).groupFailCancel(UNPAID_1);
        verify(payClient, never()).refund(any());
    }

    @Test
    void onGroupFail_paid_refundsExactlyOnceWithDeterministicRefundNo() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(PAID_1, OrderStatuses.WAIT_SHIP, LocalDateTime.now().plusHours(23))));
        when(payClient.refund(any())).thenReturn(Result.success());

        service.onGroupFail(GROUP_NO);
        // 重复事件：业务闸门拦截，不二次退款
        service.onGroupFail(GROUP_NO);

        ArgumentCaptor<CreateRefundCommand> captor = ArgumentCaptor.forClass(CreateRefundCommand.class);
        verify(payClient, org.mockito.Mockito.times(1)).refund(captor.capture());
        CreateRefundCommand cmd = captor.getValue();
        assertThat(cmd.getRefundNo()).isEqualTo("GB:" + PAID_1);
        assertThat(cmd.getOrderNo()).isEqualTo(PAID_1);
        assertThat(cmd.getAmountFen()).isEqualTo(19900L);
        verify(operateService, never()).groupFailCancel(anyString());
    }

    @Test
    void onGroupFail_refundCallFails_throwsForMqRetry_noFakeSuccess() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(PAID_1, OrderStatuses.WAIT_SHIP, LocalDateTime.now().plusHours(23))));
        when(payClient.refund(any()))
                .thenReturn(Result.fail(com.shop.common.exception.ErrorCode.DEPENDENCY_FAIL, "pay down"));

        assertThatThrownBy(() -> service.onGroupFail(GROUP_NO))
                .isInstanceOf(RuntimeException.class);
        verify(payClient, org.mockito.Mockito.times(1)).refund(any());
    }

    @Test
    void onGroupFail_mixedGroup_cancelsUnpaidAndRefundsPaid() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(UNPAID_1, OrderStatuses.WAIT_PAY, LocalDateTime.now().plusMinutes(5)),
                order(PAID_1, OrderStatuses.WAIT_SHIP, LocalDateTime.now().plusHours(23)),
                order(UNPAID_2, OrderStatuses.CANCELLED, LocalDateTime.now().minusHours(1))));
        when(payClient.refund(any())).thenReturn(Result.success());

        service.onGroupFail(GROUP_NO);

        verify(operateService).groupFailCancel(UNPAID_1);
        verify(payClient, org.mockito.Mockito.times(1)).refund(any());
        // 50 已取消单跳过
        verify(operateService, never()).groupFailCancel(UNPAID_2);
    }

    @Test
    void onGroupEvent_noOrders_safelySkipped() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of());

        service.onGroupSuccess(GROUP_NO);
        service.onGroupFail(GROUP_NO);

        verify(orderMapper, never()).batchUpdateExpireForUnpaid(anyString(), any());
        verify(operateService, never()).groupFailCancel(anyString());
        verify(payClient, never()).refund(any());
        verifyNoInteractions(outboxPublisher);
    }

    // ---------------- 双通路（MQ 批量事件 + inner Feign 逐单）幂等合并 ----------------

    @Test
    void mqSuccessThenFeignRenew_shareBusinessGate_singleProcessing() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(UNPAID_1, OrderStatuses.WAIT_PAY, LocalDateTime.now().plusMinutes(5))));
        when(orderMapper.batchUpdateExpireForUnpaid(eq(GROUP_NO), any())).thenReturn(1);

        // MQ 成团事件先到
        service.onGroupSuccess(GROUP_NO);
        // 营销侧 Feign 逐单 renew 后到（plusSeconds 与默认不同也不允许重复续期）
        service.renewGroupPayDeadline(GroupPayRenewCommand.builder()
                .groupNo(GROUP_NO).orderNo(UNPAID_1).plusSeconds(2400L).build());

        verify(orderMapper, org.mockito.Mockito.times(1)).batchUpdateExpireForUnpaid(eq(GROUP_NO), any());
        verify(outboxPublisher, org.mockito.Mockito.times(1)).publishDelay(anyString(), any(),
                any(OrderDelayMessage.class), eq(UNPAID_1 + "#gbrenew"), anyLong());
    }

    @Test
    void feignFailedThenMqFail_shareBusinessGate_refundOnce() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(PAID_1, OrderStatuses.WAIT_SHIP, LocalDateTime.now().plusHours(23))));
        when(payClient.refund(any())).thenReturn(Result.success());

        service.markFailed(GroupFailedCommand.builder().groupNo(GROUP_NO).orderNo(PAID_1).build());
        service.onGroupFail(GROUP_NO);

        verify(payClient, org.mockito.Mockito.times(1)).refund(any());
    }

    @Test
    void feignSucceed_sameGateAsMqSuccess() {
        when(orderMapper.selectByGroupNo(GROUP_NO)).thenReturn(List.of(
                order(UNPAID_1, OrderStatuses.WAIT_PAY, LocalDateTime.now().plusMinutes(5))));
        when(orderMapper.batchUpdateExpireForUnpaid(eq(GROUP_NO), any())).thenReturn(1);

        service.markSucceeded(GroupSucceedCommand.builder()
                .groupNo(GROUP_NO).orderNo(UNPAID_1).build());
        service.onGroupSuccess(GROUP_NO);

        verify(orderMapper, org.mockito.Mockito.times(1)).batchUpdateExpireForUnpaid(eq(GROUP_NO), any());
    }
}
