package com.shop.product.stock.reconcile.service.impl;

import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.OrderStatusDTO;
import com.shop.api.order.enums.OrderStatuses;
import com.shop.api.product.dto.StockDeductCommand;
import com.shop.api.product.dto.StockItemCommand;
import com.shop.api.product.dto.StockReleaseCommand;
import com.shop.api.product.enums.StockLockStatuses;
import com.shop.common.result.Result;
import com.shop.product.stock.entity.ProductStockLog;
import com.shop.product.stock.reconcile.entity.StockReconcileLog;
import com.shop.product.stock.reconcile.mapper.StockReconcileMapper;
import com.shop.product.stock.reconcile.service.StockReconcileService;
import com.shop.product.stock.service.StockService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 普通库存对账实现（P1-1）。
 *
 * <p>判定权威源 = 订单状态（OrderClient.listStatus 批量查询），流水自身状态只用于筛选悬挂单：
 * <ul>
 *   <li>50 已取消 / 70 已关闭 → releaseStock 释放 LOCKED；</li>
 *   <li>20 待发货 / 30 待收货 / 40 已完成 → confirmDeduct 补 confirm（LOCKED→占用），
 *       其中 40 再调 shipOutExisting 出账（占用仓出账）；</li>
 *   <li>10 待付款：未超支付有效期跳过；已超 → releaseStock 释放；</li>
 *   <li>订单查不到（空/404/Feign 异常）→ 不自动释放，落 issue=2 action=0 告警，
 *       查历史留痕发现上一轮已告警则升级 ERROR；</li>
 *   <li>预售 type=2 status=1 且订单 50/70（已过尾款期，presaleFinalStage=true）
 *       → returnPresaleDeposit（流水→5 回补预售池）；预售无流水是合法态，不造流水。</li>
 * </ul>
 *
 * <p>并发安全：释放/confirm 均复用 StockService 内既有条件更新（status 乐观推进），
 * Job 与 MQ 消费者并发处理同一流水时零误操作；本类无类级事务，逐单 try/catch，
 * 单条失败不回滚整批。
 */
