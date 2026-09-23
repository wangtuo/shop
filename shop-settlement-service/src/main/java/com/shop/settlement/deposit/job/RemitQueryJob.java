package com.shop.settlement.deposit.job;

import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.withdraw.service.WithdrawService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 打款终态查询补偿（GAP_PLAN_FUNDS B10）：每 60s 扫描「渠道已受理、终态未确认」的
 * 提现单（t_sett_withdraw status=20 + channel_remit_no 非空）与保证金退还单
 * （t_sett_deposit_log log_type=40 status=10 + channel_remit_no 非空），主动查渠道推进终态。
 *
 * <p>资金铁律：只有渠道查询返回成功且业务库 CAS 获胜，才允许提现解冻出款/保证金余额置零；
 * 查询失败由既有 markFailed / 退还 status=30 + DEPOSIT_ALERT 闭环。ShedLock 保证集群单节点执行，
 * 单条 touch CAS 是多节点/多轮的二道防线。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemitQueryJob {

    private final WithdrawService withdrawService;
    private final DepositService depositService;

    @Scheduled(fixedDelayString = "${shop.settle.remit.query-interval-ms:60000}")
    @SchedulerLock(name = "settle:remit-query", lockAtMostFor = "PT2M", lockAtLeastFor = "PT0S")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        int withdrawals = 0;
        int refunds = 0;
        try {
            withdrawals = withdrawService.queryPendingRemits(now);
        } catch (Exception e) {
            log.error("提现打款终态查询批异常", e);
        }
        try {
            refunds = depositService.queryPendingRefunds(now);
        } catch (Exception e) {
            log.error("保证金退还终态查询批异常", e);
        }
        if (withdrawals > 0 || refunds > 0) {
            log.info("打款终态查询批完成，提现确认 {} 笔，退还确认 {} 笔", withdrawals, refunds);
        }
    }
}
