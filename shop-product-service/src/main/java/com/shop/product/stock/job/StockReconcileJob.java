package com.shop.product.stock.job;

import com.shop.product.stock.reconcile.service.StockReconcileService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 普通库存对账定时任务（GAP_PLAN_TRADE P1-1）。
 *
 * <p>固定延迟 5 分钟；ShedLock 保证多实例下单实例执行（锁 TTL 4 分钟，最少持有 30 秒
 * 防止节点抖动重复执行；LockProvider 由 shop-framework ShedLockConfig 统一提供）。
 */
@Component
@RequiredArgsConstructor
public class StockReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileJob.class);

    private final StockReconcileService stockReconcileService;

    /** 启动 1 分钟后首轮，之后固定延迟 5 分钟。 */
    @Scheduled(fixedDelay = 5 * 60 * 1000L, initialDelay = 60 * 1000L)
    @SchedulerLock(name = "productStockReconcile", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void scan() {
        try {
            stockReconcileService.reconcileOnce();
        } catch (Exception e) {
            log.error("[stock-reconcile] 对账任务执行异常", e);
        }
    }
}
