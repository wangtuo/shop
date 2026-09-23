package com.shop.marketing.job;

import com.shop.marketing.coupon.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 券过期作废扫描：超过有效期的未使用券 → 已过期（锁定中券不扫，待订单释放后自然进入下次扫描）。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpireJob {

    private final CouponService couponService;

    @Scheduled(cron = "0 */10 * * * ?")
    @SchedulerLock(name = "marketing:couponExpire", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void expire() {
        int n = couponService.expireScanned();
        if (n > 0) {
            log.info("券过期扫描完成，作废 {} 张", n);
        }
    }
}
