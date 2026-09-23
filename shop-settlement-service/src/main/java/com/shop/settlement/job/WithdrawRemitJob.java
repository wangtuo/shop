package com.shop.settlement.job;

import com.shop.settlement.withdraw.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 提现打款提交批次：工作日 9~22 点每小时扫描申请日早于今日的审核中（20）且未受理单，
 * 事务外提交渠道代发（T+1）。B10 起本批次只做<b>提交受理</b>（落 channel_remit_no，状态保持 20），
 * 终态成功/失败由 RemitQueryJob 查询渠道后 CAS 推进。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawRemitJob {

    private final WithdrawService withdrawService;

    @Scheduled(cron = "0 0 9-22 * * ?")
    @SchedulerLock(name = "settle:withdraw-remit", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        int n = withdrawService.remitBatch(LocalDate.now());
        if (n > 0) {
            log.info("提现代发提交批完成，受理 {} 笔（终态以查询补偿为准）", n);
        }
    }
}
