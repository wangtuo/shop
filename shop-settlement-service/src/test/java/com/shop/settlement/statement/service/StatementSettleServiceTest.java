package com.shop.settlement.statement.service;

import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.service.ClearingService;
import com.shop.settlement.statement.mapper.StatementMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日终结算批编排单测（P1-4）：500/页游标翻页、逐单调用独立事务执行器、
 * 单条失败隔离不滚整批且失败明细可追踪、幂等跳过不计入结算数。
 *
 * <p>单笔事务/记账/outbox 语义在 {@link SettleClearingExecutorTest}（mock）与
 * 集成测试（真实 MySQL 独立事务验证）中覆盖。
 */
@ExtendWith(MockitoExtension.class)
class StatementSettleServiceTest {

    @Mock private ClearingService clearingService;
    @Mock private SettleClearingExecutor settleClearingExecutor;
    @Mock private StatementMapper statementMapper;

    private StatementSettleService service;
    private final LocalDate today = LocalDate.of(2026, 9, 16);

    @BeforeEach
    void setUp() {
        service = new StatementSettleService(clearingService, settleClearingExecutor, statementMapper);
    }

    private SettClearing clearing(long id, String no, long merchantId) {
        SettClearing c = new SettClearing();
        c.setId(id);
        c.setClearingNo(no);
        c.setOrderNo("O" + id);
        c.setMerchantId(merchantId);
        return c;
    }

    private SettleClearingExecutor.SettleItem settled(SettClearing c, String statementNo, long amount) {
        return new SettleClearingExecutor.SettleItem(true, c.getId(), c.getClearingNo(),
                c.getMerchantId(), statementNo, amount);
    }

    private SettleClearingExecutor.SettleItem skipped(SettClearing c) {
        return new SettleClearingExecutor.SettleItem(false, c.getId(), c.getClearingNo(),
                c.getMerchantId(), null, 0L);
    }

