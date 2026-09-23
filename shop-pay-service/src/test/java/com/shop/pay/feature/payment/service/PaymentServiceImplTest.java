package com.shop.pay.feature.payment.service;

import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.pay.dto.CreatePaymentCommand;
import com.shop.api.pay.dto.PaymentDTO;
import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.enums.PayScenes;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.event.PaymentSucceededEvent;
import com.shop.api.user.client.UserClient;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.pay.channel.ChannelPayResult;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.channel.ChannelQueryResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.channel.PayChannelClient;
import com.shop.pay.feature.payment.dto.ChannelNotifyParams;
import com.shop.pay.feature.payment.dto.PayCreateRequest;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.entity.NotifyLog;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.NotifyLogMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.payment.service.impl.PaymentServiceImpl;
import com.shop.pay.feature.payment.statemachine.PaymentStateMachine;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.pay.feature.payment.support.SignVerifier;
import com.shop.pay.mq.message.PayCheckMessage;
import com.shop.pay.support.PayNoGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 支付 Service：回调验签/幂等/金额校验、主动查询补偿、余额实时支付（mock mapper/feign）。
 */
class PaymentServiceImplTest {

    private PaymentMapper paymentMapper;
    private ChannelFlowMapper channelFlowMapper;
    private NotifyLogMapper notifyLogMapper;
    private PayNoGenerator payNoGenerator;
    private ChannelRouter channelRouter;
    private DistributedLockTemplate lockTemplate;
    private OutboxPublisher outboxPublisher;
    private UserClient userClient;
    private OrderClient orderClient;
    private PayChannelClient channelClient;

    private final ChannelSecretProvider secretProvider = ChannelSecretProvider.devDefaults();
    private final SignVerifier signVerifier = new SignVerifier(secretProvider);
    private SimpleMeterRegistry registry;
    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        channelFlowMapper = mock(ChannelFlowMapper.class);
        notifyLogMapper = mock(NotifyLogMapper.class);
        payNoGenerator = mock(PayNoGenerator.class);
        channelClient = mock(PayChannelClient.class);
        channelRouter = new ChannelRouter(List.of(channelClient));
        lockTemplate = mock(DistributedLockTemplate.class);
        outboxPublisher = mock(OutboxPublisher.class);
        userClient = mock(UserClient.class);
        orderClient = mock(OrderClient.class);
        registry = new SimpleMeterRegistry();
        service = new PaymentServiceImpl(paymentMapper, channelFlowMapper, notifyLogMapper, payNoGenerator,
                channelRouter, signVerifier, new PaymentStateMachine(), lockTemplate, outboxPublisher, userClient,
                orderClient, registry);

        when(lockTemplate.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private Payment waitingPayment() {
        Payment p = new Payment();
        p.setPayNo("P1");
        p.setOrderNo("O1");
        p.setUserId(7L);
        p.setPayMethod(PayMethods.ALIPAY.getCode());
        p.setAmountFen(12999L);
        p.setRefundedFen(0L);
        p.setStatus(PayStatuses.WAIT.getCode());
        return p;
    }

    private ChannelNotifyParams signedNotify(String payNo, long amountFen, String sign) {
        return signedNotify(payNo, amountFen, sign, "N1");
    }

    private ChannelNotifyParams signedNotify(String payNo, long amountFen, String sign, String notifyId) {
        ChannelNotifyParams params = ChannelNotifyParams.builder()
                .channelCode("MOCK_ALIPAY")
                .notifyId(notifyId)
                .payNo(payNo)
                .channelTxnNo("TXN1")
                .amountFen(amountFen)
                .status("SUCCESS")
                .paidTime(LocalDateTime.now())
                .notifyType(1)
                .build();
        params.setSign(sign != null ? sign : signVerifier.sign(params, secretProvider.secret("MOCK_ALIPAY")));
        return params;
    }

    @Test
    void handleNotify_验签通过首单_落单并发ORDER_PAID() {
        Payment payment = waitingPayment();
        ChannelFlow flow = new ChannelFlow();
        flow.setId(11L);
        flow.setPayNo("P1");
        flow.setChannelCode("MOCK_ALIPAY");
        flow.setFlowStatus(PayStatuses.WAIT.getCode());

        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        NotifyLog log = new NotifyLog();
        log.setId(1L);
        when(notifyLogMapper.selectOne(any())).thenReturn(log);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(flow));
        when(paymentMapper.markSuccess(eq("P1"), anyString(), anyString(), any())).thenReturn(1);
        when(channelFlowMapper.markSuccess(eq(11L), anyString(), any())).thenReturn(1);

        PaymentDTO dto = service.handleNotify(signedNotify("P1", 12999L, null));

        assertEquals(PayStatuses.SUCCESS.getCode(), dto.getStatus());
        verify(paymentMapper).markSuccess(eq("P1"), anyString(), anyString(), any());
        verify(outboxPublisher).publish(eq(MqTopics.ORDER_PAID), eq("paid"), any(), eq("P1"));
        verify(outboxPublisher).publish(eq(MqTopics.PAY_RESULT), eq("result"), any(), eq("P1"));
    }

