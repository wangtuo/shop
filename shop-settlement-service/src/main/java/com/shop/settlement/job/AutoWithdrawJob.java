package com.shop.settlement.job;

import com.shop.settlement.withdraw.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 自动提现批次：每日 08:00 按商户配置（每日/每周）自动生成提现申请单。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoWithdrawJob {

    private final WithdrawService withdrawService;

    @Scheduled(cron = "0 0 8 * * ?")
    @SchedulerLock(name = "settle:auto-withdraw", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        int n = withdrawService.runAutoWithdraw(LocalDate.now());
        if (n > 0) {
            log.info("自动提现批完成，生成申请单 {} 笔", n);
        }
    }
}
