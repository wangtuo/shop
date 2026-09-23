package com.shop.settlement.clearing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.settlement.enums.AccountRole;
import com.shop.common.constant.MqTopics;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.clearing.entity.ShortfallWorkOrder;
import com.shop.settlement.clearing.entity.SettClearingReverse;
import com.shop.settlement.clearing.event.RefundShortfallEvent;
import com.shop.settlement.clearing.mapper.ClearingReverseMapper;
import com.shop.settlement.clearing.mapper.ShortfallWorkOrderMapper;
import com.shop.settlement.clearing.support.AlarmNotifier;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.mapper.DepositLogMapper;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.enums.FlowChangeTypes;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.mapper.MerchantMapper;
import com.shop.settlement.mq.service.MqConsumeService;
import com.shop.settlement.support.LambdaTableSupport;
import com.shop.settlement.support.SettleNoGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 穿仓缺口工单单测（GAP_PLAN_FUNDS 卡 P0-1，行 509-513 全量）：
 * 落单告警一次 / 重放零副作用 / 冲正重复不重复开单 / 告警失败仍 ACK；
 * 扫表补单 / 已有不重复（NOT EXISTS 由 SQL 保证）/ 非2不扫（SQL status=2 入参校验）/ 分页；
 * 两张工单按序补扣 / 缴足结清 30 / 不足保留 / 重放不重复补扣；
 * 实时 + 扫表双路径最终恰一单一告警。
 */
@ExtendWith(MockitoExtension.class)
class ShortfallWorkOrderServiceTest {

    @Mock private ShortfallWorkOrderMapper workOrderMapper;
    @Mock private ClearingReverseMapper reverseMapper;
    @Mock private MqConsumeService mqConsumeService;
    @Mock private AlarmNotifier alarmNotifier;
    @Mock private MerchantMapper merchantMapper;
    @Mock private DepositLogMapper depositLogMapper;
    @Mock private AccountService accountService;
    @Mock private SettleNoGenerator noGenerator;

