package com.shop.pay.feature.recon.service;

import com.shop.api.pay.enums.ReconcileDiffTypes;
import com.shop.framework.tx.TransactionalTemplate;
import com.shop.pay.channel.ChannelBillRecord;
import com.shop.pay.channel.ChannelQueryResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.channel.PayChannelClient;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.recon.entity.ReconBatch;
import com.shop.pay.feature.recon.entity.ReconDiff;
import com.shop.pay.feature.recon.mapper.ReconBatchMapper;
import com.shop.pay.feature.recon.mapper.ReconDiffMapper;
import com.shop.pay.feature.recon.service.impl.ReconcileServiceImpl;
import com.shop.pay.feature.recon.support.ReconDiffDraft;
import com.shop.pay.feature.recon.support.ReconcileMatcher;
import com.shop.pay.support.PayNoGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2-4 批处理层：逐差异 REQUIRES_NEW 隔离、单条失败不回滚兄弟行、批次状态由未处置差异计数推导、
 * 短款渠道查询事务外完成且异常可重试不伪平账、失败差异下轮重试收敛。
 */
class ReconcileServiceImplTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 16);
    private static final String CHANNEL = "MOCK_ALIPAY";

    private ReconBatchMapper batchMapper;
    private ReconDiffMapper diffMapper;
    private PaymentMapper paymentMapper;
    private ChannelFlowMapper channelFlowMapper;
    private ReconcileMatcher reconcileMatcher;
    private ChannelRouter channelRouter;
    private PayChannelClient channelClient;
    private PayNoGenerator payNoGenerator;
    private ReconDiffHandler diffHandler;
    private ReconcileServiceImpl service;

    private ReconBatch batch;

    @BeforeEach
    void setUp() {
        batchMapper = mock(ReconBatchMapper.class);
        diffMapper = mock(ReconDiffMapper.class);
        paymentMapper = mock(PaymentMapper.class);
        channelFlowMapper = mock(ChannelFlowMapper.class);
        reconcileMatcher = mock(ReconcileMatcher.class);
        channelClient = mock(PayChannelClient.class);
        channelRouter = mock(ChannelRouter.class);
        payNoGenerator = mock(PayNoGenerator.class);
        diffHandler = mock(ReconDiffHandler.class);

        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionalTemplate txTemplate = new TransactionalTemplate(ptm);

        service = new ReconcileServiceImpl(batchMapper, diffMapper, paymentMapper, channelFlowMapper,
                reconcileMatcher, channelRouter, ChannelSecretProvider.devDefaults(), payNoGenerator,
                diffHandler, txTemplate);

        batch = new ReconBatch();
        batch.setId(1L);
        batch.setBatchNo("B1");
        batch.setReconDate(DATE);
        batch.setChannelCode(CHANNEL);
        batch.setStatus(20);

        when(batchMapper.selectOne(any())).thenReturn(null);
        when(payNoGenerator.batchNo()).thenReturn("B1");
        when(paymentMapper.selectSuccessBetween(any(), any())).thenReturn(List.of());
        when(channelRouter.route(anyString())).thenReturn(channelClient);
        when(channelClient.downloadBill(eq(CHANNEL), eq(DATE))).thenReturn(List.<ChannelBillRecord>of());
        when(reconcileMatcher.match(any(), any())).thenReturn(List.<ReconDiffDraft>of());
        when(diffMapper.insertIgnore(any())).thenReturn(1);
        doAnswer(inv -> {
            ReconBatch b = inv.getArgument(0);
            if (b.getId() == null) {
                b.setId(1L);
            }
            return 1;
        }).when(batchMapper).insert(any());
        when(batchMapper.selectById(1L)).thenAnswer(inv -> batch);
        doAnswer(inv -> {
            batch.setStatus(30);
            batch.setFinishTime(inv.getArgument(1));
            return 1;
        }).when(batchMapper).markFinished(anyLong(), any());
    }

    private ReconDiff diff(long id, int type, int status) {
        ReconDiff d = new ReconDiff();
        d.setId(id);
        d.setBatchNo("B1");
        d.setReconDate(DATE);
        d.setChannelCode(CHANNEL);
        d.setDiffType(type);
        d.setPayNo("P" + id);
        d.setOrderNo("O" + id);
        d.setChannelTxnNo("T" + id);
        d.setStatus(status);
        d.setRetryCount(0);
        d.setMaxRetry(5);
        return d;
    }

    private void stubBatchDiffs(List<ReconDiff> diffs) {
        when(diffMapper.selectByBatch("B1")).thenReturn(diffs);
    }

    // 用例 1：第 2 条处置抛异常——1/3 提交成功、第 2 条失败留痕、批次保持 20；下轮重试收敛后批次 30
    @Test
    void runReconcile_单差异抛异常_兄弟行提交且批次保持20_下轮重试收敛() {
        ReconDiff d1 = diff(1L, ReconcileDiffTypes.LONG.getCode(), 10);
        ReconDiff d2 = diff(2L, ReconcileDiffTypes.AMOUNT_MISMATCH.getCode(), 10);
        ReconDiff d3 = diff(3L, ReconcileDiffTypes.LONG.getCode(), 10);
        stubBatchDiffs(new ArrayList<>(List.of(d1, d2, d3)));
        when(diffHandler.handleOne(eq(d2), any()))
                .thenThrow(new RuntimeException("毒丸数据"))
                .thenReturn(new ReconDiffHandler.DiffHandleResult(2L,
                        ReconDiffHandler.DiffHandleResult.Outcome.HANDLED, "重试成功"));
        when(diffMapper.countPendingByBatch("B1")).thenReturn(1, 0);

        ReconBatch result = service.runReconcile(DATE, CHANNEL);

        // 三条都被尝试；失败仅第 2 条留痕，1/3 正常提交（不回滚）
        verify(diffHandler).handleOne(eq(d1), any());
        verify(diffHandler).handleOne(eq(d2), any());
        verify(diffHandler).handleOne(eq(d3), any());
        verify(diffHandler).markFailure(eq(d2), any(Exception.class));
        // 有残留差异：批次不完成
        verify(batchMapper, never()).markFinished(anyLong(), any());
        assertEquals(20, result.getStatus());

        // 下轮重试：仅剩 d2，本轮成功 → 批次 20→30
        when(diffMapper.selectPending(100)).thenReturn(List.of(d2));
        when(batchMapper.selectOne(any())).thenReturn(batch);
        int handled = service.retryPendingDiffs(100);

        assertEquals(1, handled);
        verify(batchMapper).markFinished(eq(1L), any());
    }

    // 用例 2：全部成功 → 批次 30，finishTime 落定
    @Test
    void runReconcile_全部差异处置成功_批次30落finishTime() {
        stubBatchDiffs(List.of(
                diff(1L, ReconcileDiffTypes.LONG.getCode(), 10),
                diff(2L, ReconcileDiffTypes.LONG.getCode(), 10)));
        when(diffMapper.countPendingByBatch("B1")).thenReturn(0);

        ReconBatch result = service.runReconcile(DATE, CHANNEL);

        verify(batchMapper).markFinished(eq(1L), any());
        assertEquals(30, result.getStatus());
    }

    // 用例 2b：无差异批次也直接完成
    @Test
    void runReconcile_无差错_批次30() {
        stubBatchDiffs(List.of());
        when(diffMapper.countPendingByBatch("B1")).thenReturn(0);

        service.runReconcile(DATE, CHANNEL);

        verify(batchMapper).markFinished(eq(1L), any());
    }

    // 用例 4：死锁仅回滚该条并重试留痕，兄弟行已提交不受影响，批次保持 20
    @Test
    void runReconcile_死锁异常_仅该条留痕兄弟行提交() {
        ReconDiff d1 = diff(1L, ReconcileDiffTypes.LONG.getCode(), 10);
        ReconDiff d2 = diff(2L, ReconcileDiffTypes.AMOUNT_MISMATCH.getCode(), 10);
        ReconDiff d3 = diff(3L, ReconcileDiffTypes.LONG.getCode(), 10);
        stubBatchDiffs(List.of(d1, d2, d3));
        when(diffHandler.handleOne(eq(d2), any()))
                .thenThrow(new DeadlockLoserDataAccessException("mock deadlock", null));
        when(diffMapper.countPendingByBatch("B1")).thenReturn(1);

        service.runReconcile(DATE, CHANNEL);

        verify(diffHandler).handleOne(eq(d1), any());
        verify(diffHandler).handleOne(eq(d3), any());
        verify(diffHandler).markFailure(eq(d2), any(DeadlockLoserDataAccessException.class));
        verify(batchMapper, never()).markFinished(anyLong(), any());
    }

    // 用例：短款渠道查询在事务外执行，结果传入处置器
    @Test
    void runReconcile_短款_渠道事务外查询结果传入处置器() {
        ReconDiff d = diff(1L, ReconcileDiffTypes.SHORT.getCode(), 10);
        stubBatchDiffs(List.of(d));
        ChannelFlow flow = new ChannelFlow();
        flow.setChannelCode(CHANNEL);
        flow.setChannelOrderNo("MO1");
        flow.setFlowStatus(10);
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(flow));
        when(channelClient.query(CHANNEL, "MO1")).thenReturn(
                ChannelQueryResult.of(ChannelQueryResult.State.PAYING));

        service.runReconcile(DATE, CHANNEL);

        verify(channelClient).query(CHANNEL, "MO1");
        verify(diffHandler).handleOne(eq(d),
                org.mockito.ArgumentMatchers.argThat(q -> q != null && q.getState() == ChannelQueryResult.State.PAYING));
    }

    // 用例：渠道查询异常 → 该差异失败留痕可重试、不进处置器（杜绝伪平账），其他差异照常
    @Test
    void runReconcile_渠道查询异常_差异留痕不伪平账且不阻塞同批() {
        ReconDiff shortDiff = diff(1L, ReconcileDiffTypes.SHORT.getCode(), 10);
        ReconDiff longDiff = diff(2L, ReconcileDiffTypes.LONG.getCode(), 10);
        stubBatchDiffs(List.of(shortDiff, longDiff));
        ChannelFlow flow = new ChannelFlow();
        flow.setChannelCode(CHANNEL);
        flow.setChannelOrderNo("MO1");
        when(channelFlowMapper.selectByPayNo("P1")).thenReturn(List.of(flow));
        when(channelClient.query(CHANNEL, "MO1")).thenThrow(new RuntimeException("channel 503"));
        when(diffMapper.countPendingByBatch("B1")).thenReturn(1);

        service.runReconcile(DATE, CHANNEL);

        // 短款未进处置器、无任何终态写入（不伪平账），仅失败留痕
        verify(diffHandler, never()).handleOne(eq(shortDiff), any());
        verify(diffHandler).markFailure(eq(shortDiff), any(Exception.class));
        // 同批长款照常处置
        verify(diffHandler).handleOne(eq(longDiff), any());
        verify(batchMapper, never()).markFinished(anyLong(), any());
    }

    // 用例：批次状态计数推导——存在 40 人工挂账但无 10/20 残留时仍可完成
    @Test
    void finalizeBatch_全部终态含人工挂账_批次30() {
        stubBatchDiffs(List.of(
                diff(1L, ReconcileDiffTypes.SHORT.getCode(), 40),
                diff(2L, ReconcileDiffTypes.LONG.getCode(), 30)));
        when(diffMapper.countPendingByBatch("B1")).thenReturn(0);

        service.runReconcile(DATE, CHANNEL);

        verify(batchMapper).markFinished(eq(1L), any());
    }

    // 用例 5：短款连续失败 5 次不阻塞同批其他差异（留痕路径），批次保持 20
    @Test
    void runReconcile_短款连续失败_不阻塞同批且批次保持20() {
        List<ReconDiff> diffs = new ArrayList<>();
        diffs.add(diff(1L, ReconcileDiffTypes.SHORT.getCode(), 10));
        diffs.add(diff(2L, ReconcileDiffTypes.LONG.getCode(), 10));
        stubBatchDiffs(diffs);
        when(channelFlowMapper.selectByPayNo(anyString())).thenAnswer(inv -> {
            ChannelFlow flow = new ChannelFlow();
            flow.setChannelCode(CHANNEL);
            flow.setChannelOrderNo("MO" + inv.getArgument(0));
            return List.of(flow);
        });
        // 渠道连续不可用（5 轮模拟由 retryJob 多次调用，这里单轮验证不阻塞兄弟行）
        when(channelClient.query(anyString(), anyString())).thenThrow(new RuntimeException("channel down"));
        when(diffMapper.countPendingByBatch("B1")).thenReturn(1);

        service.runReconcile(DATE, CHANNEL);

        verify(diffHandler).markFailure(eq(diffs.get(0)), any(Exception.class));
        verify(diffHandler).handleOne(eq(diffs.get(1)), any());
        verify(batchMapper, never()).markFinished(anyLong(), any());
    }

    // retryPendingDiffs：无残留批次不回推状态；selectPending 为空返回 0
    @Test
    void retryPendingDiffs_空轮次_返回0() {
        when(diffMapper.selectPending(100)).thenReturn(List.of());
        assertEquals(0, service.retryPendingDiffs(100));
        verify(batchMapper, never()).markFinished(anyLong(), any());
    }

    // 幂等：当日该渠道已对账直接返回既有批次，不重复拉账单/处置
    @Test
    void runReconcile_当日重复触发_幂等返回既有批次() {
        ReconBatch existed = new ReconBatch();
        existed.setId(9L);
        existed.setStatus(30);
        when(batchMapper.selectOne(any())).thenReturn(existed);

        ReconBatch result = service.runReconcile(DATE, CHANNEL);

        assertEquals(9L, result.getId());
        verify(channelClient, never()).downloadBill(anyString(), any());
        verify(batchMapper, never()).insert(any());
    }
}
