package com.shop.framework.id;

import cn.hutool.core.lang.Snowflake;
import jakarta.annotation.PostConstruct;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 全局分布式 ID 生成器（雪花算法）。
 *
 * <p>workerId/datacenterId 在实例启动时通过 Redis 原子自增分配（0~31），
 * 多实例水平扩容不会撞号；Redis 不可用时退化为本机网卡信息推导，保证可用性。
 */
@Component
public class IdGenerator {

    private static final Logger log = LoggerFactory.getLogger(IdGenerator.class);

    private final ObjectProvider<RedissonClient> redissonProvider;
    private Snowflake snowflake;

    public IdGenerator(ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
    }

    @PostConstruct
    public void init() {
        long workerSeed;
        try {
            RedissonClient client = redissonProvider.getIfAvailable();
            if (client != null) {
                RAtomicLong counter = client.getAtomicLong("shop:id:worker-seed");
                workerSeed = counter.incrementAndGet();
            } else {
                workerSeed = fallbackSeed();
            }
        } catch (Exception e) {
            log.warn("Redis 分配 workerId 失败，使用本机降级方案", e);
            workerSeed = fallbackSeed();
        }
        long workerId = workerSeed % 32;
        long dataCenterId = (workerSeed / 32) % 32;
        this.snowflake = new Snowflake(workerId, dataCenterId, true);
        log.info("IdGenerator 初始化完成 workerId={} dataCenterId={}", workerId, dataCenterId);
    }

    private long fallbackSeed() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostAddress();
            return Math.abs(host.hashCode()) % 1024;
        } catch (Exception e) {
            return Thread.currentThread().getId() % 1024;
        }
    }

    public long nextId() {
        return snowflake.nextId();
    }

    public String nextIdString() {
        return Long.toString(nextId());
    }
}