@Service
@RequiredArgsConstructor
public class StockReconcileServiceImpl implements StockReconcileService {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileServiceImpl.class);

    /** 单批流水扫描上限 */
    private static final int LOG_BATCH_LIMIT = 200;
    /** OrderClient.listStatus 单次订单号上限（契约 ≤100） */
    private static final int ORDER_BATCH_LIMIT = 100;
    /** 悬挂流水最小年龄（分钟）：避开正常在途/消息窗口 */
    private static final int LOCKED_MIN_AGE_MINUTES = 10;
    /** 告警抑制/升级判定的历史回看窗口（小时） */
    private static final int ALERT_LOOKBACK_HOURS = 24;

    /** issue_type：1 终态订单残留 LOCKED */
    private static final int ISSUE_TERMINAL_LOCKED = 1;
    /** issue_type：2 无有效订单 LOCKED */
    private static final int ISSUE_NO_VALID_ORDER = 2;
    /** issue_type：3 预售尾款违约（定金已扣未回补） */
    private static final int ISSUE_PRESALE_DEPOSIT = 3;

    /** action：0 告警 */
    private static final int ACTION_ALERT = 0;
    /** action：1 自动释放 */
    private static final int ACTION_RELEASE = 1;
    /** action：2 补扣（confirm / 出账 / 预售回补） */
    private static final int ACTION_CONFIRM = 2;

    private final StockReconcileMapper reconcileMapper;
    private final OrderClient orderClient;
    private final StockService stockService;

    @Override
    public void reconcileOnce() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusMinutes(LOCKED_MIN_AGE_MINUTES);

        List<ProductStockLog> lockedLogs = reconcileMapper.selectLockedLogsBefore(cutoff, LOG_BATCH_LIMIT);
        List<ProductStockLog> presaleLogs = reconcileMapper.selectPresaleDeductedBefore(cutoff, LOG_BATCH_LIMIT);
        if (lockedLogs.isEmpty() && presaleLogs.isEmpty()) {
            return;
        }

        Set<String> orderNos = new LinkedHashSet<>();
        Map<String, List<ProductStockLog>> lockedByOrder = groupByOrder(lockedLogs, orderNos);
        Map<String, List<ProductStockLog>> presaleByOrder = groupByOrder(presaleLogs, orderNos);

        Map<String, OrderStatusDTO> statusMap = batchLoadStatus(orderNos);

        int handled = 0;
        for (Map.Entry<String, List<ProductStockLog>> entry : lockedByOrder.entrySet()) {
            String orderNo = entry.getKey();
            try {
                handleLockedOrder(orderNo, entry.getValue(), statusMap.get(orderNo), now);
                handled++;
            } catch (Exception e) {
                // 逐单隔离：单条失败不回滚整批
                log.error("[stock-reconcile] LOCKED 对账失败 orderNo={}", orderNo, e);
                safeAlert(orderNo, entry.getValue(), ISSUE_TERMINAL_LOCKED,
                        "AUTO_HANDLE_FAIL: " + e.getMessage());
            }
        }
        for (Map.Entry<String, List<ProductStockLog>> entry : presaleByOrder.entrySet()) {
            String orderNo = entry.getKey();
            try {
                handlePresaleOrder(orderNo, entry.getValue(), statusMap.get(orderNo), now);
            } catch (Exception e) {
                log.error("[stock-reconcile] 预售定金回补失败 orderNo={}", orderNo, e);
                safeAlert(orderNo, entry.getValue(), ISSUE_PRESALE_DEPOSIT,
                        "AUTO_HANDLE_FAIL: " + e.getMessage());
            }
        }
        if (handled > 0 || !statusMap.isEmpty()) {
            log.info("[stock-reconcile] 对账完成 lockedOrders={} presaleOrders={} statusQueried={}",
                    lockedByOrder.size(), presaleByOrder.size(), statusMap.size());
        }
    }

    // ------------------------------------------------------------------
    // LOCKED 流水对账
    // ------------------------------------------------------------------

    private void handleLockedOrder(String orderNo, List<ProductStockLog> logs,
                                   OrderStatusDTO dto, LocalDateTime now) {
        if (orderNo == null || orderNo.isBlank()) {
            // 无单号的悬挂流水无法核对订单，走告警路径（不自动释放）
            alertMissingOrder(null, logs, now);
            return;
        }
        if (dto == null) {
            alertMissingOrder(orderNo, logs, now);
            return;
        }
        Integer status = dto.getStatus();
        if (status == null) {
            alertMissingOrder(orderNo, logs, now);
            return;
        }
        switch (status) {
            case OrderStatuses.CANCELLED, OrderStatuses.CLOSED -> releaseResidual(orderNo, logs, dto,
                    "订单终态 status=" + status + "，自动释放残留 LOCKED");
            case OrderStatuses.WAIT_SHIP, OrderStatuses.WAIT_RECEIVE, OrderStatuses.COMPLETED -> {
                confirmResidual(orderNo, logs, dto);
                if (status == OrderStatuses.COMPLETED) {
                    // 集成依赖点（TRADE-2 新增）：StockService#shipOutExisting(String)
                    stockService.shipOutExisting(orderNo);
                    log.info("[stock-reconcile] 已完成订单补出账 orderNo={}", orderNo);
                }
            }
            case OrderStatuses.WAIT_PAY -> {
                if (isPayExpired(dto, now)) {
                    releaseResidual(orderNo, logs, dto, "待付款超时未支付，自动释放残留 LOCKED");
                }
                // 未超支付有效期：正常在途，不动
            }
            default -> log.debug("[stock-reconcile] 订单 status={} 售后/中间态，暂不处理 orderNo={}",
                    status, orderNo);
        }
    }

    private void releaseResidual(String orderNo, List<ProductStockLog> logs,
                                 OrderStatusDTO dto, String reason) {
        if (alreadyHandled(orderNo, ISSUE_TERMINAL_LOCKED, ACTION_RELEASE)) {
            log.warn("[stock-reconcile] 同窗口已释放过，跳过重复处置 orderNo={}", orderNo);
            return;
        }
        stockService.releaseStock(StockReleaseCommand.builder()
                .orderNo(orderNo)
                .orderType(dto.getOrderType())
                .items(toItems(logs))
                .build());
        for (ProductStockLog stockLog : logs) {
            writeLog(orderNo, stockLog, ISSUE_TERMINAL_LOCKED, ACTION_RELEASE, reason);
        }
        log.warn("[stock-reconcile] {} orderNo={} skuCount={}", reason, orderNo, logs.size());
    }

    private void confirmResidual(String orderNo, List<ProductStockLog> logs, OrderStatusDTO dto) {
        if (alreadyHandled(orderNo, ISSUE_TERMINAL_LOCKED, ACTION_CONFIRM)) {
            log.warn("[stock-reconcile] 同窗口已补 confirm 过，跳过重复处置 orderNo={}", orderNo);
            return;
        }
        stockService.confirmDeduct(StockDeductCommand.builder()
                .orderNo(orderNo)
                .orderType(dto.getOrderType())
                .items(toItems(logs))
                .build());
        String detail = "订单 status=" + dto.getStatus() + "（已支付）残留 LOCKED，补 confirm";
        for (ProductStockLog stockLog : logs) {
            writeLog(orderNo, stockLog, ISSUE_TERMINAL_LOCKED, ACTION_CONFIRM, detail);
        }
        log.warn("[stock-reconcile] {} orderNo={} skuCount={}", detail, orderNo, logs.size());
    }

    /**
     * 订单查不到 / Feign 失败：不自动释放，落留痕告警；历史窗口内已有一轮告警 → 升级 ERROR。
     */
    private void alertMissingOrder(String orderNo, List<ProductStockLog> logs, LocalDateTime now) {
        long prior = orderNo == null ? 0
                : reconcileMapper.countSince(orderNo, ISSUE_NO_VALID_ORDER, ACTION_ALERT,
                        now.minusHours(ALERT_LOOKBACK_HOURS));
        String detail = prior > 0
                ? "连续两轮以上查无有效订单，升级人工核查（不自动释放）"
                : "查无有效订单/状态查询失败，暂不自动释放";
        for (ProductStockLog stockLog : logs) {
            writeLog(orderNo, stockLog, ISSUE_NO_VALID_ORDER, ACTION_ALERT, detail);
        }
        if (prior > 0) {
            log.error("[stock-reconcile][ALERT_ESCALATED] orderNo={} skuCount={} 残留 LOCKED 流水多轮无订单",
                    orderNo, logs.size());
        } else {
            log.warn("[stock-reconcile][ALERT] orderNo={} skuCount={} 残留 LOCKED 但查无有效订单，仅告警",
                    orderNo, logs.size());
        }
    }

    // ------------------------------------------------------------------
    // 预售尾款违约
    // ------------------------------------------------------------------

    private void handlePresaleOrder(String orderNo, List<ProductStockLog> logs,
                                    OrderStatusDTO dto, LocalDateTime now) {
        if (orderNo == null || dto == null || dto.getStatus() == null) {
            // 预售查不到订单不自动回补，落告警
            if (orderNo != null) {
                safeAlert(orderNo, logs, ISSUE_PRESALE_DEPOSIT, "预售定金回补：订单状态查询失败，暂不处理");
            }
            return;
        }
        int status = dto.getStatus();
        boolean cancelledPastTail = (status == OrderStatuses.CANCELLED || status == OrderStatuses.CLOSED)
                && Boolean.TRUE.equals(dto.getPresaleFinalStage());
        if (!cancelledPastTail) {
            // 预售合法中间态（含无流水场景）：不造流水、不回补
            return;
        }
        if (alreadyHandled(orderNo, ISSUE_PRESALE_DEPOSIT, ACTION_CONFIRM)) {
            return;
        }
        // 集成依赖点（TRADE-2 新增）：StockService#returnPresaleDeposit(String)
        stockService.returnPresaleDeposit(orderNo);
        String detail = "预售订单 status=" + status + " 已过尾款期，定金扣减流水回补预售池（status→5）";
        for (ProductStockLog stockLog : logs) {
            writeLog(orderNo, stockLog, ISSUE_PRESALE_DEPOSIT, ACTION_CONFIRM, detail);
        }
        log.warn("[stock-reconcile] {} orderNo={}", detail, orderNo);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /**
     * 待付款超时判定：OrderStatusDTO 不携带 expire_time，以 gmtCreate + 订单类型支付有效期
     * （与 order PayTimeoutPolicy 同矩阵：普通/换货 30min、秒杀 15min、拼团 24h、预售 3 天）推算。
     */
    private boolean isPayExpired(OrderStatusDTO dto, LocalDateTime now) {
        LocalDateTime gmtCreate = dto.getGmtCreate();
        if (gmtCreate == null) {
            // 无创建时间依据时按在途处理，绝不误释放
            return false;
        }
        return gmtCreate.plusMinutes(payTimeoutMinutes(dto.getOrderType())).isBefore(now);
    }

    private int payTimeoutMinutes(Integer orderType) {
        if (orderType == null) {
            return 30;
        }
        return switch (orderType) {
            case 2 -> 15;   // 秒杀
            case 3 -> 1440; // 拼团
            case 4 -> 4320; // 预售尾款
            default -> 30;  // 普通/换货/未知
        };
    }

    private Map<String, List<ProductStockLog>> groupByOrder(List<ProductStockLog> logs,
                                                            Set<String> orderNos) {
        Map<String, List<ProductStockLog>> grouped = new LinkedHashMap<>();
        for (ProductStockLog stockLog : logs) {
            String orderNo = stockLog.getOrderNo();
            if (orderNo != null && !orderNo.isBlank()) {
                orderNos.add(orderNo);
            }
            grouped.computeIfAbsent(orderNo == null ? "" : orderNo, k -> new ArrayList<>()).add(stockLog);
        }
        return grouped;
    }

    private Map<String, OrderStatusDTO> batchLoadStatus(Set<String> orderNos) {
        Map<String, OrderStatusDTO> result = new LinkedHashMap<>();
        List<String> batch = new ArrayList<>(ORDER_BATCH_LIMIT);
        for (String orderNo : orderNos) {
            batch.add(orderNo);
            if (batch.size() == ORDER_BATCH_LIMIT) {
                queryStatusBatch(batch, result);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            queryStatusBatch(batch, result);
        }
        return result;
    }

    private void queryStatusBatch(List<String> batch, Map<String, OrderStatusDTO> sink) {
        try {
            Result<Map<String, OrderStatusDTO>> result = orderClient.listStatus(batch);
            if (result != null && result.isSuccess() && result.getData() != null) {
                sink.putAll(result.getData());
            } else {
                log.warn("[stock-reconcile] listStatus 非成功响应 size={}，本批按查无订单告警处理", batch.size());
            }
        } catch (Exception e) {
            // Feign 异常（404/熔断/网络）：不自动释放，本批订单全部走告警路径
            log.warn("[stock-reconcile] listStatus 调用异常 size={}，本批按查无订单告警处理: {}",
                    batch.size(), e.getMessage());
        }
    }

    private List<StockItemCommand> toItems(List<ProductStockLog> logs) {
        return logs.stream()
                .map(l -> StockItemCommand.builder()
                        .skuId(l.getSkuId())
                        .qty(l.getQty())
                        .stockType(l.getType())
                        .build())
                .toList();
    }

    private boolean alreadyHandled(String orderNo, int issueType, int action) {
        return reconcileMapper.countSince(orderNo, issueType, action,
                LocalDateTime.now().minusHours(ALERT_LOOKBACK_HOURS)) > 0;
    }

    private void writeLog(String orderNo, ProductStockLog stockLog, int issueType, int action, String detail) {
        StockReconcileLog row = new StockReconcileLog();
        row.setOrderNo(orderNo);
        row.setSkuId(stockLog.getSkuId());
        row.setLogId(stockLog.getId());
        row.setIssueType(issueType);
        row.setAction(action);
        row.setDetail(detail == null || detail.isBlank() ? ""
                : detail.length() > 500 ? detail.substring(0, 500) : detail);
        row.setCreateTime(LocalDateTime.now());
        reconcileMapper.insert(row);
    }

    /** 处置失败后的 best-effort 告警落痕，自身异常不再外抛。 */
    private void safeAlert(String orderNo, List<ProductStockLog> logs, int issueType, String detail) {
        try {
            for (ProductStockLog stockLog : logs) {
                writeLog(orderNo, stockLog, issueType, ACTION_ALERT, detail);
            }
        } catch (Exception ex) {
            log.error("[stock-reconcile] 告警留痕写入失败 orderNo={}", orderNo, ex);
        }
    }
}
