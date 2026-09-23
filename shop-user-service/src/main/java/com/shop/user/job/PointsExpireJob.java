package com.shop.user.job;

import com.shop.user.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 积分过期清零定时任务：每日扫描过期（获取满 365 天）批次清零并发 POINTS_CHANGED。
 * ShedLock 保证多实例只有一个节点执行；批次状态条件更新 + 过期流水唯一键保证幂等。
 */
@Component
@RequiredArgsConstructor
public class PointsExpireJob {

    private static final Logger log = LoggerFactory.getLogger(PointsExpireJob.class);

    private final AccountService accountService;

    /** 每日 03:30 执行 */
    @Scheduled(cron = "0 30 3 * * ?")
    @SchedulerLock(name = "userPointsExpire", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void expireDuePoints() {
        int count = accountService.expireDuePoints(LocalDateTime.now());
        log.info("积分过期清零任务完成，清零批次数={}", count);
    }
}
