package com.shop.pay.feature.recon.job;

import com.shop.pay.feature.recon.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账差错补偿重试：每 30 分钟扫描待处理差错（短款主动查询等），超最大次数转人工。
 */
@Component
@RequiredArgsConstructor
public class ReconRetryJob {

    private static final Logger log = LoggerFactory.getLogger(ReconRetryJob.class);
    private static final int BATCH_LIMIT = 100;

    private final ReconcileService reconcileService;

    @Scheduled(fixedDelay = 30 * 60_000L, initialDelay = 5 * 60_000L)
    @SchedulerLock(name = "payReconRetry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void retryPending() {
        try {
            int handled = reconcileService.retryPendingDiffs(BATCH_LIMIT);
            if (handled > 0) {
                log.info("[对账差错补偿] 本轮处理 {} 笔", handled);
            }
        } catch (Exception e) {
            log.error("[对账差错补偿] 执行失败", e);
        }
    }
}
