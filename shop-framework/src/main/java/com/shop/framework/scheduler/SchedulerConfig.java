package com.shop.framework.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 调度器基线（C15 / Z6）：覆盖 Spring Boot 默认单线程 TaskScheduler，
 * 避免一个 @Scheduled 任务阻塞（如慢 SQL/长轮询）拖死全部定时任务。
 *
 * <p>池大小键 {@code shop.scheduler.pool-size}，默认 2；prod 建议按服务 @Scheduled 数量设置
 * （order/pay/settlement=4）。线程名前缀 {@code shop-sched-}，便于线程 dump 识别。
 * {@code @EnableScheduling} 仍由 {@code @ShopService} 开启，本类只提供 Bean。</p>
 */
@Configuration
public class SchedulerConfig {

    /** Bean 名固定为 taskScheduler：覆盖 TaskSchedulingAutoConfiguration 的单线程默认 Bean。 */
    public static final String TASK_SCHEDULER_BEAN_NAME = "taskScheduler";
    public static final String POOL_SIZE_PROPERTY = "shop.scheduler.pool-size";
    public static final int DEFAULT_POOL_SIZE = 2;
    public static final String THREAD_NAME_PREFIX = "shop-sched-";

    @Bean(name = TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler taskScheduler(Environment environment) {
        int poolSize = environment.getProperty(POOL_SIZE_PROPERTY, Integer.class, DEFAULT_POOL_SIZE);
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
