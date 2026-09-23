package com.shop.pay.feature.recon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.pay.enums.ReconcileDiffTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.tx.TransactionalTemplate;
import com.shop.pay.channel.ChannelBillRecord;
import com.shop.pay.channel.ChannelQueryResult;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.channel.PayChannelClient;
import com.shop.pay.feature.payment.entity.ChannelFlow;
import com.shop.pay.feature.payment.entity.Payment;
import com.shop.pay.feature.payment.mapper.ChannelFlowMapper;
import com.shop.pay.feature.payment.mapper.PaymentMapper;
import com.shop.pay.feature.recon.entity.ReconBatch;
import com.shop.pay.feature.recon.entity.ReconDiff;
import com.shop.pay.feature.recon.enums.ReconStatuses;
import com.shop.pay.feature.recon.mapper.ReconBatchMapper;
import com.shop.pay.feature.recon.mapper.ReconDiffMapper;
import com.shop.pay.feature.recon.service.ReconDiffHandler;
import com.shop.pay.feature.recon.service.ReconcileService;
import com.shop.pay.feature.recon.support.ReconDiffDraft;
import com.shop.pay.feature.recon.support.ReconcileMatcher;
import com.shop.pay.support.PayNoGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T+1 对账服务实现（design 6.5 / P2-4）：
 * 批次准备（短事务）→ 逐差异 REQUIRES_NEW 独立处置（单条失败仅回滚该条）→
 * 批次状态由未处置差异计数推导（短事务 20→30）。批处理入口本身<b>无事务</b>（规约 4）。
 */
