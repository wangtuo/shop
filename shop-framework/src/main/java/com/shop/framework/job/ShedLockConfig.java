package com.shop.framework.job;

import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 高可用定时任务：多实例部署时同一任务在同一时刻只由一个节点执行；
 * 节点宕机后锁到期自动漂移到其他节点，避免单点与重复执行。
 *
 * <p>环境隔离（C15 / Z7）：锁键前缀 {@code shop:scheduler:{shop.env}:lock:}，
 * 不同环境（local/kind/prod）共用同一 Redis 时互不抢锁；shop.env 缺失取 dev-local。
 * RedisLockProvider 最终键格式为 {@code keyPrefix:environment:lockName}，
 * 故这里传入 keyPrefix="shop:scheduler"、environment="{env}:lock"。</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    public static final String KEY_PREFIX = "shop:scheduler";
    public static final String DEFAULT_ENV = "dev-local";
    public static final String ENV_PROPERTY = "shop.env";
    public static final String LOCK_SEGMENT = "lock";

    @Bean
    public RedisLockProvider shedLockProvider(RedisConnectionFactory connectionFactory,
                                              Environment environment) {
        String env = environment.getProperty(ENV_PROPERTY, DEFAULT_ENV);
        return new RedisLockProvider(connectionFactory, env + ":" + LOCK_SEGMENT, KEY_PREFIX);
    }
}
