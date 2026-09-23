package com.shop.aftersale.aftersale.service;

import com.shop.aftersale.aftersale.entity.AftersaleDispute;
import com.shop.aftersale.aftersale.entity.AftersaleInsurance;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.mapper.AftersaleDisputeMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleInsuranceMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleOrderMapper;
import com.shop.aftersale.aftersale.service.impl.AftersaleTimeoutServiceImpl;
import com.shop.aftersale.mq.mapper.MqConsumeLogMapper;
import com.shop.aftersale.support.AftersaleDelayTopics;
import com.shop.aftersale.support.AftersalePolicy;
import com.shop.aftersale.support.AftersaleTimeoutMessage;
import com.shop.aftersale.support.FeignFailures;
import com.shop.api.user.client.UserClient;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.mq.MqConsumeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 超时分发：运费险 72h 理赔、举证期截止、双保险消息路由；
 * P2-1 dispatchTracked 确定性 eventId/同事务流水/双路径幂等；
 * R-B6 creditBalance null/空体/500 三类失败语义与失败可重试。
 */
@ExtendWith(MockitoExtension.class)
class AftersaleTimeoutServiceImplTest {

    @Mock private AftersaleService aftersaleService;
    @Mock private AftersaleInsuranceMapper insuranceMapper;
    @Mock private AftersaleDisputeMapper disputeMapper;
    @Mock private AftersaleOrderMapper orderMapper;
    @Mock private UserClient userClient;
    @Mock private MqConsumeLogMapper mqConsumeLogMapper;

    private AftersaleTimeoutService timeoutService;

    /** 无 Spring 环境下模拟 self 代理：dispatchTracked→dispatch 内的 self 调用回到本实例。 */
    private static AftersaleTimeoutService selfProxy(AtomicReference<AftersaleTimeoutService> holder) {
        return (AftersaleTimeoutService) Proxy.newProxyInstance(
                AftersaleTimeoutService.class.getClassLoader(),
                new Class<?>[]{AftersaleTimeoutService.class},
                (p, method, args) -> {
                    try {
                        return method.invoke(holder.get(), args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        if (cause instanceof Error err) {
                            throw err;
                        }
                        throw cause;
                    }
                });
    }

    @BeforeEach
    void setUp() {
        AtomicReference<AftersaleTimeoutService> holder = new AtomicReference<>();
        timeoutService = new AftersaleTimeoutServiceImpl(aftersaleService, insuranceMapper,
                disputeMapper, orderMapper, userClient, new AftersalePolicy(),
                selfProxy(holder), mqConsumeLogMapper);
        holder.set(timeoutService);
    }

    @AfterEach
    void clearContext() {
        MqConsumeContext.clear();
    }

    private AftersaleInsurance dueInsurance() {
        AftersaleInsurance ins = new AftersaleInsurance();
        ins.setId(7L);
        ins.setOrderNo("O1");
        ins.setUserId(1001L);
        ins.setClaimFen(2500L);
        ins.setStatus(10);
        ins.setClaimDeadline(LocalDateTime.now().minusHours(1));
        return ins;
    }

    // ============================ 既有 dispatch 路由（不回归） ============================

    @Test
    void dispatch_审核超时_路由自动同意() {
        timeoutService.dispatch(AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_AUDIT));
        verify(aftersaleService).autoApprove("AS1");
    }

    @Test
    void dispatch_收货与换货超时_正确路由() {
        timeoutService.dispatch(AftersaleTimeoutMessage.forAftersale("AS2", AftersaleDelayTopics.KIND_RECEIVE));
        timeoutService.dispatch(AftersaleTimeoutMessage.forAftersale("AS3", AftersaleDelayTopics.KIND_EXCHANGE_SHIP));
        verify(aftersaleService).autoConfirmReceive("AS2");
        verify(aftersaleService).autoConvertExchangeToRefund("AS3");
    }