@Service
@RequiredArgsConstructor
public class ReconcileServiceImpl implements ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileServiceImpl.class);

    public static final int DEFAULT_MAX_RETRY = 5;

    private final ReconBatchMapper batchMapper;
    private final ReconDiffMapper diffMapper;
    private final PaymentMapper paymentMapper;
    private final ChannelFlowMapper channelFlowMapper;
    private final ReconcileMatcher reconcileMatcher;
    private final ChannelRouter channelRouter;
    private final ChannelSecretProvider channelSecretProvider;
    private final PayNoGenerator payNoGenerator;
    // P2-4：逐差异处置走独立 Bean 的 REQUIRES_NEW 代理，禁止 this 自调用
    private final ReconDiffHandler diffHandler;
    private final TransactionalTemplate txTemplate;

    @Override
    public ReconBatch runReconcile(LocalDate reconDate, String channelCode) {
        // 渠道合法性
        channelSecretProvider.requireKnownChannel(channelCode);
        ReconBatch existed = batchMapper.selectOne(new LambdaQueryWrapper<ReconBatch>()
                .eq(ReconBatch::getReconDate, reconDate)
                .eq(ReconBatch::getChannelCode, channelCode));
        if (existed != null) {
            // 当日该渠道对账幂等
            return existed;
        }

        // 1) 本地成功支付单（按渠道过滤）——事务外只读
        LocalDateTime start = reconDate.atStartOfDay();
        LocalDateTime end = reconDate.plusDays(1).atStartOfDay();
        List<Payment> localPayments = paymentMapper.selectSuccessBetween(start, end);
        Map<String, Payment> localByPayNo = new HashMap<>();
        long localAmount = 0L;
        for (Payment payment : localPayments) {
            List<ChannelFlow> flows = channelFlowMapper.selectByPayNo(payment.getPayNo());
            boolean onChannel = flows.stream().anyMatch(f -> channelCode.equals(f.getChannelCode()));
            if (onChannel) {
                localByPayNo.put(payment.getPayNo(), payment);
                localAmount += nz(payment.getAmountFen());
            }
        }

        // 2) 拉取渠道账单（mock 文件/构造数据）——Feign 必须在事务外（规约 3）
        PayChannelClient client = channelRouter.route(channelCode);
        List<ChannelBillRecord> bills = client.downloadBill(channelCode, reconDate);
        long channelAmount = bills.stream().filter(ChannelBillRecord::isSuccess)
                .mapToLong(b -> nz(b.getAmountFen())).sum();

        // 3) 比对出三类差错（纯内存）
        List<ReconDiffDraft> drafts = reconcileMatcher.match(localByPayNo, bills);

        // 4) 短事务：批次 10 落库 → 差异幂等落库 + 汇总计数 → 批次 20
        final int localCount = localByPayNo.size();
        final long localAmountFinal = localAmount;
        final long channelAmountFinal = channelAmount;
        ReconBatch batch = txTemplate.execute(() -> prepareBatch(reconDate, channelCode,
                drafts, bills, localCount, localAmountFinal, channelAmountFinal));

        // 5) 逐差异 REQUIRES_NEW 处置（单条失败不回滚整批、不污染兄弟行）
        processBatchDiffs(batch.getBatchNo());

        // 6) 短事务：批次状态由差异计数推导（无 10/20 差异才 20→30）
        finalizeBatch(batch.getId(), batch.getBatchNo());
        return batchMapper.selectById(batch.getId());
    }

    private ReconBatch prepareBatch(LocalDate reconDate, String channelCode, List<ReconDiffDraft> drafts,
                                    List<ChannelBillRecord> bills, int localCount, long localAmount,
                                    long channelAmount) {
        ReconBatch batch = new ReconBatch();
        batch.setBatchNo(payNoGenerator.batchNo());
        batch.setReconDate(reconDate);
        batch.setChannelCode(channelCode);
        batch.setStatus(ReconStatuses.Batch.FETCHING);
        batch.setChannelCount(0);
        batch.setChannelAmountFen(0L);
        batch.setLocalCount(0);
        batch.setLocalAmountFen(0L);
        batch.setLongCount(0);
        batch.setShortCount(0);
        batch.setMismatchCount(0);
        batchMapper.insert(batch);

        int longCount = 0;
        int shortCount = 0;
        int mismatchCount = 0;
        for (ReconDiffDraft draft : drafts) {
            ReconDiff diff = new ReconDiff();
            diff.setBatchNo(batch.getBatchNo());
            diff.setReconDate(reconDate);
            diff.setChannelCode(channelCode);
            diff.setDiffType(draft.getDiffType());
            diff.setPayNo(draft.getPayNo());
            diff.setOrderNo(draft.getOrderNo());
            diff.setChannelTxnNo(draft.getChannelTxnNo());
            diff.setLocalAmountFen(draft.getLocalAmountFen());
            diff.setChannelAmountFen(draft.getChannelAmountFen());
            diff.setStatus(ReconStatuses.Diff.PENDING);
            diff.setRetryCount(0);
            diff.setMaxRetry(DEFAULT_MAX_RETRY);
            if (diffMapper.insertIgnore(diff) > 0) {
                if (draft.getDiffType() == ReconcileDiffTypes.LONG.getCode()) {
                    longCount++;
                } else if (draft.getDiffType() == ReconcileDiffTypes.SHORT.getCode()) {
                    shortCount++;
                } else {
                    mismatchCount++;
                }
            }
        }

        batch.setChannelCount((int) bills.stream().filter(ChannelBillRecord::isSuccess).count());
        batch.setChannelAmountFen(channelAmount);
        batch.setLocalCount(localCount);
        batch.setLocalAmountFen(localAmount);
        batch.setLongCount(longCount);
        batch.setShortCount(shortCount);
        batch.setMismatchCount(mismatchCount);
        batch.setStatus(ReconStatuses.Batch.COMPARED);
        batchMapper.updateById(batch);
        return batch;
    }

    @Override
    public int retryPendingDiffs(int limit) {
        List<ReconDiff> pending = diffMapper.selectPending(limit);
        Set<String> affectedBatches = new LinkedHashSet<>();
        int handled = 0;
        for (ReconDiff diff : pending) {
            if (diff.getBatchNo() != null) {
                affectedBatches.add(diff.getBatchNo());
            }
            if (processOne(diff)) {
                handled++;
            }
        }
        // 重试收敛后按差异计数回推批次状态（残留全部清零则 20→30）
        for (String batchNo : affectedBatches) {
            ReconBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<ReconBatch>()
                    .eq(ReconBatch::getBatchNo, batchNo));
            if (batch != null && batch.getStatus() != null
                    && batch.getStatus() == ReconStatuses.Batch.COMPARED) {
                finalizeBatch(batch.getId(), batchNo);
            }
        }
        return handled;
    }

    @Override
    public ReconDiff handleDiff(Long diffId) {
        ReconDiff diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "对账差错单不存在: " + diffId);
        }
        // 人工单笔触发：渠道查询在事务外，异常直接抛给入口（不做静默留痕）
        ChannelQueryResult shortQuery = diff.getDiffType() == ReconcileDiffTypes.SHORT.getCode()
                ? queryShortChannel(diff) : null;
        diffHandler.handleOne(diff, shortQuery);
        return diffMapper.selectById(diffId);
    }

    private void processBatchDiffs(String batchNo) {
        List<ReconDiff> diffs = diffMapper.selectByBatch(batchNo);
        for (ReconDiff diff : diffs) {
            processOne(diff);
        }
    }

    /**
     * 逐差异处置：渠道短款查询在事务外；处置走独立 REQUIRES_NEW 事务。
     * 任一异常仅记录该差异（retry_count+1，可重试），循环继续，不回滚兄弟行。
     *
     * @return true 该条本轮成功/跳过；false 处置失败已留痕待重试
     */
    private boolean processOne(ReconDiff diff) {
        ChannelQueryResult shortQuery = null;
        if (diff.getDiffType() != null
                && diff.getDiffType() == ReconcileDiffTypes.SHORT.getCode()) {
            try {
                shortQuery = queryShortChannel(diff);
            } catch (Exception e) {
                log.warn("短款渠道查询失败 diffId={} err={}", diff.getId(), e.getMessage());
                safeMarkFailure(diff, e);
                return false;
            }
        }
        try {
            diffHandler.handleOne(diff, shortQuery);
            return true;
        } catch (Exception e) {
            // 该差异 REQUIRES_NEW 事务已回滚；留痕在另一个独立新事务提交，兄弟行不受影响
            log.warn("对账差错处置失败 diffId={} type={} err={}",
                    diff.getId(), diff.getDiffType(), e.getMessage());
            safeMarkFailure(diff, e);
            return false;
        }
    }

    private void safeMarkFailure(ReconDiff diff, Exception e) {
        try {
            diffHandler.markFailure(diff, e);
        } catch (Exception ex) {
            // 留痕失败（如 DB 故障）不再向上抛：保证批循环不中断，等待下轮重试 Job
            log.error("对账差异失败留痕异常 diffId={}", diff.getId(), ex);
        }
    }

    /** 短款主动查询渠道（事务外，mock 默认）：取该差异对应渠道的待核查流水号。 */
    private ChannelQueryResult queryShortChannel(ReconDiff diff) {
        if (diff.getPayNo() == null) {
            return null;
        }
        List<ChannelFlow> flows = channelFlowMapper.selectByPayNo(diff.getPayNo());
        return flows.stream()
                .filter(f -> diff.getChannelCode().equals(f.getChannelCode()))
                .filter(f -> f.getChannelOrderNo() != null)
                .findFirst()
                .map(f -> channelRouter.route(f.getChannelCode())
                        .query(f.getChannelCode(), f.getChannelOrderNo()))
                .orElse(null);
    }

    /**
     * 批次状态推导（P2-4，禁止发明新状态码）：
     * 无 10/20 差异（全部 30/40 终态）→ 30 完成并落 finishTime；存在未处置差异 → 保持 20。
     */
    private void finalizeBatch(Long batchId, String batchNo) {
        txTemplate.executeWithoutResult(() -> {
            int pendingCount = diffMapper.countPendingByBatch(batchNo);
            if (pendingCount == 0) {
                batchMapper.markFinished(batchId, LocalDateTime.now());
            }
            // pendingCount > 0：批次保持 20，等待 ReconRetryJob 收敛，绝不在有残留时置 30
        });
    }

    @Override
    public List<ReconDiff> listDiffs(Integer status) {
        LambdaQueryWrapper<ReconDiff> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(ReconDiff::getStatus, status);
        }
        wrapper.orderByDesc(ReconDiff::getId);
        return diffMapper.selectList(wrapper);
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }
}
