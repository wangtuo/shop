package com.shop.framework.metrics;

import io.micrometer.core.instrument.Tags;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 框架/业务全部 {@code @Scheduled} 任务心跳指标（O6）。
 *
 * <p>采用 AOP 包 {@code @Scheduled} 方法（与 ShedLock 自身的 @SchedulerLock 切面同机制：
 * 被切 Bean 自动生成代理，调度器持代理调用，advice 生效），不改造 ShedLock/调度器配置：</p>
 * <ul>
 *   <li>{@code shop_scheduler_last_run_seconds{job}}：Gauge，采样时实时求值
 *       「now - 最近一次执行结束时间」，任务卡住/停止调度时该值持续上涨，告警可直接覆盖；</li>
 *   <li>{@code shop_scheduler_outcome_total{job,result=success|error}}：执行结果计数，
 *       异常计数后原样 rethrow，不吞异常、不改变 ShedLock 语义。</li>
 * </ul>
 * job 标签为 类简称.方法名（受控低基数，调度任务数量有界），禁止拼参数。
 */
@Aspect
@Component
public class SchedulerMetricsAspect {

    private final BizMetrics bizMetrics;
    /** job -> 最近一次执行结束的毫秒时间戳；computeIfAbsent 时顺带注册 Gauge。 */
    private final ConcurrentHashMap<String, AtomicLong> lastRunMillis = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public SchedulerMetricsAspect(BizMetrics bizMetrics) {
        this.bizMetrics = bizMetrics == null ? new BizMetrics(null) : bizMetrics;
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        String job = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        try {
            Object result = pjp.proceed();
            bizMetrics.schedulerOutcome(job, "success");
            return result;
        } catch (Throwable t) {
            bizMetrics.schedulerOutcome(job, "error");
            throw t;
        } finally {
            touch(job).set(System.currentTimeMillis());
        }
    }

    private AtomicLong touch(String job) {
        return lastRunMillis.computeIfAbsent(job, k -> {
            AtomicLong lastRun = new AtomicLong(System.currentTimeMillis());
            // Gauge 持有 lastRun 强引用（map），采样时实时计算年龄秒数。
            bizMetrics.gauge(BizMetrics.SCHEDULER_LAST_RUN_SECONDS, Tags.of("job", k),
                    lastRun, v -> Math.max(0L, (System.currentTimeMillis() - v.get()) / 1000.0));
            return lastRun;
        });
    }
}
