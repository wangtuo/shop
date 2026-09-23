package com.shop.order.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.order.order.entity.Order;
import com.shop.order.order.mapper.OrderMapper;
import com.shop.order.order.service.OrderOperateService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动确认收货扫描：status=30 且 auto_confirm_deadline 已过（发货后 10 天）。
 * 与自动收货延时消息互为双保险；30→40 为条件更新，幂等。
 */
@Component
@RequiredArgsConstructor
public class AutoConfirmScanJob {

    private static final Logger log = LoggerFactory.getLogger(AutoConfirmScanJob.class);
    private static final int BATCH_LIMIT = 200;

    private final OrderMapper orderMapper;
    private final OrderOperateService operateService;

    /** 每小时第 5 分钟执行 */
    @Scheduled(cron = "0 5 * * * ?")
    @SchedulerLock(name = "orderAutoConfirmScan", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void scanAutoConfirm() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> due = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getOrderNo)
                .eq(Order::getStatus, 30)
                .lt(Order::getAutoConfirmDeadline, now)
                .last("LIMIT " + BATCH_LIMIT));
        for (Order order : due) {
            try {
                operateService.autoConfirm(order.getOrderNo());
            } catch (Exception e) {
                log.error("自动确认收货失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        if (!due.isEmpty()) {
            log.info("自动确认收货扫描完成，处理 {} 笔", due.size());
        }
    }
}
