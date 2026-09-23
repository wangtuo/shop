package com.shop.settlement.job;

import com.shop.settlement.statement.service.StatementSettleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 日终结算批（ShedLock 多实例选主）：扫描到期 stage=20 清算单转 stage=30 可提现。
 * 500/批分页，目标单实例 ≤2h。也可由平台端 /admin/reconcile/settle 手动触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailySettleJob {

    private final StatementSettleService statementSettleService;

    @Scheduled(cron = "0 30 2 * * ?")
    @SchedulerLock(name = "settle:daily-settle", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        LocalDate today = LocalDate.now();
        log.info("日终结算批开始 date={}", today);
        statementSettleService.runDailySettle(today);
    }
}
