package com.shop.product.stock.job;

import com.shop.product.stock.reconcile.service.StockReconcileService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * P1-1 调度入口：单实例锁参数与 fixedDelay=5min；scan 异常不外抛。
 */
@ExtendWith(MockitoExtension.class)
class StockReconcileJobTest {

    @Mock
    private StockReconcileService stockReconcileService;

    @InjectMocks
    private StockReconcileJob job;

    @Test
    void scan_委托对账服务一次() {
        job.scan();
        verify(stockReconcileService, times(1)).reconcileOnce();
    }

    @Test
    void scan_服务抛异常_任务不外抛() {
        org.mockito.Mockito.doThrow(new RuntimeException("模拟对账异常"))
                .when(stockReconcileService).reconcileOnce();
        job.scan(); // 不抛异常即通过
    }

    @Test
    void 调度与ShedLock注解参数符合约定() throws Exception {
        Method scan = StockReconcileJob.class.getMethod("scan");

        Scheduled scheduled = scan.getAnnotation(Scheduled.class);
        assertEquals(5 * 60 * 1000L, scheduled.fixedDelay());
        assertTrue(scheduled.initialDelay() > 0);

        SchedulerLock lock = scan.getAnnotation(SchedulerLock.class);
        assertEquals("productStockReconcile", lock.name());
        assertEquals("PT4M", lock.lockAtMostFor());
        assertEquals("PT30S", lock.lockAtLeastFor());
    }
}
