package com.shop.pay.feature.refund.service;

import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderDTO;
import com.shop.api.order.dto.OrderItemDTO;
import com.shop.api.pay.dto.CreateRefundCommand;
import com.shop.api.pay.dto.RefundDTO;
import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.enums.PayStatuses;
import com.shop.api.pay.enums.RefundSources;
import com.shop.api.pay.enums.RefundTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.framework.web.LoginUser;
import com.shop.pay.channel.ChannelRefundQueryResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.entity.NotifyLog;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.NotifyLogMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.payment.dto.RefundNotifyParams;
import com.shop.pay.feature.payment.support.SignVerifier;
import com.shop.pay.feature.refund.entity.RefundOrder;
import com.shop.pay.feature.refund.entity.RefundSplit;
import com.shop.pay.feature.refund.mapper.RefundMapper;
import com.shop.pay.feature.refund.mapper.RefundSplitMapper;
import com.shop.pay.feature.refund.service.impl.RefundServiceImpl;
import com.shop.pay.feature.refund.support.RefundConvergeService;
import com.shop.pay.feature.refund.support.RefundOrchestrator;
import com.shop.pay.feature.refund.support.RefundSplitter;
import com.shop.pay.feature.refund.support.SplitOutcome;
import com.shop.pay.support.PayNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 退款 Service 入口测试：锁内幂等/校验委托三段式编排；B8 回调受理与查询补偿入口。
 * 三段式与漏斗 CAS 细节分别见 RefundOrchestratorTest / RefundConvergeServiceTest。
 */
class RefundServiceImplTest {

    private RefundMapper refundMapper;
    private RefundSplitMapper refundSplitMapper;
    private PaymentMapper paymentMapper;
    private ChannelFlowMapper channelFlowMapper;
    private NotifyLogMapper notifyLogMapper;
    private RefundOrchestrator orchestrator;
    private RefundConvergeService convergeService;
    private ChannelRouter channelRouter;
    private SignVerifier signVerifier;
    private OrderClient orderClient;
    private RefundServiceImpl service;

    private final ChannelSecretProvider secretProvider = ChannelSecretProvider.devDefaults();

