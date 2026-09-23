package com.shop.settlement.job;

import com.shop.settlement.deposit.service.DepositService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 保证金清退批次：每日 03:00 扫描清退登记满 90 天的商户（design 7.6）。
 * B10 起：是否存在未终结售后/介入单以 aftersale 域 existsOpenDispute 返回为准
 * （settlement 不再以观察期满伪造无纠纷结论）；退还走真实代发 SPI，终态由 RemitQueryJob 确认。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepositRefundJob {

    private final DepositService depositService;

    @Scheduled(cron = "0 0 3 * * ?")
    @SchedulerLock(name = "settle:deposit-refund", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        var refunded = depositService.scanAndRefundResigned(LocalDateTime.now());
        if (!refunded.isEmpty()) {
            log.info("保证金清退批完成，本轮推进代发/关单商户 {} 户: {}", refunded.size(), refunded);
        }
    }
}
