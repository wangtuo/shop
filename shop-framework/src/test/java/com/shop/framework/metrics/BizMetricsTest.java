package com.shop.framework.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BizMetrics 用 SimpleMeterRegistry 断言计数器递增、标签与空注册表空转。
 */
class BizMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final BizMetrics metrics = new BizMetrics(registry);

    @Test
    void countersIncrementWithExpectedTags() {
        metrics.mqPublish("order-topic", "OrderCreated");
        metrics.mqPublish("order-topic", "OrderCreated");
        metrics.mqPublishFailed("order-topic", "OrderCreated");
        metrics.mqConsume("order-topic", "order-group", "OrderCreated", "ok");
        metrics.mqDeadLetter("pay-topic", "pay-group", "RefundFailed", "terminal");
        metrics.mqConsumeFailed("pay-topic", "pay-group", "RefundFailed", "retryable");
        metrics.ratelimitHit("shop-order-service", "createOrder");
        metrics.redisFailure("idempotent");
        metrics.redisFailure("lock");
        metrics.bizException(10003);
        metrics.schedulerOutcome("OutboxRelayJob.relay", "success");

        assertEquals(2.0, registry.find(BizMetrics.MQ_PUBLISH_TOTAL)
                .tags("topic", "order-topic", "event", "OrderCreated").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.MQ_PUBLISH_FAILED_TOTAL)
                .tags("topic", "order-topic", "event", "OrderCreated").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.MQ_CONSUME_TOTAL)
                .tags("topic", "order-topic", "group", "order-group",
                        "event", "OrderCreated", "reason", "ok").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.MQ_DEAD_LETTER_TOTAL)
                .tags("topic", "pay-topic", "group", "pay-group",
                        "event", "RefundFailed", "reason", "terminal").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.MQ_CONSUME_FAILED_TOTAL)
                .tags("reason", "retryable").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.RATELIMIT_HIT_TOTAL)
                .tags("service", "shop-order-service", "resource", "createOrder").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.REDIS_FAILURE_TOTAL)
                .tags("op", "idempotent").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.REDIS_FAILURE_TOTAL)
                .tags("op", "lock").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.BIZ_EXCEPTION_TOTAL)
                .tags("code", "10003").counter().count());
        assertEquals(1.0, registry.find(BizMetrics.SCHEDULER_OUTCOME_TOTAL)
                .tags("job", "OutboxRelayJob.relay", "result", "success").counter().count());
    }

    @Test
    void timerIsRecorded() {
        metrics.mqPublishTimer("order-topic", 1_000_000L);
        metrics.outboxRelay("success", 2_000_000L);
        assertEquals(1, registry.find(BizMetrics.MQ_PUBLISH_SECONDS).tags("topic", "order-topic").timers().size());
        assertEquals(1, registry.find(BizMetrics.OUTBOX_RELAY_SECONDS).tags("result", "success").timers().size());
    }

    @Test
    void gaugeHolderReflectsUpdatesAndEvaluatorGaugeWorks() {
        AtomicLong holder = metrics.gauge(BizMetrics.OUTBOX_PENDING, "lane", "fast");
        holder.set(7L);
        assertEquals(7.0, registry.find(BizMetrics.OUTBOX_PENDING).tags("lane", "fast").gauge().value());

        AtomicLong clock = new AtomicLong(1000L);
        metrics.gauge(BizMetrics.SCHEDULER_LAST_RUN_SECONDS,
                io.micrometer.core.instrument.Tags.of("job", "DemoJob.run"),
                clock, v -> 5.0);
        assertTrue(registry.find(BizMetrics.SCHEDULER_LAST_RUN_SECONDS)
                .tags("job", "DemoJob.run").gauge().value() >= 0.0);
    }

    @Test
    void nullRegistryIsSafe() {
        BizMetrics empty = new BizMetrics(null);
        assertDoesNotThrow(() -> {
            empty.mqPublish("t", "e");
            empty.mqPublishFailed("t", "e");
            empty.mqConsumeFailed("t", "g", "e", "r");
            empty.ratelimitHit("s", "r");
            empty.bizException(1);
            empty.outboxRelay("success", 1L);
            empty.gauge(BizMetrics.OUTBOX_PENDING, "lane", "fast").set(1L);
        });
    }
}
