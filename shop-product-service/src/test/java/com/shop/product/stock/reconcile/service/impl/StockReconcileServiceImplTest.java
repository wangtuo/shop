package com.shop.product.stock.reconcile.service.impl;

import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderStatusDTO;
import com.shop.api.product.dto.StockDeductCommand;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.common.result.Result;
import com.shop.product.stock.entity.ProductStockLog;
import com.shop.product.stock.reconcile.entity.StockReconcileLog;
import com.shop.product.stock.reconcile.mapper.StockReconcileMapper;
import com.shop.product.stock.service.StockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-1 普通库存对账：终态释放、已支付补 confirm/出账、在途不动、查无订单告警两轮升级、
 * 预售两形态、逐单失败不滚批。
 *
 * <p>注：shipOutExisting / returnPresaleDeposit 由 TRADE-2 同波新增到 StockService，
 * 方法名按总控约定依赖；未合入前本测试与实现存在已知集成编译点。
 */
@ExtendWith(MockitoExtension.class)
class StockReconcileServiceImplTest {

    @Mock
    private StockReconcileMapper reconcileMapper;
    @Mock
    private OrderClient orderClient;
    @Mock
    private StockService stockService;

    @InjectMocks
    private StockReconcileServiceImpl reconcileService;

    private static final String ORDER_A = "260917010001000001";
    private static final String ORDER_B = "260917010001000002";

    private ProductStockLog lockedLog(String orderNo, long logId, long skuId, int type, int qty) {
        ProductStockLog l = new ProductStockLog();
        l.setId(logId);
        l.setOrderNo(orderNo);
        l.setSkuId(skuId);
        l.setSpuId(skuId + 1000);
        l.setType(type);
        l.setQty(qty);
        l.setStatus(0);
        l.setCreateTime(LocalDateTime.now().minusMinutes(30));
        return l;
    }

    private OrderStatusDTO statusDto(int status, int orderType, Boolean finalStage, LocalDateTime gmtCreate) {
        return OrderStatusDTO.builder()
                .status(status)
                .orderType(orderType)
                .presaleFinalStage(finalStage)
                .gmtCreate(gmtCreate)
                .build();
    }

