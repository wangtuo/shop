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
 * 售后期结束扫描：status=40 且 aftersale_deadline 已过（确认收货后 15 天）。
 * 存在进行中售后的订单由服务内部跳过；与售后期延时消息互为双保险。
 */
@Component
@RequiredArgsConstructor
public class AftersaleWindowScanJob {

    private static final Logger log = LoggerFactory.getLogger(AftersaleWindowScanJob.class);
    private static final int BATCH_LIMIT = 200;

    private final OrderMapper orderMapper;
    private final OrderOperateService operateService;

    /** 每小时第 15 分钟执行 */
    @Scheduled(cron = "0 15 * * * ?")
    @SchedulerLock(name = "orderAftersaleWindowScan", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void scanAftersaleWindow() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> due = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getOrderNo)
                .eq(Order::getStatus, 40)
                .lt(Order::getAftersaleDeadline, now)
                .last("LIMIT " + BATCH_LIMIT));
        for (Order order : due) {
            try {
                operateService.closeAftersaleWindow(order.getOrderNo());
            } catch (Exception e) {
                log.error("售后期结束关闭失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        if (!due.isEmpty()) {
            log.info("售后期结束扫描完成，处理 {} 笔", due.size());
        }
    }
}
