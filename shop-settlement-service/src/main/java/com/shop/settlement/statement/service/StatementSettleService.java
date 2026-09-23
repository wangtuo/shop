package com.shop.settlement.statement.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shop.common.result.PageResult;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.service.ClearingService;
import com.shop.settlement.statement.entity.SettStatement;
import com.shop.settlement.statement.mapper.StatementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日终结算批处理（design 7.3，目标单实例 ≤2h）：
 * 分页（500/批，id 游标）扫描 due_date &lt;= 今日 且 stage=20 的清算单，
 * <b>逐单</b>调用 {@link SettleClearingExecutor#settleOne}（独立 Spring Bean 的 public
 * 事务方法，避免同类自调用导致 @Transactional 失效，P1-4）：
 * 待结算→可提现、清算单 stage=30、写账户流水（幂等）、登记 CLEARING_SETTLE outbox 事件。
 *
 * <p>单条失败只回滚该笔（仍是 stage=20，下轮自动重跑自愈），不滚整批；失败笔数与
 * clearingNo/orderNo/原因以 ERROR 日志留痕并在返回值中给出，可追踪。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementSettleService {

    /** 单页 500 条，与日终 ≤2h 目标匹配 */
    public static final int PAGE_SIZE = 500;

    private final ClearingService clearingService;
    private final SettleClearingExecutor settleClearingExecutor;
    private final StatementMapper statementMapper;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettlePageResult {
        /** 本页实际结算清算单笔数 */
        private int settledCount;
        /** 本页扫描笔数 */
        private int scannedCount;
        /** 本页失败笔数（已隔离，不影响其他笔） */
        private int failedCount;
        /** 本页最后一条清算单 ID（下一页游标，空页为 0） */
        private long lastId;
        /** 按商户汇总的本次转可提现金额（返回值/事件归并用） */
        @Builder.Default
        private List<MerchantSettle> merchantSettles = new ArrayList<>();
        /** 失败明细（clearingNo/orderNo/原因），可追踪 */
        @Builder.Default
        private List<FailedItem> failures = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MerchantSettle {
        private String statementNo;
        private Long merchantId;
        private Long amountFen;
    }

    /** 单笔失败明细（单条事务隔离 + 可追踪）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailedItem {
        private Long clearingId;
        private String clearingNo;
        private String orderNo;
        private Long merchantId;
        private String reason;
    }

    /**
     * 处理一页（500 条）。本方法<b>自身不开事务</b>：扫描为只读，逐单调用执行器的独立事务。
     */
    public SettlePageResult settlePage(long lastId, LocalDate today) {
        List<SettClearing> page = clearingService.selectDuePage(lastId, today, PAGE_SIZE);
        Map<String, MerchantSettle> merchantAgg = new LinkedHashMap<>();
        List<FailedItem> failures = new ArrayList<>();
        int settled = 0;

        for (SettClearing clearing : page) {
            try {
                SettleClearingExecutor.SettleItem item = settleClearingExecutor.settleOne(clearing, today);
                if (!item.settled()) {
                    continue;
                }
                settled++;
                MerchantSettle agg = merchantAgg.computeIfAbsent(item.statementNo(),
                        k -> MerchantSettle.builder()
                                .statementNo(item.statementNo())
                                .merchantId(item.merchantId())
                                .amountFen(0L)
                                .build());
                agg.setAmountFen(agg.getAmountFen() + item.amountFen());
            } catch (Exception e) {
                // 单条失败隔离：执行器独立事务已回滚该笔，stage 仍为 20，下轮重跑；不影响同批其他单
                String reason = e.toString();
                log.error("日终结算单笔失败已隔离，下轮自动重跑 clearingNo={} orderNo={} merchantId={} err={}",
                        clearing.getClearingNo(), clearing.getOrderNo(), clearing.getMerchantId(), reason, e);
                failures.add(FailedItem.builder()
                        .clearingId(clearing.getId())
                        .clearingNo(clearing.getClearingNo())
                        .orderNo(clearing.getOrderNo())
                        .merchantId(clearing.getMerchantId())
                        .reason(reason)
                        .build());
            }
        }

        return SettlePageResult.builder()
                .settledCount(settled)
                .failedCount(failures.size())
                .scannedCount(page.size())
                .lastId(page.isEmpty() ? 0L : page.get(page.size() - 1).getId())
                .merchantSettles(new ArrayList<>(merchantAgg.values()))
                .failures(failures)
                .build();
    }

    /**
     * 日终全量执行：游标翻页直到取空；逐单独立事务，单条失败不滚整批。
     *
     * @return 全部页汇总的商户结算金额（失败明细见日志，失败单维持 stage=20 下轮重跑）
     */
    public List<MerchantSettle> runDailySettle(LocalDate today) {
        List<MerchantSettle> all = new ArrayList<>();
        long lastId = 0L;
        int totalSettled = 0;
        int totalFailed = 0;
        while (true) {
            SettlePageResult result = settlePage(lastId, today);
            all.addAll(result.getMerchantSettles());
            totalSettled += result.getSettledCount();
            totalFailed += result.getFailedCount();
            if (result.getScannedCount() < PAGE_SIZE) {
                break;
            }
            lastId = result.getLastId();
        }
        log.info("日终结算批处理完成 date={} 结算成功={} 失败隔离={} 商户汇总={}",
                today, totalSettled, totalFailed, all.size());
        return all;
    }

    /** 商户端分页查询结算单。 */
    public PageResult<SettStatement> pageMerchant(long merchantId, int pageNum, int pageSize) {
        Page<SettStatement> page = new Page<>(pageNum, pageSize);
        Page<SettStatement> result = statementMapper.selectPage(page, new LambdaQueryWrapper<SettStatement>()
                .eq(SettStatement::getMerchantId, merchantId)
                .orderByDesc(SettStatement::getId));
        return PageResult.of(pageNum, pageSize, result.getTotal(), result.getRecords());
    }
}