    @Test
    @DisplayName("settlePage_多商户多结算单_逐单调用独立事务执行器并按结算单汇总")
    void settlePage_aggregateByStatement() {
        SettClearing c1 = clearing(1L, "CL1", 100L);
        SettClearing c2 = clearing(2L, "CL2", 100L);
        SettClearing c3 = clearing(3L, "CL3", 200L);
        when(clearingService.selectDuePage(0L, today, StatementSettleService.PAGE_SIZE))
                .thenReturn(List.of(c1, c2, c3));
        when(settleClearingExecutor.settleOne(eq(c1), eq(today)))
                .thenReturn(settled(c1, "ST1", 1_000L));
        when(settleClearingExecutor.settleOne(eq(c2), eq(today)))
                .thenReturn(settled(c2, "ST1", 1_500L));
        when(settleClearingExecutor.settleOne(eq(c3), eq(today)))
                .thenReturn(settled(c3, "ST2", 500L));

        StatementSettleService.SettlePageResult result = service.settlePage(0L, today);

        assertEquals(3, result.getSettledCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(3, result.getScannedCount());
        assertEquals(3L, result.getLastId());
        assertEquals(2, result.getMerchantSettles().size());
        assertEquals(2_500L, result.getMerchantSettles().get(0).getAmountFen());
        assertEquals("ST1", result.getMerchantSettles().get(0).getStatementNo());
        assertEquals(500L, result.getMerchantSettles().get(1).getAmountFen());
        verify(settleClearingExecutor, times(3)).settleOne(any(), eq(today));
    }

    @Test
    @DisplayName("settlePage_执行器返回跳过(流水已存在/并发0行)_不计结算数且不产生失败")
    void settlePage_skippedItems_notCounted() {
        SettClearing c1 = clearing(1L, "CL1", 100L);
        SettClearing c2 = clearing(2L, "CL2", 200L);
        when(clearingService.selectDuePage(0L, today, StatementSettleService.PAGE_SIZE))
                .thenReturn(List.of(c1, c2));
        when(settleClearingExecutor.settleOne(eq(c1), eq(today))).thenReturn(skipped(c1));
        when(settleClearingExecutor.settleOne(eq(c2), eq(today))).thenReturn(skipped(c2));

        StatementSettleService.SettlePageResult result = service.settlePage(0L, today);

        assertEquals(0, result.getSettledCount());
        assertEquals(0, result.getFailedCount());
        assertEquals(2, result.getScannedCount());
        assertEquals(0, result.getMerchantSettles().size());
    }

    @Test
    @DisplayName("P1-4_settlePage_单笔抛错只回滚该笔_其他笔正常结算且失败明细可追踪")
    void settlePage_singleFailure_isolatedAndTracked() {
        SettClearing c1 = clearing(1L, "CL1", 100L);
        SettClearing c2 = clearing(2L, "CL2", 100L);
        SettClearing c3 = clearing(3L, "CL3", 200L);
        when(clearingService.selectDuePage(0L, today, StatementSettleService.PAGE_SIZE))
                .thenReturn(List.of(c1, c2, c3));
        // 中间一笔独立事务失败（例如记账异常）
        when(settleClearingExecutor.settleOne(eq(c1), eq(today)))
                .thenReturn(settled(c1, "ST1", 1_000L));
        when(settleClearingExecutor.settleOne(eq(c2), eq(today)))
                .thenThrow(new RuntimeException("模拟记账失败"));
        when(settleClearingExecutor.settleOne(eq(c3), eq(today)))
                .thenReturn(settled(c3, "ST2", 700L));

        StatementSettleService.SettlePageResult result = service.settlePage(0L, today);

        // c1/c3 正常结算，c2 失败被隔离
        assertEquals(2, result.getSettledCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(1, result.getFailures().size());
        StatementSettleService.FailedItem fail = result.getFailures().get(0);
        assertEquals(2L, fail.getClearingId());
        assertEquals("CL2", fail.getClearingNo());
        assertEquals("O2", fail.getOrderNo());
        assertEquals(100L, fail.getMerchantId());
        // 游标仍推进到最后一条，整批不中断
        assertEquals(3L, result.getLastId());
        assertEquals(2, result.getMerchantSettles().size());
    }

    @Test
    @DisplayName("runDailySettle_首页500条翻页_游标正确推进且每页独立汇总")
    void runDailySettle_paging500_cursor() {
        List<SettClearing> page1 = new ArrayList<>();
        for (long i = 1; i <= 500; i++) {
            page1.add(clearing(i, "CL" + i, 100L));
        }
        List<SettClearing> page2 = new ArrayList<>();
        for (long i = 501; i <= 503; i++) {
            page2.add(clearing(i, "CL" + i, 100L));
        }
        when(clearingService.selectDuePage(0L, today, StatementSettleService.PAGE_SIZE))
                .thenReturn(page1);
        when(clearingService.selectDuePage(500L, today, StatementSettleService.PAGE_SIZE))
                .thenReturn(page2);
        when(settleClearingExecutor.settleOne(any(), eq(today)))
                .thenAnswer(inv -> settled(inv.getArgument(0), "ST1", 100L));

        List<StatementSettleService.MerchantSettle> all = service.runDailySettle(today);

        verify(clearingService).selectDuePage(0L, today, StatementSettleService.PAGE_SIZE);
        verify(clearingService).selectDuePage(500L, today, StatementSettleService.PAGE_SIZE);
        // 每一页一个汇总（不同页各自的 LinkedHashMap），金额 50000 / 300
        assertEquals(2, all.size());
        assertEquals(50_000L, all.get(0).getAmountFen());
        assertEquals(300L, all.get(1).getAmountFen());
        verify(settleClearingExecutor, times(503)).settleOne(any(), eq(today));
    }

    @Test
    @DisplayName("runDailySettle_整页全失败_游标继续推进不中断且最终返回0结算")
    void runDailySettle_allFailedPage_continues() {
        SettClearing c1 = clearing(1L, "CL1", 100L);
        when(clearingService.selectDuePage(0L, today, StatementSettleService.PAGE_SIZE))
                .thenReturn(List.of(c1));
        when(settleClearingExecutor.settleOne(any(), eq(today)))
                .thenThrow(new RuntimeException("记账失败"));

        List<StatementSettleService.MerchantSettle> all = service.runDailySettle(today);

        assertEquals(0, all.size());
        verify(settleClearingExecutor, times(1)).settleOne(any(), eq(today));
        verify(clearingService, never()).selectDuePage(eq(1L), eq(today)
                , eq(StatementSettleService.PAGE_SIZE));
    }
}
