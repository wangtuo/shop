package com.shop.aftersale.job;

import com.shop.aftersale.aftersale.entity.AftersaleDispute;
import com.shop.aftersale.aftersale.entity.AftersaleInsurance;
import com.shop.aftersale.aftersale.mapper.AftersaleDisputeMapper;
import com.shop.aftersale.aftersale.mapper.AftersaleInsuranceMapper;
import com.shop.aftersale.aftersale.service.AftersaleTimeoutService;
import com.shop.aftersale.support.AftersaleDelayTopics;
import com.shop.aftersale.support.AftersaleTimeoutMessage;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 运费险 72h 理赔扫描 + 平台介入 3 天举证截止扫描。
 */
@Component
@RequiredArgsConstructor
public class AftersaleClaimJob {

    private static final int LIMIT = 200;

    private final AftersaleInsuranceMapper insuranceMapper;
    private final AftersaleDisputeMapper disputeMapper;
    private final AftersaleTimeoutService timeoutService;

    @Scheduled(fixedDelay = 60_000L)
    @SchedulerLock(name = "aftersaleInsuranceClaim", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void insuranceClaim() {
        List<AftersaleInsurance> list = insuranceMapper.selectClaimDue(LocalDateTime.now(), LIMIT);
        for (AftersaleInsurance ins : list) {
            timeoutService.dispatchTracked(AftersaleTimeoutMessage.forInsurance(
                    ins.getId(), ins.getAftersaleNo(), AftersaleDelayTopics.KIND_INSURANCE));
        }
    }

    @Scheduled(fixedDelay = 60_000L)
    @SchedulerLock(name = "aftersaleEvidenceTimeout", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void evidenceTimeout() {
        List<AftersaleDispute> list = disputeMapper.selectEvidenceDue(LocalDateTime.now(), LIMIT);
        for (AftersaleDispute d : list) {
            timeoutService.dispatchTracked(AftersaleTimeoutMessage.forAftersale(
                    d.getAftersaleNo(), AftersaleDelayTopics.KIND_EVIDENCE));
        }
    }
}
