package com.shop.settlement.clearing.job;

import com.shop.settlement.clearing.service.ShortfallWorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 穿仓缺口扫表兜底任务（P0-1）：每日 02:00 起半小时兜底窗，
 * 针对 outbox status=2 挂起或 relay 死信导致 REFUND_SHORTFALL 从未投递的冲正。
 * ShedLock 多实例选主；只扫当日 0 点前的冲正，不与实时消费竞争。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortfallResidualScanJob {

    private final ShortfallWorkOrderService shortfallWorkOrderService;

    @Scheduled(cron = "0 0/30 2 * * ?")
    @SchedulerLock(name = "settle:shortfall-rescan", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void run() {
        LocalDate today = LocalDate.now();
        log.info("穿仓缺口扫表兜底任务触发 date={}", today);
        shortfallWorkOrderService.dailyRescan(today);
    }
}
