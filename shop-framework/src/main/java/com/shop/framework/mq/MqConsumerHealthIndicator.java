package com.shop.framework.mq;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * MQ 消费者健康指示器：所有消费者注册完成才 UP；注册途中/失败为 DOWN 并展示
 * 已注册数量与最近失败原因。
 *
 * <p>注意：消费者在后台线程注册，HTTP 服务不等待 MQ——K8s liveness 不会因 MQ 抖动
 * 反复杀容器；readiness 探针据此在消费者未就绪时暂不接流量（若部署将本指示器纳入
 * readiness 组），MQ 恢复后自动转 UP，无需重启 Pod。
 */
@Component("mqConsumers")
public class MqConsumerHealthIndicator implements HealthIndicator {

    private final MqConsumerRegistrar registrar;

    public MqConsumerHealthIndicator(MqConsumerRegistrar registrar) {
        this.registrar = registrar;
    }

    @Override
    public Health health() {
        int registered = registrar.registeredCount();
        int total = registrar.totalCount();
        if (registrar.allRegistered()) {
            return Health.up()
                    .withDetail("consumers", total)
                    .build();
        }
        // R4-20：ApplicationReady 后的启动宽限是有意为之（等 JIT/服务发现预热，避免 broker
        // 积压风暴打在冷下游上触发熔断），展示 OUT_OF_SERVICE 而非 DOWN，语义为「尚未接管
        // 消费」而非故障，滚动发布期间流量/消费都由同组其他实例承担。
        if (registrar.isInStartupGrace()) {
            return Health.outOfService()
                    .withDetail("phase", "startup-grace")
                    .withDetail("consumers", registered + "/" + total)
                    .build();
        }
        Health.Builder builder = Health.down();
        if (total > 0) {
            builder.withDetail("registered", registered)
                    .withDetail("total", total);
        }
        String failure = registrar.getLastFailure();
        if (failure != null) {
            builder.withDetail("lastFailure", failure);
        }
        return builder.build();
    }
}