    @Test
    void handleNotify_重复回调已处理_幂等返回不重复落单() {
        Payment payment = waitingPayment();
        payment.setStatus(PayStatuses.SUCCESS.getCode());
        when(notifyLogMapper.insertIgnore(any())).thenReturn(0);
        NotifyLog done = new NotifyLog();
        done.setId(1L);
        done.setHandleStatus(1);
        when(notifyLogMapper.selectOne(any())).thenReturn(done);
        when(paymentMapper.selectOne(any())).thenReturn(payment);

        PaymentDTO dto = service.handleNotify(signedNotify("P1", 12999L, null));

        assertEquals(PayStatuses.SUCCESS.getCode(), dto.getStatus());
        verify(paymentMapper, never()).markSuccess(anyString(), anyString(), anyString(), any());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void handleNotify_验签失败_抛60002且不落单() {
        BizException ex = assertThrows(BizException.class,
                () -> service.handleNotify(signedNotify("P1", 12999L, "bad-sign")));
        assertEquals(60002, ex.getCode());
        verify(paymentMapper, never()).markSuccess(anyString(), anyString(), anyString(), any());
        verify(notifyLogMapper).insertIgnore(any());
    }

    @Test
    void handleNotify_金额不符_抛支付错误() {
        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        NotifyLog log = new NotifyLog();
        log.setId(1L);
        when(notifyLogMapper.selectOne(any())).thenReturn(log);
        when(paymentMapper.selectOne(any())).thenReturn(waitingPayment());

        BizException ex = assertThrows(BizException.class,
                () -> service.handleNotify(signedNotify("P1", 1L, null)));
        assertEquals(60001, ex.getCode());
        verify(paymentMapper, never()).markSuccess(anyString(), anyString(), anyString(), any());
    }

    @Test
    void activeQuery_渠道返回成功_推进为成功并发事件() {
        Payment payment = waitingPayment();
        ChannelFlow flow = new ChannelFlow();
        flow.setId(12L);
        flow.setPayNo("P1");
        flow.setChannelCode("MOCK_ALIPAY");
        flow.setChannelOrderNo("MO1");
        flow.setFlowStatus(PayStatuses.WAIT.getCode());

        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.query(eq("MOCK_ALIPAY"), eq("MO1")))
                .thenReturn(ChannelQueryResult.builder()
                        .state(ChannelQueryResult.State.SUCCESS).channelTxnNo("TXN9").build());
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(flow));
        when(paymentMapper.markSuccess(eq("P1"), anyString(), anyString(), any())).thenReturn(1);

        PaymentDTO dto = service.activeQuery("P1");