    @Test
    void claimInsurance_超过72小时_理赔到余额() {
        when(insuranceMapper.selectById(7L)).thenReturn(dueInsurance());
        when(insuranceMapper.markClaimed(eq(7L), any())).thenReturn(1);
        when(userClient.creditBalance(any())).thenReturn(Result.success());

        timeoutService.claimInsurance(7L);

        verify(insuranceMapper).markClaimed(eq(7L), any());
        verify(userClient).creditBalance(any());
    }

    @Test
    void claimInsurance_未满72小时_不理赔() {
        AftersaleInsurance ins = new AftersaleInsurance();
        ins.setId(7L);
        ins.setStatus(10);
        ins.setClaimDeadline(LocalDateTime.now().plusHours(10));
        when(insuranceMapper.selectById(7L)).thenReturn(ins);

        timeoutService.claimInsurance(7L);

        verify(insuranceMapper, never()).markClaimed(any(), any());
        verify(userClient, never()).creditBalance(any());
    }

    @Test
    void closeEvidence_举证期满_转待裁决() {
        AftersaleDispute d = new AftersaleDispute();
        d.setId(3L);
        d.setAftersaleNo("AS1");
        d.setStatus(10);
        d.setEvidenceDeadline(LocalDateTime.now().minusHours(1));
        when(disputeMapper.selectByAftersaleNo("AS1")).thenReturn(d);

        timeoutService.closeEvidence("AS1");

        verify(disputeMapper).updateStatus(3L, 10, 20);
    }

    // ============================ P2-1：dispatchTracked 同事务流水 + 双路径幂等 ============================

