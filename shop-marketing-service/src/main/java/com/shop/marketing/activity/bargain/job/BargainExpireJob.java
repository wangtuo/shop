package com.shop.marketing.activity.bargain.job;

import com.shop.marketing.activity.bargain.service.BargainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 砍价超时扫描：到期未成交记录 status 0→2（条件更新，与成交 CAS 互斥）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BargainExpireJob {

    private final BargainService bargainService;

    @Scheduled(cron = "0 */5 * * * ?")
    @SchedulerLock(name = "marketing:bargainExpire", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void expire() {
        int n = bargainService.expireScan();
        if (n > 0) {
            log.info("砍价超时扫描完成，{} 条记录已失效", n);
        }
    }
}
