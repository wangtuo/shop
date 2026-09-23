package com.shop.pay.feature.refund.support;

import com.shop.api.pay.enums.PayMethods;
import com.shop.api.pay.enums.RefundStatuses;
import com.shop.common.exception.BizException;
import com.shop.common.result.Result;
import com.shop.api.user.client.UserClient;
import com.shop.framework.tx.TransactionalTemplate;
import com.shop.pay.channel.ChannelRefundRequest;
import com.shop.pay.channel.ChannelRefundResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.channel.PayChannelClient;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.refund.entity.RefundOrder;
import com.shop.pay.feature.refund.entity.RefundSplit;
import com.shop.pay.feature.refund.mapper.RefundMapper;
import com.shop.pay.feature.refund.mapper.RefundSplitMapper;
import com.shop.pay.feature.refund.statemachine.RefundStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-5 三段式编排器测试（TX1 短事务 → 事务外外部动作 → TX3 漏斗）。
 */
class RefundOrchestratorTest {

    private RefundMapper refundMapper;
    private RefundSplitMapper refundSplitMapper;
    private ChannelRouter channelRouter;
    private PayChannelClient channelClient;
    private UserClient userClient;
    private RefundConvergeService convergeService;
    private RefundOrchestrator orchestrator;

    private final List<RefundSplit> insertedSplits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        refundMapper = mock(RefundMapper.class);
        refundSplitMapper = mock(RefundSplitMapper.class);
        channelClient = mock(PayChannelClient.class);
        channelRouter = new ChannelRouter(List.of(channelClient));
        userClient = mock(UserClient.class);
        convergeService = mock(RefundConvergeService.class);
        when(convergeService.converge(any(), any(), any(), any()))
                .thenReturn(RefundConvergeService.ConvergeResult.ADVANCED_SUCCESS);

        // 无副作用事务管理器：模板回调内联执行，段二开始时事务必然已结束
        PlatformTransactionManager tm = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
        orchestrator = new RefundOrchestrator(new TransactionalTemplate(tm), convergeService,
                refundMapper, refundSplitMapper, channelRouter, userClient, new RefundStateMachine());

