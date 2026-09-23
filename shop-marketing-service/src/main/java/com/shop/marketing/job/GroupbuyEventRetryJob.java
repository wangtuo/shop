package com.shop.marketing.job;

import com.shop.marketing.activity.groupbuy.service.GroupbuyEventAcceptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 拼团事件补偿（B1）：每 2 分钟扫描 t_groupbuy_event_todo 中 handle_status=0/2 的待办重新通知订单域。
 *
 * <p>与 MQ 消费重试双保险：status=2 按 retry_count×60s 指数退避；retry_count 达 10 停止，
 * 受理服务内打 P0 告警人工介入。单批最多 {@link GroupbuyEventAcceptService#RETRY_BATCH_LIMIT} 条限频。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupbuyEventRetryJob {

    private final GroupbuyEventAcceptService acceptService;

    @Scheduled(cron = "0 */2 * * * ?")
    @SchedulerLock(name = "marketing:groupbuyEventRetry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void retry() {
        int n = acceptService.retryDue(GroupbuyEventAcceptService.RETRY_BATCH_LIMIT);
        if (n > 0) {
            log.info("拼团事件补偿完成，{} 条待办通知成功", n);
        }
    }
}