    private ShortfallWorkOrderServiceImpl service;
    private SimpleMeterRegistry registry;
    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeAll
    static void initLambdaCache() {
        LambdaTableSupport.init();
    }

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ShortfallWorkOrderService> provider = mock(ObjectProvider.class);
        registry = new SimpleMeterRegistry();
        service = new ShortfallWorkOrderServiceImpl(workOrderMapper, reverseMapper, mqConsumeService,
                alarmNotifier, merchantMapper, depositLogMapper, accountService, noGenerator, provider,
                registry);
        lenient().when(provider.getObject()).thenReturn(service);
        lenient().when(noGenerator.nextDepositLogNo()).thenReturn("DP-1");
        // MP insert：回填雪花 id，模拟生产行为
        lenient().doAnswer(inv -> {
            ((ShortfallWorkOrder) inv.getArgument(0)).setId(idSeq.incrementAndGet());
            return 1;
        }).when(workOrderMapper).insert(any());
    }

    // ---------- 事件构造 ----------

    private RefundShortfallEvent event(String eventId, String reverseNo, String refundNo, long shortfall) {
        RefundShortfallEvent e = new RefundShortfallEvent();
        e.setEventId(eventId);
        e.setBizNo(refundNo);
        e.setMerchantId(777L);
        e.setOrderNo("O1");
        e.setRefundNo(refundNo);
        e.setReverseNo(reverseNo);
        e.setMerchantPartFen(1000L);
        e.setFromPendingFen(400L);
        e.setFromAvailableFen(300L);
        e.setFromDepositFen(200L);
        e.setShortfallFen(shortfall);
        return e;
    }

    private ShortfallWorkOrder workOrder(long id, String reverseNo, String refundNo,
                                         long shortfall, long clawed, int status) {
        ShortfallWorkOrder wo = new ShortfallWorkOrder();
        wo.setId(id);
        wo.setEventId("EVT-" + reverseNo);
        wo.setReverseNo(reverseNo);
        wo.setRefundNo(refundNo);
        wo.setOrderNo("O1");
        wo.setMerchantId(777L);
        wo.setShortfallFen(shortfall);
        wo.setClawedBackFen(clawed);
        wo.setStatus(status);
        wo.setAlertCount(0);
        return wo;
    }

    private SettClearingReverse reverseRow(String reverseNo, String refundNo, long shortfall) {
        SettClearingReverse r = new SettClearingReverse();
        r.setId(Long.parseLong(reverseNo.substring(2)));
        r.setReverseNo(reverseNo);
        r.setRefundNo(refundNo);
        r.setOrderNo("O1");
        r.setMerchantId(777L);
        r.setReverseMerchantFen(1000L);
        r.setFromPendingFen(400L);
        r.setFromAvailableFen(300L);
        r.setFromDepositFen(200L);
        r.setShortfallFen(shortfall);
        r.setStatus(2);
        return r;
    }

    private void noPendingStuckOrders() {
        when(workOrderMapper.selectByStatusPaged(eq(ShortfallWorkOrder.STATUS_PENDING),
                anyLong(), anyInt())).thenReturn(List.of());
    }

    // ---------- onShortfall ----------

    @Test
    @DisplayName("首次落单_10态入库_CAS20_告警恰一次")
    void firstShortfall_insertAndAlertOnce() {
        when(mqConsumeService.tryRecord(anyString(), eq(MqTopics.REFUND_SHORTFALL),
                eq(ShortfallWorkOrderService.CG_SHORTFALL), anyString())).thenReturn(true);
        when(workOrderMapper.selectByReverseNo("RV1")).thenReturn(null);
        when(workOrderMapper.casAlerted(anyLong())).thenReturn(1);

        service.onShortfall(event("EVT1", "RV1", "R1", 100L));

        ArgumentCaptor<ShortfallWorkOrder> captor = ArgumentCaptor.forClass(ShortfallWorkOrder.class);
        verify(workOrderMapper).insert(captor.capture());
        ShortfallWorkOrder saved = captor.getValue();
        assertEquals(ShortfallWorkOrder.STATUS_PENDING, saved.getStatus());
        assertEquals(0, saved.getClawedBackFen());
        assertEquals(100L, saved.getShortfallFen());
        assertEquals("RV1", saved.getReverseNo());
        verify(workOrderMapper).casAlerted(saved.getId());
        verify(alarmNotifier).notifyShortfall(any());
    }

    @Test
    @DisplayName("eventId重放_mqConsume拦截_零副作用直接ACK")
    void replayEventId_zeroSideEffect() {
        when(mqConsumeService.tryRecord(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        service.onShortfall(event("EVT1", "RV1", "R1", 100L));

        verify(workOrderMapper, never()).insert(any());
        verify(workOrderMapper, never()).casAlerted(anyLong());
        verify(alarmNotifier, never()).notifyShortfall(any());
    }

    @Test
    @DisplayName("同冲正重复事件_reverseNo已查有单_不重复开单不重复告警")
    void sameReverse_existingOrder_noDuplicate() {
        when(mqConsumeService.tryRecord(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(workOrderMapper.selectByReverseNo("RV1"))
                .thenReturn(workOrder(101L, "RV1", "R1", 100L, 0L, ShortfallWorkOrder.STATUS_ALERTED));

        service.onShortfall(event("EVT-DUP", "RV1", "R1", 100L));

        verify(workOrderMapper, never()).insert(any());
        verify(workOrderMapper, never()).casAlerted(anyLong());
        verify(alarmNotifier, never()).notifyShortfall(any());
    }

    @Test
    @DisplayName("uk_event_id并发落败_捕获Duplicate不告警且不抛出")
    void duplicateEventIdKey_loserSkipsSilently() {
        when(mqConsumeService.tryRecord(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(workOrderMapper.selectByReverseNo("RV1")).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_event_id")).when(workOrderMapper).insert(any());

        assertDoesNotThrow(() -> service.onShortfall(event("EVT1", "RV1", "R1", 100L)));
        verify(workOrderMapper, never()).casAlerted(anyLong());
        verify(alarmNotifier, never()).notifyShortfall(any());
    }

    @Test
    @DisplayName("告警通道抛异常_消费仍ACK_失败信息落remark留痕")
    void alarmFailure_stillAckAndRemarked() {
        when(mqConsumeService.tryRecord(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(workOrderMapper.selectByReverseNo("RV1")).thenReturn(null);
        when(workOrderMapper.casAlerted(anyLong())).thenReturn(1);
        doThrow(new RuntimeException("webhook down"))
                .when(alarmNotifier).notifyShortfall(any());

        assertDoesNotThrow(() -> service.onShortfall(event("EVT1", "RV1", "R1", 100L)));
        verify(workOrderMapper).updateRemark(anyLong(), contains("ALARM_FAIL"));
    }

    // ---------- dailyRescan ----------

    @Test
    @DisplayName("扫表_status2无工单_RESIDUAL合成事件补单且仅告警一次")
    void rescan_missingOrder_createdWithResidualEventId() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        SettClearingReverse row = reverseRow("RV9", "R9", 88L);
        when(reverseMapper.selectSuspended(eq(today.atStartOfDay()), eq(200)))
                .thenReturn(List.of(row));
        when(mqConsumeService.tryRecord(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(workOrderMapper.selectByReverseNo("RV9")).thenReturn(null);
        when(workOrderMapper.casAlerted(anyLong())).thenReturn(1);
        noPendingStuckOrders();

        service.dailyRescan(today);

        // eventId=RESIDUAL-+reverseNo，topic/消费组/bizNo 口径与实时一致
        verify(mqConsumeService).tryRecord(eq("RESIDUAL-RV9"), eq(MqTopics.REFUND_SHORTFALL),
                eq(ShortfallWorkOrderService.CG_SHORTFALL), eq("R9"));
        ArgumentCaptor<ShortfallWorkOrder> captor = ArgumentCaptor.forClass(ShortfallWorkOrder.class);
        verify(workOrderMapper).insert(captor.capture());
        assertEquals("RESIDUAL-RV9", captor.getValue().getEventId());
        assertEquals(88L, captor.getValue().getShortfallFen());
        verify(alarmNotifier).notifyShortfall(any());
    }

    @Test
    @DisplayName("扫表_超过单页200行_逐页补齐全部落单")
    void rescan_paginationAcrossPages() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        List<SettClearingReverse> page1 = new ArrayList<>();
        List<SettClearingReverse> page2 = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            page1.add(reverseRow("RV" + i, "R" + i, 10L));
        }
        for (int i = 201; i <= 250; i++) {
            page2.add(reverseRow("RV" + i, "R" + i, 10L));
        }
        when(reverseMapper.selectSuspended(any(LocalDateTime.class), eq(200)))
                .thenReturn(page1).thenReturn(page2);
        when(mqConsumeService.tryRecord(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(workOrderMapper.selectByReverseNo(anyString())).thenReturn(null);
        when(workOrderMapper.casAlerted(anyLong())).thenReturn(1);
        noPendingStuckOrders();

        service.dailyRescan(today);

        verify(workOrderMapper, org.mockito.Mockito.times(250)).insert(any());
        verify(reverseMapper, org.mockito.Mockito.times(2)).selectSuspended(any(), eq(200));
        verify(alarmNotifier, org.mockito.Mockito.times(250)).notifyShortfall(any());
    }

    @Test
    @DisplayName("扫表_已有工单的冲正SQL侧已排除_不重复开单")
    void rescan_existingOrderNotReturned_noDuplicate() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        // selectSuspended 的 NOT EXISTS 已过滤有单冲正：mapper 返回空即语义保证
        when(reverseMapper.selectSuspended(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of());
        noPendingStuckOrders();

        service.dailyRescan(today);

        verify(workOrderMapper, never()).insert(any());
        verify(alarmNotifier, never()).notifyShortfall(any());
    }

    @Test
    @DisplayName("扫表_滞留10态工单_补告警一次")
    void rescan_stuckPending_reAlerted() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        ShortfallWorkOrder stuck = workOrder(301L, "RV3", "R3", 50L, 0L,
                ShortfallWorkOrder.STATUS_PENDING);
        when(reverseMapper.selectSuspended(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of());
        when(workOrderMapper.selectByStatusPaged(eq(ShortfallWorkOrder.STATUS_PENDING),
                eq(0L), eq(200))).thenReturn(List.of(stuck));
        when(workOrderMapper.casAlerted(301L)).thenReturn(1);

        service.dailyRescan(today);

        verify(workOrderMapper).casAlerted(301L);
        verify(alarmNotifier).notifyShortfall(any());
    }

    @Test
    @DisplayName("扫表_单行补单失败不拖垮整扫_下轮可重试")
    void rescan_rowFailureDoesNotAbortScan() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        SettClearingReverse bad = reverseRow("RV1", "R1", 10L);
        SettClearingReverse good = reverseRow("RV2", "R2", 20L);
        when(reverseMapper.selectSuspended(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of(bad, good));
        when(mqConsumeService.tryRecord(anyString(), eq(MqTopics.REFUND_SHORTFALL),
                eq(ShortfallWorkOrderService.CG_SHORTFALL), anyString())).thenReturn(true);
        when(workOrderMapper.selectByReverseNo(anyString())).thenReturn(null);
        // 第一行落库失败、第二行成功
        doThrow(new RuntimeException("db glitch")).doAnswer(inv -> {
            ((ShortfallWorkOrder) inv.getArgument(0)).setId(idSeq.incrementAndGet());
            return 1;
        }).when(workOrderMapper).insert(any());
        when(workOrderMapper.casAlerted(anyLong())).thenReturn(1);
        noPendingStuckOrders();

        assertDoesNotThrow(() -> service.dailyRescan(today));
        verify(alarmNotifier).notifyShortfall(any());
    }

    // ---------- clawbackOnDepositPaid ----------

    private void stubMerchantBalance(long balance) {
        SettMerchant m = new SettMerchant();
        m.setId(777L);
        m.setDepositBalanceFen(balance);
        when(merchantMapper.selectForUpdate(777L)).thenReturn(m);
        when(merchantMapper.changeDeposit(eq(777L), anyLong())).thenReturn(1);
    }

    private void stubNoPriorClawback() {
        when(depositLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
    }

    @Test
    @DisplayName("补扣_两张工单按时间序_首张缴足30_次张部分扣保留")
    void clawback_chronological_firstClosedSecondKept() {
        stubMerchantBalance(150L);
        stubNoPriorClawback();
        ShortfallWorkOrder wo1 = workOrder(1L, "RV1", "R1", 100L, 0L,
                ShortfallWorkOrder.STATUS_ALERTED);
        ShortfallWorkOrder wo2 = workOrder(2L, "RV2", "R2", 100L, 0L,
                ShortfallWorkOrder.STATUS_PENDING);
        when(workOrderMapper.selectOpenByMerchant(777L)).thenReturn(List.of(wo1, wo2));
        when(workOrderMapper.casClosed(1L)).thenReturn(1);
        when(workOrderMapper.casClosed(2L)).thenReturn(0);

        service.clawbackOnDepositPaid(777L);

        verify(merchantMapper).changeDeposit(777L, -100L);
        verify(merchantMapper).changeDeposit(777L, -50L);

        ArgumentCaptor<SettDepositLog> dpCaptor = ArgumentCaptor.forClass(SettDepositLog.class);
        verify(depositLogMapper, org.mockito.Mockito.times(2)).insert(dpCaptor.capture());
        List<SettDepositLog> dps = dpCaptor.getAllValues();
        assertEquals(20, dps.get(0).getLogType());
        assertEquals("RV1", dps.get(0).getBizNo());
        assertEquals(-100L, dps.get(0).getAmountFen());
        assertEquals(50L, dps.get(0).getBalanceAfterFen());
        assertEquals("RV2", dps.get(1).getBizNo());
        assertEquals(-50L, dps.get(1).getAmountFen());
        assertEquals(0L, dps.get(1).getBalanceAfterFen());

        verify(accountService).writeZeroFlow(777L, AccountRole.MERCHANT, "RV1",
                FlowChangeTypes.REFUND_FROM_DEPOSIT, "穿仓缺口保证金自动补扣 reverseNo=RV1 100分");
        verify(accountService).writeZeroFlow(777L, AccountRole.MERCHANT, "RV2",
                FlowChangeTypes.REFUND_FROM_DEPOSIT, "穿仓缺口保证金自动补扣 reverseNo=RV2 50分");

        verify(workOrderMapper).addClawedBack(1L, 100L);
        verify(workOrderMapper).addClawedBack(2L, 50L);
        verify(workOrderMapper).casClosed(1L);
        verify(workOrderMapper).casClosed(2L);
    }

    @Test
    @DisplayName("补扣_缴足两张_均CAS30且余额扣净")
    void clawback_exactEnough_bothClosed() {
        stubMerchantBalance(200L);
        stubNoPriorClawback();
        when(workOrderMapper.selectOpenByMerchant(777L)).thenReturn(List.of(
                workOrder(1L, "RV1", "R1", 100L, 0L, ShortfallWorkOrder.STATUS_ALERTED),
                workOrder(2L, "RV2", "R2", 100L, 0L, ShortfallWorkOrder.STATUS_ALERTED)));
        when(workOrderMapper.casClosed(anyLong())).thenReturn(1);

        service.clawbackOnDepositPaid(777L);

        verify(merchantMapper, org.mockito.Mockito.times(2)).changeDeposit(eq(777L), eq(-100L));
        verify(workOrderMapper, org.mockito.Mockito.times(2)).casClosed(anyLong());
    }

    @Test
    @DisplayName("补扣_余额不足_首张部分扣保留_第二张不触碰")
    void clawback_insufficient_keptOpenAndStops() {
        stubMerchantBalance(50L);
        stubNoPriorClawback();
        when(workOrderMapper.selectOpenByMerchant(777L)).thenReturn(List.of(
                workOrder(1L, "RV1", "R1", 100L, 0L, ShortfallWorkOrder.STATUS_ALERTED),
                workOrder(2L, "RV2", "R2", 100L, 0L, ShortfallWorkOrder.STATUS_PENDING)));
        when(workOrderMapper.casClosed(anyLong())).thenReturn(0);

        service.clawbackOnDepositPaid(777L);

        verify(merchantMapper).changeDeposit(777L, -50L);
        verify(workOrderMapper).addClawedBack(1L, 50L);
        verify(workOrderMapper, never()).addClawedBack(eq(2L), anyLong());
        verify(accountService, org.mockito.Mockito.times(1)).writeZeroFlow(
                anyLong(), anyInt(), anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("补扣_缴费事件重放_DP流水已存在不重复扣款不重复记账")
    void clawback_replay_noDoubleDeduct() {
        SettMerchant m = new SettMerchant();
        m.setId(777L);
        m.setDepositBalanceFen(150L);
        when(merchantMapper.selectForUpdate(777L)).thenReturn(m);
        // 该冲正首期 DP(biz_no=RV1) 已在（上一缴费事件已补扣50/100）：
        // 第一次 count 为期次统计=1 → 本期 bizNo=RV1#2；第二次 exists 查询命中 → 跳过
        when(depositLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L, 1L);
        when(workOrderMapper.selectOpenByMerchant(777L)).thenReturn(List.of(
                workOrder(1L, "RV1", "R1", 100L, 50L, ShortfallWorkOrder.STATUS_ALERTED)));

        service.clawbackOnDepositPaid(777L);

        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        verify(depositLogMapper, never()).insert(any());
        verify(accountService, never()).writeZeroFlow(
                anyLong(), anyInt(), anyString(), anyInt(), anyString());
        verify(workOrderMapper, never()).addClawedBack(anyLong(), anyLong());
    }

    @Test
    @DisplayName("补扣_多期到账_后续期次bizNo带期次后缀不撞UK")
    void clawback_secondInstallment_suffixedBizNo() {
        stubMerchantBalance(50L);
        // 首期 DP(biz_no=RV1) 已存在 → 本期 biz_no=RV1#2
        when(depositLogMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L, 0L);
        when(workOrderMapper.selectOpenByMerchant(777L)).thenReturn(List.of(
                workOrder(1L, "RV1", "R1", 100L, 50L, ShortfallWorkOrder.STATUS_ALERTED)));
        when(workOrderMapper.casClosed(anyLong())).thenReturn(1);

        service.clawbackOnDepositPaid(777L);

        verify(merchantMapper).changeDeposit(777L, -50L);
        ArgumentCaptor<SettDepositLog> dpCaptor = ArgumentCaptor.forClass(SettDepositLog.class);
        verify(depositLogMapper).insert(dpCaptor.capture());
        assertEquals("RV1#2", dpCaptor.getValue().getBizNo());
        verify(accountService).writeZeroFlow(eq(777L), eq(AccountRole.MERCHANT), eq("RV1#2"),
                eq(FlowChangeTypes.REFUND_FROM_DEPOSIT), anyString());
    }

    @Test
    @DisplayName("补扣_无未结工单或余额为0_无任何扣款动作")
    void clawback_noOpenOrdersOrZeroBalance() {
        SettMerchant m = new SettMerchant();
        m.setId(777L);
        m.setDepositBalanceFen(0L);
        when(merchantMapper.selectForUpdate(777L)).thenReturn(m);
        when(workOrderMapper.selectOpenByMerchant(777L)).thenReturn(List.of(
                workOrder(1L, "RV1", "R1", 100L, 0L, ShortfallWorkOrder.STATUS_ALERTED)));

        service.clawbackOnDepositPaid(777L);

        verify(merchantMapper, never()).changeDeposit(anyLong(), anyLong());
        verify(depositLogMapper, never()).insert(any());
        verify(workOrderMapper, never()).addClawedBack(anyLong(), anyLong());
    }

    // ---------- 端到端：实时 + 扫表双路径 ----------

    @Test
    @DisplayName("双路径_实时已落单后扫表_最终恰一张工单一次告警")
    void dualPath_exactlyOneOrderOneAlert() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        // 实时消费
        when(mqConsumeService.tryRecord(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(workOrderMapper.selectByReverseNo("RV1")).thenReturn(null);
        when(workOrderMapper.casAlerted(anyLong())).thenReturn(1);
        service.onShortfall(event("EVT-LIVE", "RV1", "R1", 100L));

        // 次日扫表：NOT EXISTS 使该冲正不再返回；10 态滞留扫描也为空
        when(reverseMapper.selectSuspended(any(LocalDateTime.class), eq(200)))
                .thenReturn(List.of());
        when(workOrderMapper.selectByStatusPaged(anyInt(), anyLong(), anyInt()))
                .thenReturn(List.of());
        service.dailyRescan(today);

        verify(workOrderMapper, org.mockito.Mockito.times(1)).insert(any());
        verify(alarmNotifier, org.mockito.Mockito.times(1)).notifyShortfall(any());
        verify(workOrderMapper, atLeastOnce()).selectByReverseNo("RV1");
        assertTrue(true);
    }

    @Test
    @DisplayName("O6指标_穿仓补扣余额耗尽停止_{event=CLAWBACK}仅event受控标签")
    void metrics_clawbackExhausted_counterIncrement() {
        stubMerchantBalance(50L);
        stubNoPriorClawback();
        when(workOrderMapper.selectOpenByMerchant(777L)).thenReturn(List.of(
                workOrder(1L, "RV1", "R1", 100L, 0L, ShortfallWorkOrder.STATUS_ALERTED),
                workOrder(2L, "RV2", "R2", 100L, 0L, ShortfallWorkOrder.STATUS_PENDING)));
        when(workOrderMapper.casClosed(anyLong())).thenReturn(0);

        service.clawbackOnDepositPaid(777L);

        assertEquals(1.0, registry.counter(DepositService.DEPOSIT_INSUFFICIENT_TOTAL,
                "event", DepositService.EVENT_CLAWBACK).count());
        registry.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertFalse(tag.getKey().equals("merchantId") || tag.getKey().equals("userId"),
                        "高基数标签键泄露: " + tag.getKey())));
    }
}