        assertEquals(PayStatuses.SUCCESS.getCode(), dto.getStatus());
        verify(outboxPublisher).publish(eq(MqTopics.ORDER_PAID), eq("paid"), any(), eq("P1"));
    }

    @Test
    void createPayment_余额支付_实时扣减并直接成功() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O2");
        request.setUserId(9L);
        request.setPayMethod(PayMethods.BALANCE.getCode());
        // C-3：客户端篡改金额为 1 分，服务端必须以订单应付金额 5000 建单
        request.setAmountFen(1L);
        request.setTerminal(1);
        when(orderClient.getByOrderNo("O2")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O2").userId(9L).payFen(5000L).status(10).build()));

        when(payNoGenerator.payNo()).thenReturn("P2");
        when(userClient.debitBalance(any())).thenReturn(Result.success());

        Payment[] stored = new Payment[1];
        when(paymentMapper.insert(any())).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(channelFlowMapper.insert(any())).thenReturn(1);
        when(channelFlowMapper.markSuccess(any(), anyString(), any())).thenReturn(1);
        // 第 1 次查重返回 null；之后返回内存中的支付单（模拟 DB）
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);
        when(paymentMapper.markSuccess(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            stored[0].setStatus(PayStatuses.SUCCESS.getCode());
            return 1;
        });

        PaymentDTO dto = service.createPayment(request);

        assertEquals(PayStatuses.SUCCESS.getCode(), dto.getStatus());
        // 实际建单金额取订单应付，忽略请求体
        assertEquals(5000L, stored[0].getAmountFen());
        verify(userClient).debitBalance(any());
        verify(outboxPublisher).publish(eq(MqTopics.ORDER_PAID), eq("paid"), any(), eq("P2"));
        verify(outboxPublisher, never())
                .publishDelay(anyString(), anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createPayment_伪造他人userId支付他人订单_403() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O2");
        // 登录用户 9 试图支付属于用户 7 的订单
        request.setUserId(9L);
        request.setPayMethod(PayMethods.BALANCE.getCode());
        request.setAmountFen(5000L);
        request.setTerminal(1);
        when(orderClient.getByOrderNo("O2")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O2").userId(7L).payFen(5000L).status(10).build()));

        BizException ex = assertThrows(BizException.class, () -> service.createPayment(request));
        assertEquals(10003, ex.getCode());
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void createPayment_组合支付合计不等于订单应付_拒绝() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O2");
        request.setUserId(9L);
        request.setTerminal(1);
        PayCreateRequest.PayPart p1 = new PayCreateRequest.PayPart();
        p1.setPayMethod(PayMethods.ALIPAY.getCode());
        p1.setAmountFen(4000L);
        PayCreateRequest.PayPart p2 = new PayCreateRequest.PayPart();
        p2.setPayMethod(PayMethods.WECHAT.getCode());
        p2.setAmountFen(999L);
        request.setParts(List.of(p1, p2));
        when(orderClient.getByOrderNo("O2")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O2").userId(9L).payFen(5000L).status(10).build()));

        BizException ex = assertThrows(BizException.class, () -> service.createPayment(request));
        assertEquals(10001, ex.getCode());
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void createPayment_订单不存在_拒绝() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("NOPE");
        request.setUserId(9L);
        request.setPayMethod(PayMethods.BALANCE.getCode());
        request.setAmountFen(5000L);
        when(orderClient.getByOrderNo("NOPE"))
                .thenReturn(Result.fail(50001, "订单不存在"));

        assertThrows(BizException.class, () -> service.createPayment(request));
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void createPayment_余额不足_抛支付异常() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O3");
        request.setUserId(9L);
        request.setPayMethod(PayMethods.BALANCE.getCode());
        request.setAmountFen(5000L);
        request.setTerminal(1);
        when(orderClient.getByOrderNo("O3")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O3").userId(9L).payFen(5000L).status(10).build()));

        when(payNoGenerator.payNo()).thenReturn("P3");
        when(userClient.debitBalance(any())).thenThrow(new BizException(
                com.shop.common.exception.ErrorCode.DEPENDENCY_FAIL, "余额不足"));

        BizException ex = assertThrows(BizException.class, () -> service.createPayment(request));
        assertEquals(60001, ex.getCode());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void createPayment_同订单重复发起_幂等返回原单() {
        Payment existed = waitingPayment();
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(existed);
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(12999L).status(10).build()));

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(12999L);
        request.setTerminal(1);

        PaymentDTO dto = service.createPayment(request);
        assertEquals("P1", dto.getPayNo());
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void createPayment_旧单PAYING_幂等返回旧单不新建() {
        Payment existed = waitingPayment();
        existed.setStatus(PayStatuses.PAYING.getCode());
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(existed);
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(12999L).status(10).build()));

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(12999L);
        request.setTerminal(1);

        PaymentDTO dto = service.createPayment(request);
        assertEquals("P1", dto.getPayNo());
        assertEquals(PayStatuses.PAYING.getCode(), dto.getStatus());
        verify(paymentMapper, never()).insert(any());
        verify(outboxPublisher, never())
                .publishDelay(anyString(), anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createPayment_旧单FAIL或CLOSED_旧单入墓碑槽位并新建第二张支付单() {
        // P2-5 修复：FAIL(40)/CLOSED(50) 终态旧单在同一发起事务内 CAS 入墓碑槽位
        // （active_slot 0 → 旧单自身 id），随后以新 payNo 新建 WAIT 单与新渠道流水，
        // 并为新尝试登记 15 分钟延时核查；同一订单两行共存但仅新单 active_slot=0。
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(12999L).status(10).build()));
        when(payNoGenerator.payNo()).thenReturn("P2");
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.createOrder(any())).thenReturn(ChannelPayResult.builder()
                .accepted(true).channelOrderNo("CH2").payUrl("http://pay/ch2").build());

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(12999L);
        request.setTerminal(1);

        for (int terminalStatus : List.of(PayStatuses.FAIL.getCode(), PayStatuses.CLOSED.getCode())) {
            long oldId = terminalStatus == PayStatuses.FAIL.getCode() ? 1001L : 1002L;
            Payment dead = waitingPayment();
            dead.setId(oldId);
            dead.setStatus(terminalStatus);
            dead.setActiveSlot(0L);

            Payment[] stored = new Payment[1];
            when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(dead, null);
            // 模拟 SQL: UPDATE t_pay_order SET active_slot = id WHERE id=? AND active_slot=0 AND status IN (40,50)
            when(paymentMapper.retireActiveSlot(oldId)).thenAnswer(inv -> {
                dead.setActiveSlot(oldId);
                return 1;
            });
            when(paymentMapper.insert(any())).thenAnswer(inv -> {
                stored[0] = inv.getArgument(0);
                return 1;
            });
            when(channelFlowMapper.insert(any())).thenReturn(1);
            when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);

            PaymentDTO dto = service.createPayment(request);

            assertEquals("P2", dto.getPayNo());
            assertEquals(PayStatuses.WAIT.getCode(), dto.getStatus());
            assertEquals("http://pay/ch2", dto.getPayUrl());
            // 旧单进入自身墓碑槽位，不再占用活跃槽位
            assertEquals(oldId, dead.getActiveSlot());
            verify(paymentMapper).retireActiveSlot(oldId);
            // 新单占活跃槽位 0，payNo/渠道流水全新
            assertEquals(0L, stored[0].getActiveSlot());
            assertEquals("P2", stored[0].getPayNo());
            // 新尝试重新登记 15 分钟延时核查 outbox（bizNo=新 payNo）
            verify(outboxPublisher).publishDelay(eq(MqTopics.PAY_RESULT), eq("check"),
                    any(PayCheckMessage.class), eq("P2"), eq(MqTopics.DELAY_15_MIN_SECONDS));
            verify(paymentMapper, times(1)).insert(any());
            verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());

            org.mockito.Mockito.reset(paymentMapper, channelFlowMapper, outboxPublisher);
        }
    }

    @Test
    void createPayment_旧单SUCCESS_幂等返回旧单绝不新建() {
        Payment existed = waitingPayment();
        existed.setId(1003L);
        existed.setStatus(PayStatuses.SUCCESS.getCode());
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(existed);
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(12999L).status(10).build()));

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(12999L);
        request.setTerminal(1);

        PaymentDTO dto = service.createPayment(request);
        assertEquals("P1", dto.getPayNo());
        assertEquals(PayStatuses.SUCCESS.getCode(), dto.getStatus());
        verify(paymentMapper, never()).insert(any());
        verify(paymentMapper, never()).retireActiveSlot(anyLong());
        verify(outboxPublisher, never())
                .publishDelay(anyString(), anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createPayment_旧单WAIT已过期_仍返回旧单不新建() {
        // 过期 WAIT 单可能正收到渠道迟到成功回调（用户线下已付款），直接开新单有重复扣款风险；
        // 必须由 scanTimeout 核查渠道置 CLOSED 后，下一次发起才走终态入槽 + 新建。
        Payment existed = waitingPayment();
        existed.setId(1004L);
        existed.setExpireTime(LocalDateTime.now().minusMinutes(1));
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(existed);
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(12999L).status(10).build()));

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(12999L);
        request.setTerminal(1);

        PaymentDTO dto = service.createPayment(request);
        assertEquals("P1", dto.getPayNo());
        assertEquals(PayStatuses.WAIT.getCode(), dto.getStatus());
        verify(paymentMapper, never()).insert(any());
        verify(paymentMapper, never()).retireActiveSlot(anyLong());
        verify(outboxPublisher, never())
                .publishDelay(anyString(), anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createPayment_终态旧单入槽CAS落败_幂等返回并发获胜方新单不新建() {
        // 分布式锁内的极端并发兜底：retireActiveSlot 影响 0 行（槽位已被另一线程抢占），
        // 重读活跃行拿到获胜方 WAIT 单后幂等返回，本线程绝不 insert（uk_order_active 兜底之外的应用层防线）。
        Payment dead = waitingPayment();
        dead.setId(2001L);
        dead.setStatus(PayStatuses.CLOSED.getCode());
        Payment winner = waitingPayment();
        winner.setId(2002L);
        winner.setPayNo("PWIN");
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(dead, winner);
        when(paymentMapper.retireActiveSlot(2001L)).thenReturn(0);
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(12999L).status(10).build()));

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(12999L);
        request.setTerminal(1);

        PaymentDTO dto = service.createPayment(request);
        assertEquals("PWIN", dto.getPayNo());
        assertEquals(PayStatuses.WAIT.getCode(), dto.getStatus());
        verify(paymentMapper).retireActiveSlot(2001L);
        verify(paymentMapper, never()).insert(any());
        verify(outboxPublisher, never())
                .publishDelay(anyString(), anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createPayment_订单已取消_订单域状态校验挡住不发起支付() {
        // design 5.3.3 超时取消后订单 status=50：支付域只信订单域状态，禁止再发起支付（资金悬挂防护）
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(12999L)
                        .status(com.shop.api.order.enums.OrderStatuses.CANCELLED).build()));

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(12999L);
        request.setTerminal(1);

        BizException ex = assertThrows(BizException.class, () -> service.createPayment(request));
        assertEquals(ErrorCode.ORDER_STATUS_ERROR.getCode(), ex.getCode());
        verify(paymentMapper, never()).selectActiveByOrderNo(anyString());
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void createPayment_FAIL旧单后余额重新支付_扣款成功且ORDER_PAID仅一次() {
        when(orderClient.getByOrderNo("O1")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).payFen(5000L).status(10).build()));

        Payment dead = new Payment();
        dead.setId(3001L);
        dead.setPayNo("P1");
        dead.setOrderNo("O1");
        dead.setUserId(7L);
        dead.setPayMethod(PayMethods.BALANCE.getCode());
        dead.setAmountFen(5000L);
        dead.setRefundedFen(0L);
        dead.setStatus(PayStatuses.FAIL.getCode());
        dead.setActiveSlot(0L);
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(dead, null);
        when(paymentMapper.retireActiveSlot(3001L)).thenAnswer(inv -> {
            dead.setActiveSlot(3001L);
            return 1;
        });
        when(payNoGenerator.payNo()).thenReturn("P2");
        when(userClient.debitBalance(any())).thenReturn(Result.success());

        Payment[] stored = new Payment[1];
        when(paymentMapper.insert(any())).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(channelFlowMapper.insert(any())).thenReturn(1);
        when(channelFlowMapper.markSuccess(any(), anyString(), any())).thenReturn(1);
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);
        when(paymentMapper.markSuccess(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            stored[0].setStatus(PayStatuses.SUCCESS.getCode());
            return 1;
        });

        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O1");
        request.setUserId(7L);
        request.setPayMethod(PayMethods.BALANCE.getCode());
        request.setAmountFen(5000L);
        request.setTerminal(1);

        PaymentDTO dto = service.createPayment(request);

        assertEquals("P2", dto.getPayNo());
        assertEquals(PayStatuses.SUCCESS.getCode(), dto.getStatus());
        assertEquals(3001L, dead.getActiveSlot());
        // 余额扣款 bizNo=新 payNo，用户域幂等键不与失败旧单碰撞；同事务扣款仅一次
        org.mockito.ArgumentCaptor<com.shop.api.user.dto.AmountCommand> cmdCaptor =
                org.mockito.ArgumentCaptor.forClass(com.shop.api.user.dto.AmountCommand.class);
        verify(userClient, times(1)).debitBalance(cmdCaptor.capture());
        assertEquals("P2", cmdCaptor.getValue().getBizNo());
        // P1-2：completeSuccess CAS 获胜方只登记一次 ORDER_PAID/PAY_RESULT；余额支付无延时核查
        verify(outboxPublisher, times(1)).publish(eq(MqTopics.ORDER_PAID), eq("paid"), any(), eq("P2"));
        verify(outboxPublisher, times(1)).publish(eq(MqTopics.PAY_RESULT), eq("result"), any(), eq("P2"));
        verify(outboxPublisher, never())
                .publishDelay(anyString(), anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void handleNotify_FAIL旧单迟到成功回调_终态不可逆转不翻单不发ORDER_PAID() {
        // P2-5 状态机保护：已入终态（FAIL）的历史支付单收到迟到 SUCCESS 回调，
        // 既不得翻转为 SUCCESS，也不得发 ORDER_PAID（markSuccess CAS status IN (10,20) 兜底）。
        Payment dead = waitingPayment();
        dead.setStatus(PayStatuses.FAIL.getCode());

        NotifyLog nlog = new NotifyLog();
        nlog.setId(1L);
        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        when(notifyLogMapper.selectOne(any())).thenReturn(nlog);
        when(paymentMapper.selectOne(any())).thenReturn(dead);

        BizException ex = assertThrows(BizException.class,
                () -> service.handleNotify(signedNotify("P1", 12999L, null, "NLATE")));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(paymentMapper, never()).markSuccess(anyString(), anyString(), anyString(), any());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
        // 回调记为处理失败（不 ACK 成功），但支付单保持 FAIL
        verify(notifyLogMapper).updateResult(eq(1L), eq(1), eq(3), anyString());
        assertEquals(PayStatuses.FAIL.getCode(), dead.getStatus());
    }

    @Test
    void createPayment_第三方渠道_同事务登记15分钟延时查询outbox() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O4");
        request.setUserId(9L);
        request.setPayMethod(PayMethods.ALIPAY.getCode());
        request.setAmountFen(5000L);
        request.setTerminal(1);
        when(orderClient.getByOrderNo("O4")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O4").userId(9L).payFen(5000L).status(10).build()));
        when(payNoGenerator.payNo()).thenReturn("P4");
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.createOrder(any())).thenReturn(ChannelPayResult.builder()
                .accepted(true).channelOrderNo("CH4").payUrl("http://pay/ch4").build());

        Payment[] stored = new Payment[1];
        when(paymentMapper.insert(any())).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(channelFlowMapper.insert(any())).thenReturn(1);
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);

        PaymentDTO dto = service.createPayment(request);

        assertEquals(PayStatuses.WAIT.getCode(), dto.getStatus());
        verify(outboxPublisher).publishDelay(eq(MqTopics.PAY_RESULT), eq("check"),
                any(PayCheckMessage.class), eq("P4"), eq(MqTopics.DELAY_15_MIN_SECONDS));
        // 未支付成功不发 ORDER_PAID
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void handleNotify_并发重复回调不同notifyId_仅CAS获胜方登记outbox() {
        // P1-2：渠道重发换 notifyId / 回调与主动查询并发，两个线程都读到 WAIT：
        // markSuccess 条件更新只允许一个线程获胜（rows=1），落败方（rows=0，最新已 SUCCESS）
        // 不得登记第二条 ORDER_PAID/PAY_RESULT，但重复通知仍 ACK（标记 notify 完成）。
        Payment winner = waitingPayment();
        ChannelFlow flow = new ChannelFlow();
        flow.setId(11L);
        flow.setPayNo("P1");
        flow.setChannelCode("MOCK_ALIPAY");
        flow.setFlowStatus(PayStatuses.WAIT.getCode());
        Payment loserRead = waitingPayment();
        Payment latestSuccess = waitingPayment();
        latestSuccess.setStatus(PayStatuses.SUCCESS.getCode());

        NotifyLog nlog = new NotifyLog();
        nlog.setId(1L);
        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        when(notifyLogMapper.selectOne(any())).thenReturn(nlog);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(flow));

        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> {
            int i = reads.getAndIncrement();
            // 第 1 次回调：校验读/publishPaid 回读/返回回读 均为 winner
            if (i <= 2) {
                return winner;
            }
            // 第 2 次回调：校验时仍读到 WAIT 快照(i=3)；CAS 落败后回读(i=4)与返回回读(i=5)为 SUCCESS
            return i == 3 ? loserRead : latestSuccess;
        });
        when(paymentMapper.markSuccess(eq("P1"), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    winner.setStatus(PayStatuses.SUCCESS.getCode());
                    return 1;
                })
                .thenReturn(0);
        when(channelFlowMapper.markSuccess(eq(11L), anyString(), any())).thenReturn(1);

        PaymentDTO first = service.handleNotify(signedNotify("P1", 12999L, null, "N1"));
        PaymentDTO second = service.handleNotify(signedNotify("P1", 12999L, null, "N2"));

        assertEquals(PayStatuses.SUCCESS.getCode(), first.getStatus());
        assertEquals(PayStatuses.SUCCESS.getCode(), second.getStatus());
        // 两条不同 notifyId 的回调合计只登记一次 ORDER_PAID 与一次 PAY_RESULT
        verify(outboxPublisher, times(1)).publish(eq(MqTopics.ORDER_PAID), eq("paid"), any(), eq("P1"));
        verify(outboxPublisher, times(1)).publish(eq(MqTopics.PAY_RESULT), eq("result"), any(), eq("P1"));
        // 两条通知都被 ACK（不返回 REPEAT_SUBMIT，渠道不会持续重试，P2-2）
        verify(notifyLogMapper, times(2)).updateResult(anyLong(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void viewByPayNo_非归属用户查询_403() {
        when(paymentMapper.selectOne(any())).thenReturn(waitingPayment());
        LoginUser other = LoginUser.builder().userId(9L).userType(0).build();
        BizException ex = assertThrows(BizException.class,
                () -> service.viewByPayNo("P1", other));
        assertEquals(10003, ex.getCode());
    }

    @Test
    void viewByPayNo_买家本人_通过() {
        when(paymentMapper.selectOne(any())).thenReturn(waitingPayment());
        LoginUser owner = LoginUser.builder().userId(7L).userType(0).build();
        assertEquals("P1", service.viewByPayNo("P1", owner).getPayNo());
    }

    @Test
    void viewByPayNo_平台运营_通过() {
        when(paymentMapper.selectOne(any())).thenReturn(waitingPayment());
        LoginUser admin = LoginUser.builder().userId(1L).userType(2).build();
        assertEquals("P1", service.viewByPayNo("P1", admin).getPayNo());
    }

    @Test
    void viewByOrderNo_非归属用户查询_403() {
        Payment payment = waitingPayment();
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(payment);
        LoginUser other = LoginUser.builder().userId(9L).userType(0).build();
        BizException ex = assertThrows(BizException.class,
                () -> service.viewByOrderNo("O1", other));
        assertEquals(10003, ex.getCode());
    }

    // ---------- 混合支付（余额 + 在线）：余额实时扣减、失败释放 ----------

    private PayCreateRequest mixedRequest(String orderNo, long userId) {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo(orderNo);
        request.setUserId(userId);
        request.setTerminal(1);
        PayCreateRequest.PayPart online = new PayCreateRequest.PayPart();
        online.setPayMethod(PayMethods.WECHAT.getCode());
        online.setAmountFen(7000L);
        PayCreateRequest.PayPart balance = new PayCreateRequest.PayPart();
        balance.setPayMethod(PayMethods.BALANCE.getCode());
        balance.setAmountFen(3000L);
        request.setParts(List.of(online, balance));
        return request;
    }

    @Test
    void createPayment_余额加在线组合支付_余额部分实时扣减_在线部分等待回调() {
        when(orderClient.getByOrderNo("O5")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O5").userId(9L).payFen(10000L).status(10).build()));
        when(payNoGenerator.payNo()).thenReturn("P5");
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.createOrder(any())).thenReturn(ChannelPayResult.builder()
                .accepted(true).channelOrderNo("CH5").payUrl("http://pay/ch5").build());
        when(userClient.debitBalance(any())).thenReturn(Result.success());

        Payment[] stored = new Payment[1];
        when(paymentMapper.insert(any())).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(channelFlowMapper.insert(any())).thenAnswer(inv -> {
            ChannelFlow f = inv.getArgument(0);
            f.setId("BALANCE".equals(f.getChannelCode()) ? 22L : 21L);
            return 1;
        });
        when(channelFlowMapper.markSuccess(anyLong(), anyString(), any())).thenReturn(1);
        when(paymentMapper.selectActiveByOrderNo("O5")).thenReturn(null);
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);

        PaymentDTO dto = service.createPayment(mixedRequest("O5", 9L));

        // 在线渠道未回调前，支付单保持待支付
        assertEquals(PayStatuses.WAIT.getCode(), dto.getStatus());
        // 仅在线部分向渠道下单一次，余额部分无渠道下单
        verify(channelClient, times(1)).createOrder(any());
        // 余额部分创建即实时扣减，金额 3000，bizNo=payNo（用户域幂等）
        org.mockito.ArgumentCaptor<com.shop.api.user.dto.AmountCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.shop.api.user.dto.AmountCommand.class);
        verify(userClient, times(1)).debitBalance(captor.capture());
        assertEquals(3000L, captor.getValue().getAmountFen());
        assertEquals("P5", captor.getValue().getBizNo());
        // 仅余额流水被置成功，微信流水保持待回调
        verify(channelFlowMapper, times(1)).markSuccess(eq(22L), anyString(), any());
        verify(channelFlowMapper, never()).markSuccess(eq(21L), anyString(), any());
        // 登记在线渠道延时核查；未成功不发 ORDER_PAID
        verify(outboxPublisher).publishDelay(eq(MqTopics.PAY_RESULT), eq("check"),
                any(PayCheckMessage.class), eq("P5"), eq(MqTopics.DELAY_15_MIN_SECONDS));
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void createPayment_组合支付余额部分不足_整体回滚不登记延时核查() {
        when(orderClient.getByOrderNo("O5")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O5").userId(9L).payFen(10000L).status(10).build()));
        when(payNoGenerator.payNo()).thenReturn("P5");
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.createOrder(any())).thenReturn(ChannelPayResult.builder()
                .accepted(true).channelOrderNo("CH5").payUrl("http://pay/ch5").build());
        when(userClient.debitBalance(any())).thenThrow(new BizException(
                ErrorCode.DEPENDENCY_FAIL, "余额不足"));
        when(paymentMapper.selectActiveByOrderNo("O5")).thenReturn(null);
        when(channelFlowMapper.insert(any())).thenReturn(1);

        BizException ex = assertThrows(BizException.class,
                () -> service.createPayment(mixedRequest("O5", 9L)));
        assertEquals(60001, ex.getCode());
        verify(outboxPublisher, never())
                .publishDelay(anyString(), anyString(), any(), anyString(),
                        org.mockito.ArgumentMatchers.anyLong());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    private ChannelNotifyParams signedFailNotify(String payNo, String channel) {
        ChannelNotifyParams params = ChannelNotifyParams.builder()
                .channelCode(channel)
                .notifyId("NF1")
                .payNo(payNo)
                .channelTxnNo("TXN1")
                .amountFen(10000L)
                .status("FAIL")
                .notifyType(1)
                .build();
        params.setSign(signVerifier.sign(params, secretProvider.secret(channel)));
        return params;
    }

    @Test
    void handleNotify_组合支付在线渠道失败_已扣余额实时退回() {
        Payment payment = waitingPayment();
        payment.setPayMethod(PayMethods.WECHAT.getCode());
        payment.setAmountFen(10000L);

        ChannelFlow online = new ChannelFlow();
        online.setId(21L);
        online.setPayNo("P1");
        online.setChannelCode("MOCK_WECHAT");
        online.setAmountFen(7000L);
        online.setFlowStatus(PayStatuses.WAIT.getCode());
        ChannelFlow balance = new ChannelFlow();
        balance.setId(22L);
        balance.setPayNo("P1");
        balance.setChannelCode("BALANCE");
        balance.setAmountFen(3000L);
        balance.setFlowStatus(PayStatuses.SUCCESS.getCode());

        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        NotifyLog nlog = new NotifyLog();
        nlog.setId(1L);
        when(notifyLogMapper.selectOne(any())).thenReturn(nlog);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.markFail(eq("P1"), anyString())).thenAnswer(inv -> {
            payment.setStatus(PayStatuses.FAIL.getCode());
            return 1;
        });
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(online, balance));
        when(userClient.creditBalance(any())).thenReturn(Result.success());

        PaymentDTO dto = service.handleNotify(signedFailNotify("P1", "MOCK_WECHAT"));

        assertEquals(PayStatuses.FAIL.getCode(), dto.getStatus());
        org.mockito.ArgumentCaptor<com.shop.api.user.dto.AmountCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.shop.api.user.dto.AmountCommand.class);
        verify(userClient, times(1)).creditBalance(captor.capture());
        assertEquals(3000L, captor.getValue().getAmountFen());
        assertEquals("BALANCE_RELEASE_P1", captor.getValue().getBizNo());
        // 失败不发 ORDER_PAID
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void activeQuery_组合支付在线渠道确认失败_已扣余额退回且幂等键固定() {
        Payment payment = waitingPayment();
        payment.setPayMethod(PayMethods.WECHAT.getCode());
        payment.setAmountFen(10000L);

        ChannelFlow online = new ChannelFlow();
        online.setId(21L);
        online.setPayNo("P1");
        online.setChannelCode("MOCK_WECHAT");
        online.setChannelOrderNo("MO1");
        online.setAmountFen(7000L);
        online.setFlowStatus(PayStatuses.WAIT.getCode());
        ChannelFlow balance = new ChannelFlow();
        balance.setId(22L);
        balance.setPayNo("P1");
        balance.setChannelCode("BALANCE");
        balance.setAmountFen(3000L);
        balance.setFlowStatus(PayStatuses.SUCCESS.getCode());

        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.query(eq("MOCK_WECHAT"), eq("MO1")))
                .thenReturn(ChannelQueryResult.builder().state(ChannelQueryResult.State.FAIL).build());
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(online, balance));
        when(paymentMapper.markFail(eq("P1"), anyString())).thenReturn(1);
        when(userClient.creditBalance(any())).thenReturn(Result.success());

        service.activeQuery("P1");

        org.mockito.ArgumentCaptor<com.shop.api.user.dto.AmountCommand> captor =
                org.mockito.ArgumentCaptor.forClass(com.shop.api.user.dto.AmountCommand.class);
        verify(userClient).creditBalance(captor.capture());
        assertEquals(3000L, captor.getValue().getAmountFen());
        assertEquals("BALANCE_RELEASE_P1", captor.getValue().getBizNo());
        verify(outboxPublisher, never()).publish(anyString(), anyString(), any(), anyString());
    }

    @Test
    void activeQuery_纯在线支付失败_不触发余额退回调用() {
        Payment payment = waitingPayment();
        ChannelFlow online = new ChannelFlow();
        online.setId(21L);
        online.setPayNo("P1");
        online.setChannelCode("MOCK_ALIPAY");
        online.setChannelOrderNo("MO1");
        online.setAmountFen(12999L);
        online.setFlowStatus(PayStatuses.WAIT.getCode());

        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.query(eq("MOCK_ALIPAY"), eq("MO1")))
                .thenReturn(ChannelQueryResult.builder().state(ChannelQueryResult.State.FAIL).build());
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(online));
        when(paymentMapper.markFail(eq("P1"), anyString())).thenReturn(1);

        service.activeQuery("P1");

        verify(userClient, never()).creditBalance(any());
    }

    // B10：保证金缴费内部命令（payScene=4）建单——subject 固定、DP 流水号作 order_no、场景落库
    @Test
    void createPayment_保证金缴费命令_scene4且subject固定保证金缴费() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderNo("260917DP00070001")
                .userId(7L)
                .payMethod(PayMethods.ALIPAY.getCode())
                .amountFen(100000L)
                .terminal(1)
                .payScene(PayScenes.DEPOSIT)
                .subject("被篡改的标题")
                .build();
        when(payNoGenerator.payNo()).thenReturn("PDP1");
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.createOrder(any())).thenReturn(ChannelPayResult.builder()
                .accepted(true).channelOrderNo("CHDP1").payUrl("http://pay/dp1").build());
        Payment[] stored = new Payment[1];
        when(paymentMapper.insert(any())).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(channelFlowMapper.insert(any())).thenReturn(1);
        when(paymentMapper.selectActiveByOrderNo("260917DP00070001")).thenReturn(null);
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);

        PaymentDTO dto = service.createPayment(command);

        assertEquals(PayStatuses.WAIT.getCode(), dto.getStatus());
        assertEquals(PayScenes.DEPOSIT, stored[0].getPayScene());
        assertEquals("保证金缴费", stored[0].getSubject());
        assertEquals("260917DP00070001", stored[0].getOrderNo());
        // 无 t_pay_channel_flow 之外的特殊逻辑：渠道单正常登记 15 分钟延时核查
        verify(outboxPublisher).publishDelay(eq(MqTopics.PAY_RESULT), eq("check"),
                any(PayCheckMessage.class), eq("PDP1"), eq(MqTopics.DELAY_15_MIN_SECONDS));
    }

    // B10：DP 单号断网重试——uk_order_no/uk_order_active 天然幂等，重复发起返回原单不新建
    @Test
    void createPayment_保证金DP单号重复发起_幂等返回原单() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderNo("260917DP00070001")
                .userId(7L)
                .payMethod(PayMethods.ALIPAY.getCode())
                .amountFen(100000L)
                .terminal(1)
                .payScene(PayScenes.DEPOSIT)
                .build();
        Payment existed = new Payment();
        existed.setId(7001L);
        existed.setPayNo("PDP1");
        existed.setOrderNo("260917DP00070001");
        existed.setUserId(7L);
        existed.setPayMethod(PayMethods.ALIPAY.getCode());
        existed.setPayScene(PayScenes.DEPOSIT);
        existed.setAmountFen(100000L);
        existed.setSubject("保证金缴费");
        existed.setStatus(PayStatuses.WAIT.getCode());
        existed.setActiveSlot(0L);
        when(paymentMapper.selectActiveByOrderNo("260917DP00070001")).thenReturn(existed);

        PaymentDTO first = service.createPayment(command);
        PaymentDTO second = service.createPayment(command);

        assertEquals("PDP1", first.getPayNo());
        assertEquals("PDP1", second.getPayNo());
        verify(paymentMapper, never()).insert(any());
        verify(channelClient, never()).createOrder(any());
    }

    // B10：保证金缴费余额支付成功——ORDER_PAID 事件回填 payScene=4
    @Test
    void createPayment_保证金余额支付成功_ORDER_PAID事件带scene4() {
        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderNo("260917DP00070002")
                .userId(9L)
                .payMethod(PayMethods.BALANCE.getCode())
                .amountFen(50000L)
                .terminal(1)
                .payScene(PayScenes.DEPOSIT)
                .build();
        when(payNoGenerator.payNo()).thenReturn("PDP2");
        when(userClient.debitBalance(any())).thenReturn(Result.success());
        Payment[] stored = new Payment[1];
        when(paymentMapper.insert(any())).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(channelFlowMapper.insert(any())).thenReturn(1);
        when(channelFlowMapper.markSuccess(any(), anyString(), any())).thenReturn(1);
        when(paymentMapper.selectActiveByOrderNo("260917DP00070002")).thenReturn(null);
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);
        when(paymentMapper.markSuccess(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            stored[0].setStatus(PayStatuses.SUCCESS.getCode());
            return 1;
        });

        PaymentDTO dto = service.createPayment(command);

        assertEquals(PayStatuses.SUCCESS.getCode(), dto.getStatus());
        ArgumentCaptor<PaymentSucceededEvent> cap = ArgumentCaptor.forClass(PaymentSucceededEvent.class);
        verify(outboxPublisher).publish(eq(MqTopics.ORDER_PAID), eq("paid"), cap.capture(), eq("PDP2"));
        assertEquals(PayScenes.DEPOSIT, cap.getValue().getPayScene());
        assertEquals("260917DP00070002", cap.getValue().getOrderNo());
    }

    // B10：C 端请求体即便伪造 payScene=4 也被忽略，仍按支付形态推导为普通场景
    @Test
    void createPayment_C端伪造payScene4_忽略按普通场景建单() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderNo("O2");
        request.setUserId(9L);
        request.setPayMethod(PayMethods.BALANCE.getCode());
        request.setAmountFen(5000L);
        request.setTerminal(1);
        request.setPayScene(PayScenes.DEPOSIT);
        when(orderClient.getByOrderNo("O2")).thenReturn(Result.success(
                OrderDTO.builder().orderNo("O2").userId(9L).payFen(5000L).status(10).build()));
        when(payNoGenerator.payNo()).thenReturn("P3");
        when(userClient.debitBalance(any())).thenReturn(Result.success());
        Payment[] stored = new Payment[1];
        when(paymentMapper.insert(any())).thenAnswer(inv -> {
            stored[0] = inv.getArgument(0);
            return 1;
        });
        when(channelFlowMapper.insert(any())).thenReturn(1);
        when(channelFlowMapper.markSuccess(any(), anyString(), any())).thenReturn(1);
        when(paymentMapper.selectOne(any())).thenAnswer(inv -> stored[0]);
        when(paymentMapper.markSuccess(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            stored[0].setStatus(PayStatuses.SUCCESS.getCode());
            return 1;
        });

        service.createPayment(request);

        assertEquals(PayScenes.NORMAL, stored[0].getPayScene());
    }

    // ============================== O6 业务指标 ==============================

    /** 遍历注册表所有 meter，断言无高基数业务 ID 标签键（订单/支付/用户/商户号禁止入标签）。 */
    private void assertNoHighCardinalityTags() {
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> {
            String k = tag.getKey();
            org.junit.jupiter.api.Assertions.assertFalse(
                    k.equals("userId") || k.equals("merchantId") || k.equals("orderNo")
                            || k.equals("payNo") || k.equals("refundNo") || k.equals("skuId"),
                    "高基数标签键泄露: " + k);
        }));
    }

    @Test
    void metrics_回调成功_shop_pay_total递增且标签受控() {
        Payment payment = waitingPayment();
        ChannelFlow flow = new ChannelFlow();
        flow.setId(11L);
        flow.setPayNo("P1");
        flow.setChannelCode("MOCK_ALIPAY");
        flow.setFlowStatus(PayStatuses.WAIT.getCode());

        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        NotifyLog nlog = new NotifyLog();
        nlog.setId(1L);
        when(notifyLogMapper.selectOne(any())).thenReturn(nlog);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(flow));
        when(paymentMapper.markSuccess(eq("P1"), anyString(), anyString(), any())).thenReturn(1);
        when(channelFlowMapper.markSuccess(eq(11L), anyString(), any())).thenReturn(1);

        service.handleNotify(signedNotify("P1", 12999L, null));

        assertEquals(1.0, registry.counter("shop_pay_total",
                "channel", "MOCK_ALIPAY", "result", "success").count());
        assertEquals(0.0, registry.counter("shop_pay_failed_total",
                "channel", "MOCK_ALIPAY", "result", "fail").count());
        assertNoHighCardinalityTags();
    }

    @Test
    void metrics_渠道等待回调失败_shop_pay_failed_total递增() {
        Payment payment = waitingPayment();
        payment.setPayMethod(PayMethods.WECHAT.getCode());
        payment.setAmountFen(10000L);
        ChannelFlow online = new ChannelFlow();
        online.setId(21L);
        online.setPayNo("P1");
        online.setChannelCode("MOCK_WECHAT");
        online.setAmountFen(10000L);
        online.setFlowStatus(PayStatuses.WAIT.getCode());

        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        NotifyLog nlog = new NotifyLog();
        nlog.setId(1L);
        when(notifyLogMapper.selectOne(any())).thenReturn(nlog);
        when(paymentMapper.selectOne(any())).thenReturn(payment);
        when(paymentMapper.markFail(eq("P1"), anyString())).thenReturn(1);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(online));

        service.handleNotify(signedFailNotify("P1", "MOCK_WECHAT"));

        assertEquals(1.0, registry.counter("shop_pay_failed_total",
                "channel", "MOCK_WECHAT", "result", "fail").count());
        assertEquals(0.0, registry.counter("shop_pay_total",
                "channel", "MOCK_WECHAT", "result", "success").count());
        assertNoHighCardinalityTags();
    }

    @Test
    void metrics_支付主调用_shop_pay_seconds记录耗时() {
        // 幂等返回旧单路径同样经过 lockAndCreate 计时（成功/异常路径都在 finally 记录）
        Payment existed = waitingPayment();
        existed.setStatus(PayStatuses.SUCCESS.getCode());
        when(paymentMapper.selectActiveByOrderNo("O1")).thenReturn(existed);

        CreatePaymentCommand command = CreatePaymentCommand.builder()
                .orderNo("O1").userId(7L).payMethod(PayMethods.ALIPAY.getCode())
                .amountFen(12999L).terminal(1).build();
        service.createPayment(command);

        io.micrometer.core.instrument.Timer timer = registry.find("shop_pay_seconds")
                .tag("channel", "MOCK_ALIPAY").timer();
        org.junit.jupiter.api.Assertions.assertNotNull(timer);
        assertEquals(1L, timer.count());
        assertNoHighCardinalityTags();
    }
}