    @BeforeEach
    void setUp() {
        refundMapper = mock(RefundMapper.class);
        refundSplitMapper = mock(RefundSplitMapper.class);
        paymentMapper = mock(PaymentMapper.class);
        channelFlowMapper = mock(ChannelFlowMapper.class);
        notifyLogMapper = mock(NotifyLogMapper.class);
        PayNoGenerator payNoGenerator = mock(PayNoGenerator.class);
        when(payNoGenerator.refundNo()).thenReturn("RGEN1");
        DistributedLockTemplate lockTemplate = mock(DistributedLockTemplate.class);
        when(lockTemplate.execute(anyString(), org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        orderClient = mock(OrderClient.class);
        orchestrator = mock(RefundOrchestrator.class);
        convergeService = mock(RefundConvergeService.class);
        channelRouter = mock(ChannelRouter.class);
        signVerifier = new SignVerifier(secretProvider);

        service = new RefundServiceImpl(refundMapper, refundSplitMapper, paymentMapper, channelFlowMapper,
                notifyLogMapper, new RefundSplitter(), payNoGenerator, lockTemplate, orderClient,
                signVerifier, channelRouter, orchestrator, convergeService);
    }

    private Payment payment(long amount, long refunded) {
        Payment p = new Payment();
        p.setId(1L);
        p.setPayNo("P1");
        p.setOrderNo("O1");
        p.setUserId(7L);
        p.setPayMethod(PayMethods.WECHAT.getCode());
        p.setAmountFen(amount);
        p.setRefundedFen(refunded);
        p.setStatus(PayStatuses.SUCCESS.getCode());
        return p;
    }

    private ChannelFlow flow(long id, String code, int method, long amount) {
        ChannelFlow f = new ChannelFlow();
        f.setId(id);
        f.setPayNo("P1");
        f.setChannelCode(code);
        f.setPayMethod(method);
        f.setChannelOrderNo(code + "_O1");
        f.setChannelTransactionNo(code + "_T1");
        f.setAmountFen(amount);
        f.setPaidFen(0L);
        f.setFlowStatus(PayStatuses.SUCCESS.getCode());
        return f;
    }

    private CreateRefundCommand command(long amount, int refundType) {
        return CreateRefundCommand.builder()
                .refundNo("R1")
                .orderNo("O1")
                .aftersaleNo("AS202609160001")
                .userId(7L)
                .amountFen(amount)
                .refundType(refundType)
                .source(RefundSources.AFTERSALE.getCode())
                .reason("测试退款")
                .build();
    }

    private RefundOrder order(String refundNo, int status) {
        RefundOrder r = new RefundOrder();
        r.setId(11L);
        r.setRefundNo(refundNo);
        r.setPayNo("P1");
        r.setOrderNo("O1");
        r.setUserId(7L);
        r.setAmountFen(1000L);
        r.setStatus(status);
        return r;
    }

    // ---------- 入口：幂等 / 校验 / 委托三段式 ----------

    @Test
    void refund_refundNo重复_幂等返回不进编排() {
        when(refundMapper.selectOne(any())).thenReturn(order("R1", 30));
        RefundDTO dto = service.refund(command(100, RefundTypes.PART.getCode()));
        assertEquals(30, dto.getStatus());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void refund_累计退款超出实付_抛60004_不进编排() {
        when(paymentMapper.selectRefundableByOrderNo("O1")).thenReturn(payment(10000, 9500));
        BizException ex = assertThrows(BizException.class,
                () -> service.refund(command(600, RefundTypes.PART.getCode())));
        assertEquals(60004, ex.getCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void refund_无可退支付单_抛NOT_FOUND() {
        when(paymentMapper.selectRefundableByOrderNo("O1")).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.refund(command(1000, RefundTypes.FULL.getCode())));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void refund_校验通过_委托三段式编排并返回收敛结果() {
        when(paymentMapper.selectRefundableByOrderNo("O1")).thenReturn(payment(10000, 0));
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(
                flow(100L, "MOCK_WECHAT", PayMethods.WECHAT.getCode(), 6000),
                flow(101L, "MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 4000)));
        when(orchestrator.runNew(any(), any(), any())).thenReturn(order("R1", 30));

        RefundDTO dto = service.refund(command(1000, RefundTypes.PART.getCode()));
        assertEquals(30, dto.getStatus());

        ArgumentCaptor<List<Long>> amounts = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).runNew(any(), any(), amounts.capture());
        assertEquals(List.of(600L, 400L), amounts.getValue());
    }

    @Test
    void retry_非失败单_抛CONFLICT() {
        when(refundMapper.selectOne(any())).thenReturn(order("R1", 20));
        BizException ex = assertThrows(BizException.class, () -> service.retry("R1"));
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(orchestrator, never()).runRetry(any(), any());
    }

    @Test
    void retry_失败单_委托编排retry续做() {
        when(refundMapper.selectOne(any())).thenReturn(order("R1", 40));
        when(paymentMapper.selectOne(any())).thenReturn(payment(1000, 0));
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(
                List.of(flow(102L, "BALANCE", PayMethods.BALANCE.getCode(), 1000)));
        when(orchestrator.runRetry(any(), any())).thenReturn(order("R1", 30));

        assertEquals(30, service.retry("R1").getStatus());
        verify(orchestrator).runRetry(any(), any());
    }

    // ---------- B8：退款回调受理 ----------

    private RefundNotifyParams notifyParams(String status, String sign) {
        return RefundNotifyParams.builder()
                .channelCode("MOCK_ALIPAY")
                .notifyId("N1")
                .refundNo("R1")
                .channelRefundNo("MOCK_ALIPAY_R_X")
                .amountFen(1000L)
                .status(status)
                .sign(sign)
                .build();
    }

    private String sign(RefundNotifyParams p) {
        return signVerifier.signRefund(p, secretProvider.secret("MOCK_ALIPAY"));
    }

    @Test
    void 回调_错误签名_抛60002且写sign失败流水_不收敛() {
        RefundNotifyParams p = notifyParams("SUCCESS", "bad-sign");
        BizException ex = assertThrows(BizException.class, () -> service.handleRefundNotify(p));
        assertEquals(60002, ex.getCode());

        ArgumentCaptor<NotifyLog> captor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(notifyLogMapper).insertIgnore(captor.capture());
        NotifyLog row = captor.getValue();
        assertEquals(2, row.getNotifyType());
        assertEquals("R1", row.getRefundNo());
        assertEquals(2, row.getSignStatus());
        assertEquals(2, row.getHandleStatus());
        verifyNoInteractions(convergeService);
    }

    @Test
    void 回调_同notifyId重放_幂等ACK且不重复收敛() {
        RefundNotifyParams p = notifyParams("SUCCESS", null);
        p.setSign(sign(p));
        when(notifyLogMapper.insertIgnore(any())).thenReturn(0);
        NotifyLog existed = new NotifyLog();
        existed.setId(5L);
        existed.setHandleStatus(1);
        when(notifyLogMapper.selectOne(any())).thenReturn(existed);
        when(refundMapper.selectOne(any())).thenReturn(order("R1", 30));

        RefundDTO dto = service.handleRefundNotify(p);
        assertEquals(30, dto.getStatus());
        verify(convergeService, never()).converge(anyLong(), any(), any(), any());
    }

    @Test
    void 回调_金额不符_拒绝且不收敛() {
        RefundNotifyParams p = notifyParams("SUCCESS", null);
        p.setAmountFen(999L);
        p.setSign(sign(p));
        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        NotifyLog logRow = new NotifyLog();
        logRow.setId(5L);
        when(notifyLogMapper.selectOne(any())).thenReturn(logRow);
        when(refundMapper.selectOne(any())).thenReturn(order("R1", 20));

        BizException ex = assertThrows(BizException.class, () -> service.handleRefundNotify(p));
        assertEquals(ErrorCode.PAY_ERROR.getCode(), ex.getCode());
        verify(convergeService, never()).converge(anyLong(), any(), any(), any());
        verify(notifyLogMapper).updateResult(any(), eq(1), eq(3), anyString());
    }

    @Test
    void 回调_成功_仅渠道20split进漏斗且notify_type2落列() {
        RefundNotifyParams p = notifyParams("SUCCESS", null);
        p.setSign(sign(p));
        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        NotifyLog logRow = new NotifyLog();
        logRow.setId(5L);
        when(notifyLogMapper.selectOne(any())).thenReturn(logRow);
        when(refundMapper.selectOne(any())).thenReturn(order("R1", 20));
        RefundSplit channelSplit = split(1L, "MOCK_ALIPAY", 20);
        RefundSplit balanceSplit = split(2L, "BALANCE", 20);
        RefundSplit doneSplit = split(3L, "MOCK_WECHAT", 30);
        when(refundSplitMapper.selectByRefundNo("R1"))
                .thenReturn(List.of(channelSplit, balanceSplit, doneSplit));
        when(convergeService.converge(anyLong(), any(), any(), any()))
                .thenReturn(RefundConvergeService.ConvergeResult.ADVANCED_SUCCESS);

        service.handleRefundNotify(p);

        ArgumentCaptor<NotifyLog> logCaptor = ArgumentCaptor.forClass(NotifyLog.class);
        verify(notifyLogMapper).insertIgnore(logCaptor.capture());
        assertEquals(2, logCaptor.getValue().getNotifyType());
        assertEquals("R1", logCaptor.getValue().getRefundNo());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SplitOutcome>> oc = ArgumentCaptor.forClass(List.class);
        verify(convergeService).converge(eq(11L), eq(RefundConvergeService.Trigger.NOTIFY),
                oc.capture(), any());
        List<SplitOutcome> outcomes = oc.getValue();
        assertEquals(1, outcomes.size());
        assertEquals(1L, outcomes.get(0).getSplitId());
        assertEquals(SplitOutcome.State.SUCCESS, outcomes.get(0).getState());
        assertEquals("MOCK_ALIPAY_R_X", outcomes.get(0).getChannelRefundNo());
        verify(notifyLogMapper).updateResult(any(), eq(1), eq(1), eq(null));
    }

    @Test
    void 回调_失败_渠道split上报FAIL进漏斗() {
        RefundNotifyParams p = notifyParams("FAIL", null);
        p.setSign(sign(p));
        when(notifyLogMapper.insertIgnore(any())).thenReturn(1);
        when(refundMapper.selectOne(any())).thenReturn(order("R1", 20));
        when(refundSplitMapper.selectByRefundNo("R1"))
                .thenReturn(List.of(split(1L, "MOCK_ALIPAY", 20)));
        when(convergeService.converge(anyLong(), any(), any(), any()))
                .thenReturn(RefundConvergeService.ConvergeResult.MARKED_FAIL);

        service.handleRefundNotify(p);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SplitOutcome>> oc = ArgumentCaptor.forClass(List.class);
        verify(convergeService).converge(eq(11L), eq(RefundConvergeService.Trigger.NOTIFY),
                oc.capture(), anyString());
        assertEquals(SplitOutcome.State.FAIL, oc.getValue().get(0).getState());
    }
    // ---------- B8：主动查询补偿入口 ----------

    @Test
    void 查询补偿_touchQuery落败_不调渠道() {
        RefundOrder processing = order("R1", 20);
        when(refundMapper.selectProcessingForQuery(any(), any(), anyInt()))
                .thenReturn(List.of(processing));
        when(refundMapper.touchQuery(anyLong(), any(), any())).thenReturn(0);

        assertEquals(0, service.scanProcessingRefunds(50));
        verify(channelRouter, never()).queryRefund(any());
        verify(convergeService, never()).converge(anyLong(), any(), any(), any());
    }

    @Test
    void 查询补偿_渠道受理中_保持20且余额split不查渠道() {
        RefundOrder processing = order("R1", 20);
        when(refundMapper.selectProcessingForQuery(any(), any(), anyInt()))
                .thenReturn(List.of(processing));
        when(refundMapper.touchQuery(anyLong(), any(), any())).thenReturn(1);
        when(refundMapper.selectById(11L)).thenReturn(processing);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(
                flow(100L, "MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 600),
                flow(101L, "BALANCE", PayMethods.BALANCE.getCode(), 400)));
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(List.of(
                split(1L, "MOCK_ALIPAY", 20), split(2L, "BALANCE", 20)));
        when(channelRouter.queryRefund(any()))
                .thenReturn(ChannelRefundQueryResult.processing("MOCK_ALIPAY"));
        when(convergeService.converge(anyLong(), any(), any(), any()))
                .thenReturn(RefundConvergeService.ConvergeResult.STILL_PROCESSING);

        assertEquals(1, service.scanProcessingRefunds(50));
        verify(channelRouter).queryRefund(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SplitOutcome>> oc = ArgumentCaptor.forClass(List.class);
        verify(convergeService).converge(eq(11L), eq(RefundConvergeService.Trigger.QUERY),
                oc.capture(), any());
        assertEquals(1, oc.getValue().size());
        assertEquals(SplitOutcome.State.PENDING, oc.getValue().get(0).getState());
    }

    @Test
    void 查询补偿_渠道成功_经漏斗收敛() {
        RefundOrder processing = order("R1", 20);
        when(refundMapper.selectProcessingForQuery(any(), any(), anyInt()))
                .thenReturn(List.of(processing));
        when(refundMapper.touchQuery(anyLong(), any(), any())).thenReturn(1);
        when(refundMapper.selectById(11L)).thenReturn(processing);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(
                List.of(flow(100L, "MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 1000)));
        when(refundSplitMapper.selectByRefundNo("R1"))
                .thenReturn(List.of(split(1L, "MOCK_ALIPAY", 20)));
        when(channelRouter.queryRefund(any()))
                .thenReturn(ChannelRefundQueryResult.ok("MOCK_ALIPAY", "CHRF9"));
        when(convergeService.converge(anyLong(), any(), any(), any()))
                .thenReturn(RefundConvergeService.ConvergeResult.ADVANCED_SUCCESS);

        service.scanProcessingRefunds(50);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SplitOutcome>> oc = ArgumentCaptor.forClass(List.class);
        verify(convergeService).converge(eq(11L), eq(RefundConvergeService.Trigger.QUERY),
                oc.capture(), any());
        assertEquals(SplitOutcome.State.SUCCESS, oc.getValue().get(0).getState());
        assertEquals("CHRF9", oc.getValue().get(0).getChannelRefundNo());
    }

    private RefundSplit split(Long id, String channelCode, int status) {
        RefundSplit s = new RefundSplit();
        s.setId(id);
        s.setRefundNo("R1");
        s.setPayNo("P1");
        s.setChannelCode(channelCode);
        s.setAmountFen(status == 30 ? 0L : 1000L);
        s.setStatus(status);
        return s;
    }

    // ---------- C-3：对外退款查询归属（保留既有行为） ----------

    private RefundOrder refundForViewer() {
        return order("R9", 30);
    }

    @Test
    void query_平台运营_可查任意退款单() {
        when(refundMapper.selectOne(any())).thenReturn(refundForViewer());
        LoginUser admin = LoginUser.builder().userId(1L).userType(2).build();
        assertEquals("R9", service.getByRefundNoForViewer("R9", admin).getRefundNo());
    }

    @Test
    void query_归属商户_可查本店订单退款单() {
        when(refundMapper.selectOne(any())).thenReturn(refundForViewer());
        OrderItemDTO item = OrderItemDTO.builder().orderItemId(1L).merchantId(2002L).build();
        when(orderClient.getByOrderNo("O1")).thenReturn(com.shop.common.result.Result.success(
                OrderDTO.builder().orderNo("O1").userId(7L).items(List.of(item)).build()));
        LoginUser merchant = LoginUser.builder().userId(50L).userType(1).merchantId(2002L).build();
        assertEquals("R9", service.getByRefundNoForViewer("R9", merchant).getRefundNo());
    }

    @Test
    void query_非本人消费者_403() {
        when(refundMapper.selectOne(any())).thenReturn(refundForViewer());
        LoginUser stranger = LoginUser.builder().userId(8L).userType(0).build();
        BizException ex = assertThrows(BizException.class,
                () -> service.getByRefundNoForViewer("R9", stranger));
        assertEquals(10003, ex.getCode());
    }

    @Test
    void query_买家本人_可查且不依赖订单Feign() {
        when(refundMapper.selectOne(any())).thenReturn(refundForViewer());
        LoginUser owner = LoginUser.builder().userId(7L).userType(0).build();
        assertEquals("R9", service.getByRefundNoForViewer("R9", owner).getRefundNo());
        verify(orderClient, never()).getByOrderNo(anyString());
    }
}
