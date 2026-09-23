package com.shop.user.job;

import com.shop.user.account.service.GrowthService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 年末成长值折算任务：每年 12 月 31 日按 80% 折算成长值，保底当前等级不降级。
 *
 * <p>双重幂等：ShedLock 保证多实例单节点执行；t_user_growth_discount(user_id, year)
 * 唯一记录保证本年对每个用户仅执行一次（节点宕机漂移后重跑安全）。
 * 折算年份可由 shop.user.growth.discount-year 配置覆盖（0=当前年），便于单元测试。
 */
@Component
@RequiredArgsConstructor
public class GrowthDiscountJob {

    private static final Logger log = LoggerFactory.getLogger(GrowthDiscountJob.class);

    private final GrowthService growthService;

    /** 0 表示取当前自然年；测试可注入指定年份 */
    @Value("${shop.user.growth.discount-year:0}")
    private int configuredYear;

    /** 每年 12 月 31 日 02:00 执行 */
    @Scheduled(cron = "0 0 2 31 12 ?")
    @SchedulerLock(name = "userGrowthYearEndDiscount", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void yearEndDiscount() {
        int year = configuredYear > 0 ? configuredYear : LocalDate.now().getYear();
        int affected = growthService.yearEndDiscount(year);
        log.info("年末成长值折算任务完成 year={} 折算用户数={}", year, affected);
    }
}
