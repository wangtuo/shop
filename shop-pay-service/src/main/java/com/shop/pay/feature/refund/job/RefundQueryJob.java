package com.shop.pay.feature.refund.job;

import com.shop.pay.feature.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款主动查询补偿 Job（B8）：定时扫描 PROCESSING(20) 退款单，逐单调渠道 queryRefund，
 * 结果进入与同步路径/异步回调相同的 {@code RefundConvergeService} 收敛漏斗。
 *
 * <p>ShedLock 保证多实例单节点执行；单条查询失败不影响本轮其他单，下轮继续。
 * 余额退款不查渠道（由同步段二终结）。间隔/宽限语义见 RefundServiceImpl 常量。</p>
 */
@Component
@RequiredArgsConstructor
public class RefundQueryJob {

    private static final Logger log = LoggerFactory.getLogger(RefundQueryJob.class);
    private static final int BATCH_LIMIT = 50;

    private final RefundService refundService;

    @Scheduled(fixedDelayString = "${shop.pay.refund.query-delay-ms:30000}", initialDelay = 30_000L)
    @SchedulerLock(name = "pay:refund-query", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void scan() {
        try {
            int handled = refundService.scanProcessingRefunds(BATCH_LIMIT);
            if (handled > 0) {
                log.info("[退款查询补偿] 本轮查询 {} 笔处理中退款单", handled);
            }
        } catch (Exception e) {
            log.error("[退款查询补偿] 执行失败", e);
        }
    }
}
