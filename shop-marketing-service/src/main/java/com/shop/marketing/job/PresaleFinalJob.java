package com.shop.marketing.job;

import com.shop.marketing.activity.service.PresaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 预售尾款 3 天窗口超时扫描：未付尾款自动取消（定金不退），与 MQ 延时消息双保险。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresaleFinalJob {

    private final PresaleService presaleService;

    @Scheduled(cron = "0 */10 * * * ?")
    @SchedulerLock(name = "marketing:presaleFinal", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void timeout() {
        int n = presaleService.timeoutScan();
        if (n > 0) {
            log.info("预售尾款超时扫描完成，{} 单取消（定金不退）", n);
        }
    }
}