    @BeforeEach
    void setUp() {
        // 默认无预售流水、无历史留痕（部分用例提前返回不查留痕，用 lenient 避免严格桩报错）
        when(reconcileMapper.selectPresaleDeductedBefore(any(), anyInt())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(reconcileMapper.countSince(anyString(), anyInt(), anyInt(), any()))
                .thenReturn(0L);
    }

    private void stubLocked(List<ProductStockLog> logs) {
        when(reconcileMapper.selectLockedLogsBefore(any(), anyInt())).thenReturn(logs);
    }

    private void stubStatus(Map<String, OrderStatusDTO> map) {
        when(orderClient.listStatus(anyList())).thenReturn(Result.success(map));
    }

    /** ① 终态取消订单残留 LOCKED → 释放且只处理一次（含第二轮抑制）。 */
    @Test
    void 终态取消残留LOCKED_释放且只处理一次() {
        ProductStockLog log = lockedLog(ORDER_A, 11L, 100L, 1, 2);
        stubLocked(List.of(log));
        stubStatus(Map.of(ORDER_A, statusDto(50, 1, null, LocalDateTime.now().minusMinutes(30))));

        reconcileService.reconcileOnce();

        ArgumentCaptor<StockReleaseCommand> cmdCap = ArgumentCaptor.forClass(StockReleaseCommand.class);
        verify(stockService).releaseStock(cmdCap.capture());
        StockReleaseCommand cmd = cmdCap.getValue();
        assertEquals(ORDER_A, cmd.getOrderNo());
        assertEquals(1, cmd.getItems().size());
        assertEquals(100L, cmd.getItems().get(0).getSkuId());
        assertEquals(2, cmd.getItems().get(0).getQty());
        assertEquals(1, cmd.getItems().get(0).getStockType());

        ArgumentCaptor<StockReconcileLog> rowCap = ArgumentCaptor.forClass(StockReconcileLog.class);
        verify(reconcileMapper).insert(rowCap.capture());
        assertEquals(1, rowCap.getValue().getIssueType());
        assertEquals(1, rowCap.getValue().getAction());

        // 第二轮：24h 窗口内已有 action=1 留痕 → 抑制，不重复释放
        when(reconcileMapper.countSince(eq(ORDER_A), eq(1), eq(1), any())).thenReturn(1L);
        reconcileService.reconcileOnce();
        verify(stockService, times(1)).releaseStock(any());
    }

    /** ② 已支付残留：30 待收货补 confirm；40 已完成补 confirm + 出账（仅 40 出账）。 */
    @Test
    void 已支付残留_补confirm_完成再出账() {
        ProductStockLog log30 = lockedLog(ORDER_A, 21L, 101L, 1, 1);
        ProductStockLog log40 = lockedLog(ORDER_B, 22L, 102L, 1, 3);
        stubLocked(List.of(log30, log40));
        stubStatus(Map.of(
                ORDER_A, statusDto(30, 1, null, LocalDateTime.now().minusHours(2)),
                ORDER_B, statusDto(40, 1, null, LocalDateTime.now().minusHours(1))));

        reconcileService.reconcileOnce();

        ArgumentCaptor<StockDeductCommand> confirmCap = ArgumentCaptor.forClass(StockDeductCommand.class);
        verify(stockService, times(2)).confirmDeduct(confirmCap.capture());
        List<String> confirmedOrders = confirmCap.getAllValues().stream().map(StockDeductCommand::getOrderNo).toList();
        assertTrue(confirmedOrders.contains(ORDER_A));
        assertTrue(confirmedOrders.contains(ORDER_B));

        // 出账只针对已完成订单
        verify(stockService, times(1)).shipOutExisting(ORDER_B);
        verify(stockService, never()).shipOutExisting(ORDER_A);

        verify(reconcileMapper, times(2)).insert(any(StockReconcileLog.class));
    }

    /** ③ 在途未超时不动；10 已超支付有效期（gmtCreate+30min）才释放。 */
    @Test
    void 在途待付款未超时不动_已超时释放() {
        ProductStockLog fresh = lockedLog(ORDER_A, 31L, 103L, 1, 1);
        fresh.setCreateTime(LocalDateTime.now().minusMinutes(5));
        ProductStockLog expired = lockedLog(ORDER_B, 32L, 104L, 1, 1);
        expired.setCreateTime(LocalDateTime.now().minusMinutes(60));
        stubLocked(List.of(fresh, expired));
        stubStatus(Map.of(
                ORDER_A, statusDto(10, 1, null, LocalDateTime.now().minusMinutes(5)),
                ORDER_B, statusDto(10, 1, null, LocalDateTime.now().minusMinutes(60))));

        reconcileService.reconcileOnce();

        verify(stockService, times(1)).releaseStock(any());
        verify(stockService, never()).confirmDeduct(any());
    }

    /** ④ 订单查不到：不释放只告警；连续两轮（历史已有 1 条告警）升级留痕。 */
    @Test
    void 订单查不到_只告警不释放_连续两轮升级() {
        ProductStockLog log = lockedLog(ORDER_A, 41L, 105L, 1, 1);
        stubLocked(List.of(log));
        when(orderClient.listStatus(anyList())).thenReturn(Result.success(Map.of()));

        // 第一轮：无历史告警
        reconcileService.reconcileOnce();
        verify(stockService, never()).releaseStock(any());
        ArgumentCaptor<StockReconcileLog> rowCap = ArgumentCaptor.forClass(StockReconcileLog.class);
        verify(reconcileMapper).insert(rowCap.capture());
        assertEquals(2, rowCap.getValue().getIssueType());
        assertEquals(0, rowCap.getValue().getAction());
        assertFalse(rowCap.getValue().getDetail().contains("升级"));

        // 第二轮：历史窗口内已有 1 条 action=0 → 升级
        when(reconcileMapper.countSince(eq(ORDER_A), eq(2), eq(0), any())).thenReturn(1L);
        reconcileService.reconcileOnce();
        verify(reconcileMapper, times(2)).insert(rowCap.capture());
        StockReconcileLog escalated = rowCap.getValue();
        assertEquals(0, escalated.getAction());
        assertTrue(escalated.getDetail().contains("升级"));
        verify(stockService, never()).releaseStock(any());
    }

    /** Feign 异常同样不释放、走告警。 */
    @Test
    void listStatus异常_不释放只告警() {
        ProductStockLog log = lockedLog(ORDER_A, 42L, 106L, 1, 1);
        stubLocked(List.of(log));
        when(orderClient.listStatus(anyList())).thenThrow(new RuntimeException("feign 500"));

        reconcileService.reconcileOnce();

        verify(stockService, never()).releaseStock(any());
        verify(reconcileMapper).insert(any(StockReconcileLog.class));
    }

    /** ⑤a 预售定金流水 type=2 status=1，订单已取消且超尾款期 → returnPresaleDeposit 一次。 */
    @Test
    void 预售尾款违约_定金流水回补一次() {
        ProductStockLog presale = lockedLog(ORDER_A, 51L, 107L, 2, 1);
        presale.setStatus(1);
        when(reconcileMapper.selectLockedLogsBefore(any(), anyInt())).thenReturn(List.of());
        when(reconcileMapper.selectPresaleDeductedBefore(any(), anyInt())).thenReturn(List.of(presale));
        stubStatus(Map.of(ORDER_A, statusDto(50, 4, Boolean.TRUE, LocalDateTime.now().minusDays(4))));

        reconcileService.reconcileOnce();

        verify(stockService, times(1)).returnPresaleDeposit(ORDER_A);
        ArgumentCaptor<StockReconcileLog> rowCap = ArgumentCaptor.forClass(StockReconcileLog.class);
        verify(reconcileMapper).insert(rowCap.capture());
        assertEquals(3, rowCap.getValue().getIssueType());
        assertEquals(2, rowCap.getValue().getAction());
    }

    /** ⑤b 预售无 LOCKED/扣减流水是合法态：不造流水、不回补。 */
    @Test
    void 预售无流水_合法态不造单不回补() {
        when(reconcileMapper.selectLockedLogsBefore(any(), anyInt())).thenReturn(List.of());
        when(reconcileMapper.selectPresaleDeductedBefore(any(), anyInt())).thenReturn(List.of());

        reconcileService.reconcileOnce();

        verify(stockService, never()).returnPresaleDeposit(anyString());
        verify(reconcileMapper, never()).insert(any());
    }

    /** 预售订单未取消（仍在尾款期）不回补。 */
    @Test
    void 预售订单未取消_不回补() {
        ProductStockLog presale = lockedLog(ORDER_A, 52L, 108L, 2, 1);
        presale.setStatus(1);
        when(reconcileMapper.selectLockedLogsBefore(any(), anyInt())).thenReturn(List.of());
        when(reconcileMapper.selectPresaleDeductedBefore(any(), anyInt())).thenReturn(List.of(presale));
        stubStatus(Map.of(ORDER_A, statusDto(20, 4, Boolean.FALSE, LocalDateTime.now().minusHours(1))));

        reconcileService.reconcileOnce();

        verify(stockService, never()).returnPresaleDeposit(anyString());
        verify(reconcileMapper, never()).insert(any());
    }

    /** ⑦ 逐单 try/catch：A 释放抛错不影响 B，A 补告警留痕、B 正常释放。 */
    @Test
    void 单条处置失败_不回滚整批() {
        ProductStockLog logA = lockedLog(ORDER_A, 71L, 109L, 1, 1);
        ProductStockLog logB = lockedLog(ORDER_B, 72L, 110L, 1, 1);
        stubLocked(List.of(logA, logB));
        stubStatus(Map.of(
                ORDER_A, statusDto(50, 1, null, LocalDateTime.now().minusMinutes(30)),
                ORDER_B, statusDto(70, 1, null, LocalDateTime.now().minusMinutes(30))));
        org.mockito.Mockito.doThrow(new RuntimeException("模拟释放失败"))
                .when(stockService).releaseStock(org.mockito.ArgumentMatchers.argThat(
                        c -> ORDER_A.equals(c.getOrderNo())));

        reconcileService.reconcileOnce();

        ArgumentCaptor<StockReleaseCommand> cmdCap = ArgumentCaptor.forClass(StockReleaseCommand.class);
        verify(stockService, times(2)).releaseStock(cmdCap.capture());
        List<String> touched = cmdCap.getAllValues().stream().map(StockReleaseCommand::getOrderNo).toList();
        assertTrue(touched.contains(ORDER_A));
        assertTrue(touched.contains(ORDER_B));

        ArgumentCaptor<StockReconcileLog> rowCap = ArgumentCaptor.forClass(StockReconcileLog.class);
        verify(reconcileMapper, times(2)).insert(rowCap.capture());
        Map<String, Integer> actionsByOrder = Map.of(
                rowCap.getAllValues().get(0).getOrderNo(), rowCap.getAllValues().get(0).getAction(),
                rowCap.getAllValues().get(1).getOrderNo(), rowCap.getAllValues().get(1).getAction());
        // 一单一告警(0)、一单一释放(1)
        assertTrue(actionsByOrder.containsValue(0));
        assertTrue(actionsByOrder.containsValue(1));
    }
}