    @Test
    void dispatchTracked_首次_流水与业务同事务顺序写入() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        AftersaleTimeoutMessage msg = AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_AUDIT);

        timeoutService.dispatchTracked(msg);

        var order = inOrder(mqConsumeLogMapper, aftersaleService);
        // eventId 非空且确定性；topic 与消费组沿用不改
        order.verify(mqConsumeLogMapper).insertIgnore("TO-AS1-audit",
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, "AS1");
        order.verify(aftersaleService).autoApprove("AS1");
    }

    @Test
    void dispatchTracked_重复eventId_直接ACK业务零调用() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(0);

        timeoutService.dispatchTracked(
                AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_AUDIT));

        verify(aftersaleService, never()).autoApprove(any());
        verify(insuranceMapper, never()).markClaimed(any(), any());
    }

    @Test
    void dispatchTracked_MQ与扫表交叉_先执行者写流水后到者noop() {
        // job 先执行：insertIgnore=1 业务生效
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        AftersaleTimeoutMessage fromJob =
                AftersaleTimeoutMessage.forAftersale("AS8", AftersaleDelayTopics.KIND_RECEIVE);
        timeoutService.dispatchTracked(fromJob);
        verify(aftersaleService).autoConfirmReceive("AS8");

        // MQ 重放同一业务超时（同确定性 eventId）：UK 命中返回 0，no-op
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(0);
        AftersaleTimeoutMessage fromMq =
                AftersaleTimeoutMessage.forAftersale("AS8", AftersaleDelayTopics.KIND_RECEIVE);
        timeoutService.dispatchTracked(fromMq);
        verify(aftersaleService, times(1)).autoConfirmReceive("AS8");
    }

    @Test
    void dispatchTracked_evidence与insurance分支_同样先走流水() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        AftersaleDispute d = new AftersaleDispute();
        d.setId(3L);
        d.setAftersaleNo("AS1");
        d.setStatus(10);
        d.setEvidenceDeadline(LocalDateTime.now().minusHours(1));
        when(disputeMapper.selectByAftersaleNo("AS1")).thenReturn(d);
        when(insuranceMapper.selectById(7L)).thenReturn(dueInsurance());
        when(insuranceMapper.markClaimed(eq(7L), any())).thenReturn(1);
        when(userClient.creditBalance(any())).thenReturn(Result.success());

        timeoutService.dispatchTracked(
                AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_EVIDENCE));
        timeoutService.dispatchTracked(
                AftersaleTimeoutMessage.forInsurance(7L, "AS1", AftersaleDelayTopics.KIND_INSURANCE));

        verify(mqConsumeLogMapper).insertIgnore("TO-AS1-evidence",
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, "AS1");
        verify(mqConsumeLogMapper).insertIgnore("TO-INS-7-insurance",
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, "AS1");
        verify(disputeMapper).updateStatus(3L, 10, 20);
        verify(userClient).creditBalance(any());
    }

    @Test
    void dispatchTracked_历史存量消息无eventId_按业务键现算兜底() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        AftersaleTimeoutMessage legacy = new AftersaleTimeoutMessage("AS5", AftersaleDelayTopics.KIND_AUDIT, null);
        legacy.setEventId("");

        timeoutService.dispatchTracked(legacy);

        verify(mqConsumeLogMapper).insertIgnore("TO-AS5-audit",
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, "AS5");
    }

    @Test
    void dispatchTracked_框架合成eventId被确定性ID替换_重放命中同一行() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        // 模拟极旧 outbox 消息被 EventNormalizer 兜底合成 noid
        MqConsumeContext.bind(new MqConsumeContext(
                "noid:shop_aftersale_timeout:msg123",
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, "msg123", "msg123", true));
        AftersaleTimeoutMessage msg = new AftersaleTimeoutMessage("AS6", AftersaleDelayTopics.KIND_AUDIT, null);
        msg.setEventId("noid:shop_aftersale_timeout:msg123");

        timeoutService.dispatchTracked(msg);

        verify(mqConsumeLogMapper).insertIgnore("TO-AS6-audit",
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, "AS6");
    }

    // ============================ R4-26：跨轮乱序守卫 ============================

    @Test
    void dispatchTracked_旧轮audit消息在重提第二轮窗口迟到_直接丢弃不落库不流转() {
        // 售后单已重提：当前审核截止点是第二轮（+2 天）
        LocalDateTime round2Deadline = LocalDateTime.now().plusDays(2);
        AftersaleOrder o = new AftersaleOrder();
        o.setAftersaleNo("AS1");
        o.setAuditDeadline(round2Deadline);
        when(orderMapper.selectByNo("AS1")).thenReturn(o);
        // 第一轮（两天前截止）的延时消息此刻才被 broker 投递
        String staleRound =
                AftersaleTimeoutMessage.deadlineRoundKey(LocalDateTime.now().minusDays(2));
        AftersaleTimeoutMessage stale =
                AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_AUDIT, staleRound);

        timeoutService.dispatchTracked(stale);

        verify(mqConsumeLogMapper, never()).insertIgnore(any(), any(), any());
        verify(aftersaleService, never()).autoApprove(any());
    }

    @Test
    void dispatchTracked_当前轮audit消息_守卫通过正常落库流转() {
        LocalDateTime deadline = LocalDateTime.now().plusDays(2);
        AftersaleOrder o = new AftersaleOrder();
        o.setAftersaleNo("AS1");
        o.setAuditDeadline(deadline);
        when(orderMapper.selectByNo("AS1")).thenReturn(o);
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        String round = AftersaleTimeoutMessage.deadlineRoundKey(deadline);
        AftersaleTimeoutMessage msg =
                AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_AUDIT, round);

        timeoutService.dispatchTracked(msg);

        verify(mqConsumeLogMapper).insertIgnore("TO-AS1-audit-R" + round,
                AftersaleDelayTopics.AFTERSALE_TIMEOUT, "AS1");
        verify(aftersaleService).autoApprove("AS1");
    }

    @Test
    void dispatchTracked_当前已无截止点_带轮次旧receive消息丢弃() {
        // 售后单已推进到后续状态：receive_deadline 清空，旧轮 receive 消息迟到
        AftersaleOrder o = new AftersaleOrder();
        o.setAftersaleNo("AS2");
        when(orderMapper.selectByNo("AS2")).thenReturn(o);
        AftersaleTimeoutMessage stale = AftersaleTimeoutMessage.forAftersale(
                "AS2", AftersaleDelayTopics.KIND_RECEIVE, "1700000000000");

        timeoutService.dispatchTracked(stale);

        verify(mqConsumeLogMapper, never()).insertIgnore(any(), any(), any());
        verify(aftersaleService, never()).autoConfirmReceive(any());
    }

    @Test
    void dispatchTracked_evidence消息不属轮次维度_不查售后单直接走流水() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        AftersaleDispute d = new AftersaleDispute();
        d.setId(3L);
        d.setAftersaleNo("AS1");
        d.setStatus(10);
        d.setEvidenceDeadline(LocalDateTime.now().minusHours(1));
        when(disputeMapper.selectByAftersaleNo("AS1")).thenReturn(d);

        timeoutService.dispatchTracked(
                AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_EVIDENCE));

        verify(orderMapper, never()).selectByNo(any());
        verify(disputeMapper).updateStatus(3L, 10, 20);
    }

    // ============================ P2-1 × R-B6：失败回滚流水 → 可重试闭合 ============================

    @Test
    void dispatchTracked_理赔失败_异常上抛且同eventId可重试成功仅入账一次() {
        when(mqConsumeLogMapper.insertIgnore(any(), any(), any())).thenReturn(1);
        when(insuranceMapper.selectById(7L)).thenReturn(dueInsurance());
        when(insuranceMapper.markClaimed(eq(7L), any())).thenReturn(1);
        // 首次下游空体：失败
        when(userClient.creditBalance(any())).thenReturn(null, Result.success());

        AftersaleTimeoutMessage msg =
                AftersaleTimeoutMessage.forInsurance(7L, "AS1", AftersaleDelayTopics.KIND_INSURANCE);
        BizException ex = assertThrows(BizException.class, () -> timeoutService.dispatchTracked(msg));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());

        // 生产环境 @Transactional 会把 markClaimed 与消费流水一并回滚；恢复后同 eventId 重新投递
        timeoutService.dispatchTracked(msg);
        verify(userClient, times(2)).creditBalance(any());
        // 两次调用携带同一 bizNo 幂等键：即使首次请求在 user 侧已入账，重放也不会产生第二笔
        verify(userClient, times(2)).creditBalance(org.mockito.ArgumentMatchers.argThat(
                c -> "INS:O1".equals(c.getBizNo()) && c.getAmountFen() == 2500L));
    }

    // ============================ R-B6：creditBalance 三类失败形态（9 用例之 3） ============================

    @Test
    void claimInsurance_下游返回null_抛DEPENDENCY_FAIL不标记成功() {
        when(insuranceMapper.selectById(7L)).thenReturn(dueInsurance());
        when(insuranceMapper.markClaimed(eq(7L), any())).thenReturn(1);
        when(userClient.creditBalance(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> timeoutService.claimInsurance(7L));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        verify(insuranceMapper).markClaimed(eq(7L), any());
    }

    @Test
    void claimInsurance_下游非success空体语义_抛DEPENDENCY_FAIL() {
        when(insuranceMapper.selectById(7L)).thenReturn(dueInsurance());
        when(insuranceMapper.markClaimed(eq(7L), any())).thenReturn(1);
        when(userClient.creditBalance(any())).thenReturn(Result.fail(50000, "余额服务内部错误"));

        BizException ex = assertThrows(BizException.class, () -> timeoutService.claimInsurance(7L));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }

    @Test
    void claimInsurance_下游HTTP500经框架解码_抛可恢复DEPENDENCY_FAIL() {
        when(insuranceMapper.selectById(7L)).thenReturn(dueInsurance());
        when(insuranceMapper.markClaimed(eq(7L), any())).thenReturn(1);
        when(userClient.creditBalance(any()))
                .thenThrow(FeignFailures.serverError500("UserClient#creditBalance"));

        BizException ex = assertThrows(BizException.class, () -> timeoutService.claimInsurance(7L));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
    }
}
