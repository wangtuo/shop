package com.shop.order.job;

import com.shop.order.invoice.service.InvoiceService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发票开具扫描：已完成（40/70）且登记了待开发票（电子普通/增值税专票）的订单，
 * 模拟开具 PDF（生成发票号与下载地址）。全额退款时由 REFUND_SUCCESS 消费触发红冲。
 */
@Component
@RequiredArgsConstructor
public class InvoiceIssueJob {

    private static final Logger log = LoggerFactory.getLogger(InvoiceIssueJob.class);

    private final InvoiceService invoiceService;

    /** 每日凌晨 04:10 执行 */
    @Scheduled(cron = "0 10 4 * * ?")
    @SchedulerLock(name = "orderInvoiceIssue", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void issueInvoices() {
        int count = invoiceService.issueDueInvoices();
        if (count > 0) {
            log.info("发票开具扫描完成，开具 {} 张", count);
        }
    }
}
