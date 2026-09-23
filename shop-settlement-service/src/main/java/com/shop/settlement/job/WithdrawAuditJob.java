package com.shop.settlement.job;

import com.shop.settlement.withdraw.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 提现审核批次：申请单 10→20（模拟风控自动过审），ShedLock 选主。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawAuditJob {

    private final WithdrawService withdrawService;

    @Scheduled(cron = "0 */10 * * * ?")
    @SchedulerLock(name = "settle:withdraw-audit", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void run() {
        int n = withdrawService.auditBatch();
        if (n > 0) {
            log.info("提现审核批完成，流转 {} 笔", n);
        }
    }
}
