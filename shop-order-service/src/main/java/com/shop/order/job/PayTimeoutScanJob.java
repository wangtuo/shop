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
 * 支付超时扫描：status=10 且 expire_time 已过的订单系统取消（cancelType=2 超时）。
 * 与支付超时延时消息互为双保险；取消本身为条件更新（仅 10→50），天然幂等。
 */
@Component
@RequiredArgsConstructor
public class PayTimeoutScanJob {

    private static final Logger log = LoggerFactory.getLogger(PayTimeoutScanJob.class);
    private static final int BATCH_LIMIT = 200;

    private final OrderMapper orderMapper;
    private final OrderOperateService operateService;

    /** 每分钟扫描一次 */
    @Scheduled(cron = "0 * * * * ?")
    @SchedulerLock(name = "orderPayTimeoutScan", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    public void scanTimeoutOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<Order> expired = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .select(Order::getOrderNo)
                .eq(Order::getStatus, 10)
                .lt(Order::getExpireTime, now)
                .last("LIMIT " + BATCH_LIMIT));
        for (Order order : expired) {
            try {
                operateService.timeoutCancel(order.getOrderNo());
            } catch (Exception e) {
                log.error("支付超时取消失败 orderNo={}", order.getOrderNo(), e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("支付超时扫描完成，处理 {} 笔", expired.size());
        }
    }
}
