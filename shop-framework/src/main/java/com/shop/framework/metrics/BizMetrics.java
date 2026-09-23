package com.shop.framework.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务/框架自定义 Micrometer 指标统一入口（O6/C57）。
 *
 * <p>指标命名冻结于 GAP_PLAN_OBSERVABILITY O6 命名表；高基数纪律：
 * 标签禁止 orderNo/userId/skuId/merchantId/URL 原值——topic/group/event/prefix/job
 * 均为受控常量/方法名，基数有界。</p>
 *
 * <p>无 {@link MeterRegistry} 环境（单测）下全部方法空转安全；Gauge 持有者（{@link AtomicLong}）
 * 始终返回，采样代码无需判空，只是没有注册表时不导出。</p>
 */
@Component
public class BizMetrics {

    // ---- MQ（生产/消费） ----
    public static final String MQ_PUBLISH_TOTAL = "shop_mq_publish_total";
    public static final String MQ_PUBLISH_FAILED_TOTAL = "shop_mq_publish_failed_total";
    public static final String MQ_PUBLISH_SECONDS = "shop_mq_publish_seconds";
    public static final String MQ_CONSUME_TOTAL = "shop_mq_consume_total";
    public static final String MQ_CONSUME_FAILED_TOTAL = "shop_mq_consume_failed_total";
    public static final String MQ_DEAD_LETTER_TOTAL = "shop_mq_dead_letter_total";
    /** 消费 lag：无 broker 管理端 API 时不产生假数据；环境有 rocketmq-exporter 时以其为准。 */
    public static final String MQ_CONSUME_LAG = "shop_mq_consume_lag";

    // ---- Outbox ----
    public static final String OUTBOX_PENDING = "shop_outbox_pending";
    public static final String OUTBOX_OLDEST_AGE_SECONDS = "shop_outbox_oldest_age_seconds";
    public static final String OUTBOX_RELAY_SECONDS = "shop_outbox_relay_seconds";

    // ---- 限流 / Redis / 业务异常 / 调度心跳 / Druid ----
    public static final String RATELIMIT_HIT_TOTAL = "shop_ratelimit_hit_total";
    public static final String REDIS_FAILURE_TOTAL = "shop_redis_failure_total";
    public static final String BIZ_EXCEPTION_TOTAL = "shop_biz_exception_total";
    public static final String SCHEDULER_LAST_RUN_SECONDS = "shop_scheduler_last_run_seconds";
    public static final String SCHEDULER_OUTCOME_TOTAL = "shop_scheduler_outcome_total";
    public static final String DRUID_ACTIVE = "shop_druid_active";
    public static final String DRUID_IDLE = "shop_druid_idle";
    public static final String DRUID_WAIT = "shop_druid_wait";
    public static final String DRUID_MAX = "shop_druid_max";

    private final MeterRegistry registry;

    @Autowired(required = false)
    public BizMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 无注册表便捷构造（单测/手工 new 场景）。 */
    public static AtomicLong holder() {
        return new AtomicLong();
    }

    // ---- MQ ----

    public void mqPublish(String topic, String event) {
        if (registry != null) {
            registry.counter(MQ_PUBLISH_TOTAL, "topic", nz(topic), "event", nz(event)).increment();
        }
    }

    public void mqPublishFailed(String topic, String event) {
        if (registry != null) {
            registry.counter(MQ_PUBLISH_FAILED_TOTAL, "topic", nz(topic), "event", nz(event)).increment();
        }
    }

    public void mqPublishTimer(String topic, long elapsedNanos) {
        if (registry != null) {
            Timer.builder(MQ_PUBLISH_SECONDS)
                    .tags("topic", nz(topic))
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void mqConsume(String topic, String group, String event, String reason) {
        if (registry != null) {
            registry.counter(MQ_CONSUME_TOTAL,
                    "topic", nz(topic), "group", nz(group), "event", nz(event), "reason", nz(reason))
                    .increment();
        }
    }

    public void mqConsumeFailed(String topic, String group, String event, String reason) {
        if (registry != null) {
            registry.counter(MQ_CONSUME_FAILED_TOTAL,
                    "topic", nz(topic), "group", nz(group), "event", nz(event), "reason", nz(reason))
                    .increment();
        }
    }

    public void mqDeadLetter(String topic, String group, String event, String reason) {
        if (registry != null) {
            registry.counter(MQ_DEAD_LETTER_TOTAL,
                    "topic", nz(topic), "group", nz(group), "event", nz(event), "reason", nz(reason))
                    .increment();
        }
    }

    // ---- Outbox ----

    public void outboxRelay(String result, long elapsedNanos) {
        if (registry != null) {
            Timer.builder(OUTBOX_RELAY_SECONDS)
                    .tags("result", nz(result))
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 注册一个 Gauge 并返回其数值持有者；采样方定期 set。持有者由采样方强引用持有，
     * 避免被 GC 后 Gauge 消失。无注册表时仍返回持有者（空转）。
     */
    public AtomicLong gauge(String name, String... tagKeyValue) {
        AtomicLong value = new AtomicLong();
        if (registry != null) {
            registry.gauge(name, Tags.of(tagKeyValue), value, AtomicLong::doubleValue);
        }
        return value;
    }

    /**
     * 注册求值型 Gauge（采样时实时计算，如「距上次运行秒数」）。{code strongRef} 必须由
     * 调用方强引用持有；无注册表时空转。
     */
    public <T> void gauge(String name, Tags tags, T strongRef,
                          java.util.function.ToDoubleFunction<T> evaluator) {
        if (registry != null) {
            registry.gauge(name, tags, strongRef, evaluator);
        }
    }

    // ---- 限流 / Redis / 异常 / 调度 ----

    public void ratelimitHit(String service, String resource) {
        if (registry != null) {
            registry.counter(RATELIMIT_HIT_TOTAL, "service", nz(service), "resource", nz(resource))
                    .increment();
        }
    }

    public void redisFailure(String op) {
        if (registry != null) {
            registry.counter(REDIS_FAILURE_TOTAL, "op", nz(op)).increment();
        }
    }

    public void bizException(int code) {
        if (registry != null) {
            registry.counter(BIZ_EXCEPTION_TOTAL, "code", String.valueOf(code)).increment();
        }
    }

    public void schedulerOutcome(String job, String result) {
        if (registry != null) {
            registry.counter(SCHEDULER_OUTCOME_TOTAL, "job", nz(job), "result", nz(result)).increment();
        }
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
