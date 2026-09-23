package com.shop.pay.feature.payment.job;

import com.shop.pay.feature.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 支付中/待支付状态超时扫描（补偿双保险之一，另一保险为 RocketMQ 延时消息）。
 * 多实例由 ShedLock 保证单节点执行；扫描时先主动查渠道，渠道确认未支付才关单。
 */
@Component
@RequiredArgsConstructor
public class PayTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(PayTimeoutJob.class);
    private static final int BATCH_LIMIT = 200;

    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    @SchedulerLock(name = "payTimeoutScan", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void scan() {
        try {
            int handled = paymentService.scanTimeout(BATCH_LIMIT);
            if (handled > 0) {
                log.info("[支付超时扫描] 本轮处理 {} 笔", handled);
            }
        } catch (Exception e) {
            log.error("[支付超时扫描] 执行失败", e);
        }
    }
}