        // 模拟 MyBatis 回填主键/置 PROCESSING
        when(refundMapper.insert(any())).thenAnswer(inv -> {
            ((RefundOrder) inv.getArgument(0)).setId(11L);
            return 1;
        });
        when(refundSplitMapper.insert(any())).thenAnswer(inv -> {
            RefundSplit s = inv.getArgument(0);
            s.setId((long) (insertedSplits.size() + 1));
            insertedSplits.add(s);
            return 1;
        });
        when(refundMapper.markProcessing(anyString())).thenAnswer(inv -> {
            // 标记 TX1 的 PROCESSING CAS 已发生（段二断言此标志）
            processingMarked.set(true);
            return 1;
        });
        when(refundSplitMapper.markProcessingByRefundNo(anyString())).thenAnswer(inv -> {
            insertedSplits.forEach(s -> s.setStatus(RefundStatuses.PROCESSING.getCode()));
            return insertedSplits.size();
        });
        when(refundMapper.selectById(11L)).thenAnswer(inv -> currentOrder);
    }

    private final AtomicBoolean processingMarked = new AtomicBoolean(false);
    private RefundOrder currentOrder;

    private RefundOrder newOrder() {
        RefundOrder r = new RefundOrder();
        r.setId(11L);
        r.setRefundNo("R1");
        r.setPayNo("P1");
        r.setOrderNo("O1");
        r.setUserId(7L);
        r.setAmountFen(1000L);
        r.setStatus(RefundStatuses.WAIT.getCode());
        currentOrder = r;
        return r;
    }

    private ChannelFlow flow(String code, int method, long amount) {
        ChannelFlow f = new ChannelFlow();
        f.setPayNo("P1");
        f.setChannelCode(code);
        f.setPayMethod(method);
        f.setChannelOrderNo(code + "_O1");
        f.setChannelTransactionNo(code + "_T1");
        f.setAmountFen(amount);
        return f;
    }

    private RefundOrder runChannelSuccess() {
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.refund(any())).thenAnswer(inv -> {
            // P2-5 核心不变量：外部调用发生时 PROCESSING 已落库且不在任何事务内
            assertTrue(processingMarked.get(), "段二渠道调用前 TX1 必须已 markProcessing");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "段二渠道调用点不允许存在活动事务");
            ChannelRefundRequest req = inv.getArgument(0);
            return ChannelRefundResult.ok(req.getChannelCode(), "CHRF_" + req.getRefundNo());
        });
        List<ChannelFlow> flows = List.of(flow("MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 1000));
        return orchestrator.runNew(newOrder(), flows, List.of(1000L));
    }

    @SuppressWarnings("unchecked")
    private List<SplitOutcome> capturedOutcomes() {
        ArgumentCaptor<List<SplitOutcome>> captor = ArgumentCaptor.forClass(List.class);
        verify(convergeService).converge(any(), eq(RefundConvergeService.Trigger.SYNC),
                captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void 三段式成功_TX1置20后段二成功_TX3上报SUCCESS() {
        runChannelSuccess();
        List<SplitOutcome> outcomes = capturedOutcomes();
        assertEquals(1, outcomes.size());
        assertEquals(SplitOutcome.State.SUCCESS, outcomes.get(0).getState());
        assertEquals("CHRF_R1-0", outcomes.get(0).getChannelRefundNo());

        ArgumentCaptor<ChannelRefundRequest> req = ArgumentCaptor.forClass(ChannelRefundRequest.class);
        verify(channelClient).refund(req.capture());
        assertEquals("R1-0", req.getValue().getRefundNo(), "渠道幂等号必须为 refundNo-index");
        // 退款单在段二外部调用前已 CAS 置 PROCESSING(20)（段二调用内断言了该标志）
        assertTrue(processingMarked.get());
    }

    @Test
    void 段二渠道超时异常_单留20不回滚_上报PENDING不置失败() {
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.refund(any())).thenAnswer(inv -> {
            assertTrue(processingMarked.get());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            throw new RuntimeException("simulated read timeout");
        });
        orchestrator.runNew(newOrder(),
                List.of(flow("MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 1000)), List.of(1000L));

        List<SplitOutcome> outcomes = capturedOutcomes();
        assertEquals(SplitOutcome.State.PENDING, outcomes.get(0).getState());
        // 编排器自身不写任何退款单终态（终态只允许漏斗写）
        verify(refundMapper, never()).markSuccess(anyString(), any());
        verify(refundMapper, never()).markFail(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 渠道明确失败_上报FAIL留痕() {
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.refund(any())).thenReturn(
                ChannelRefundResult.fail("MOCK_ALIPAY", "余额不足"));
        orchestrator.runNew(newOrder(),
                List.of(flow("MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 1000)), List.of(1000L));

        List<SplitOutcome> outcomes = capturedOutcomes();
        assertEquals(SplitOutcome.State.FAIL, outcomes.get(0).getState());
        assertTrue(outcomes.get(0).getFailReason().contains("余额不足"));
    }

    @Test
    void 渠道受理中10_上报PENDING() {
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.refund(any())).thenReturn(
                ChannelRefundResult.builder().channelCode("MOCK_ALIPAY").status(10).build());
        orchestrator.runNew(newOrder(),
                List.of(flow("MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 1000)), List.of(1000L));

        assertEquals(SplitOutcome.State.PENDING, capturedOutcomes().get(0).getState());
    }

    @Test
    void 余额split_实时入账_bizNo为退款单号() {
        when(userClient.creditBalance(any())).thenAnswer(inv -> {
            assertTrue(processingMarked.get());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return Result.success();
        });
        orchestrator.runNew(newOrder(),
                List.of(flow("BALANCE", PayMethods.BALANCE.getCode(), 1000)), List.of(1000L));

        List<SplitOutcome> outcomes = capturedOutcomes();
        assertEquals(SplitOutcome.State.SUCCESS, outcomes.get(0).getState());
        assertEquals("BALANCE_CREDIT_R1", outcomes.get(0).getChannelRefundNo());
        verify(channelClient, never()).refund(any());
        ArgumentCaptor<com.shop.api.user.dto.AmountCommand> cmd =
                ArgumentCaptor.forClass(com.shop.api.user.dto.AmountCommand.class);
        verify(userClient).creditBalance(cmd.capture());
        assertEquals("R1", cmd.getValue().getBizNo());
    }

    @Test
    void 余额业务拒绝_上报FAIL() {
        when(userClient.creditBalance(any())).thenThrow(new BizException(
                com.shop.common.exception.ErrorCode.PAY_ERROR, "账户异常"));
        orchestrator.runNew(newOrder(),
                List.of(flow("BALANCE", PayMethods.BALANCE.getCode(), 1000)), List.of(1000L));

        assertEquals(SplitOutcome.State.FAIL, capturedOutcomes().get(0).getState());
    }

    @Test
    void 混合支付_渠道成功余额失败_结果聚合SUCCESS与FAIL() {
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.refund(any())).thenAnswer(inv ->
                ChannelRefundResult.ok("MOCK_ALIPAY", "CHRF"));
        when(userClient.creditBalance(any())).thenThrow(new BizException(
                com.shop.common.exception.ErrorCode.PAY_ERROR, "余额入账失败"));

        orchestrator.runNew(newOrder(), List.of(
                flow("MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 600),
                flow("BALANCE", PayMethods.BALANCE.getCode(), 400)), List.of(600L, 400L));

        List<SplitOutcome> outcomes = capturedOutcomes();
        assertEquals(2, outcomes.size());
        assertEquals(SplitOutcome.State.SUCCESS, outcomes.get(0).getState());
        assertEquals(SplitOutcome.State.FAIL, outcomes.get(1).getState());
    }

    @Test
    void retry_已成功split不重复打款_失败split续做() {
        when(channelClient.supports(anyString())).thenReturn(true);
        when(channelClient.refund(any())).thenAnswer(inv ->
                ChannelRefundResult.ok("MOCK_ALIPAY", "CHRF_RETRY"));
        when(userClient.creditBalance(any())).thenReturn(Result.success());
        when(refundMapper.reopen(anyString())).thenReturn(1);

        RefundOrder failed = newOrder();
        failed.setStatus(RefundStatuses.FAIL.getCode());
        RefundSplit channelDone = new RefundSplit();
        channelDone.setId(1L);
        channelDone.setRefundNo("R1");
        channelDone.setChannelCode("MOCK_ALIPAY");
        channelDone.setAmountFen(600L);
        channelDone.setStatus(RefundStatuses.SUCCESS.getCode());
        channelDone.setChannelRefundNo("CHRF_DONE");
        RefundSplit balanceFail = new RefundSplit();
        balanceFail.setId(2L);
        balanceFail.setRefundNo("R1");
        balanceFail.setChannelCode("BALANCE");
        balanceFail.setAmountFen(400L);
        balanceFail.setStatus(RefundStatuses.FAIL.getCode());
        when(refundSplitMapper.selectByRefundNo("R1")).thenReturn(List.of(channelDone, balanceFail));

        orchestrator.runRetry(failed, List.of(
                flow("MOCK_ALIPAY", PayMethods.ALIPAY.getCode(), 600),
                flow("BALANCE", PayMethods.BALANCE.getCode(), 400)));

        // TX1：reopen + markProcessing + 失败 split 回 20
        verify(refundMapper).reopen("R1");
        verify(refundMapper, atLeastOnce()).markProcessing("R1");
        verify(refundSplitMapper).markProcessingByRefundNo("R1");
        // 段二：渠道 split 已 30 不重复退款；余额补做一次
        verify(channelClient, never()).refund(any());
        verify(userClient).creditBalance(any());
        List<SplitOutcome> outcomes = capturedOutcomes();
        // 30 split 被跳过，只有余额补做结果上报（漏斗以 DB 中渠道 split=30 聚合）
        assertEquals(1, outcomes.size());
        assertEquals(SplitOutcome.State.SUCCESS, outcomes.get(0).getState());
    }
}
