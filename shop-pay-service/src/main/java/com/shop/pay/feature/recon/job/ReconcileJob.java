package com.shop.pay.feature.recon.job;

import com.shop.api.pay.enums.PayMethods;
import com.shop.pay.channel.ChannelLimits;
import com.shop.pay.feature.recon.service.ReconcileService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * T+1 日终对账任务（design 6.5）：每日 02:17 逐渠道分页拉取昨日账单比对。
 */
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ReconcileJob.class);

    private final ReconcileService reconcileService;

    @Scheduled(cron = "0 17 2 * * ?")
    @SchedulerLock(name = "payReconDaily", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void dailyReconcile() {
        LocalDate reconDate = LocalDate.now().minusDays(1);
        for (String channelCode : allMockChannels()) {
            try {
                reconcileService.runReconcile(reconDate, channelCode);
            } catch (Exception e) {
                log.error("[T+1对账] 渠道对账失败 date={} channel={}", reconDate, channelCode, e);
            }
        }
    }

    private List<String> allMockChannels() {
        List<String> codes = new ArrayList<>();
        for (PayMethods method : PayMethods.values()) {
            String code = ChannelLimits.channelCode(method);
            if (!ChannelLimits.isBalance(code) && !codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }
}
