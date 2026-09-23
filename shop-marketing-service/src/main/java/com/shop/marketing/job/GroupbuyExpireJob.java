package com.shop.marketing.job;

import com.shop.marketing.activity.service.GroupbuyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 拼团 24h 超时扫描：未满团标记失败并发失败事件（驱动自动退款），与成团链路条件更新互斥。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupbuyExpireJob {

    private final GroupbuyService groupbuyService;

    @Scheduled(cron = "0 */5 * * * ?")
    @SchedulerLock(name = "marketing:groupbuyExpire", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void expire() {
        int n = groupbuyService.expireGroups();
        if (n > 0) {
            log.info("拼团超时扫描完成，{} 个团失败待退款", n);
        }
    }
}
