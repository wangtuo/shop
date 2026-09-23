package com.shop.framework.mq;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * MQ / outbox 平台指标（C15）。所有计数器在无 MeterRegistry 环境（单测）下空转安全。
 *
 * <ul>
 *   <li>{@code shop_mq_consume_dead_total{topic,group}}：终态丢弃点
 *       （不可恢复业务错误 ACK、反序列化毒丸、关闭 eventId 兜底后的无标识消息）；</li>
 *   <li>{@code shop_mq_consume_retry_total{topic,group}}：可恢复失败、交 broker 重试；</li>
 *   <li>{@code shop_outbox_suspended_total}：outbox 耗尽自动重放次数的挂起死信增量；</li>
 *   <li>{@code shop_mq_topic_no_subscriber{topic}}：确认无任何订阅组的孤儿 topic
 *             （broker 查询能力不足时不产生假数据，见 OrphanTopicInspector）。</li>
 * </ul>
 */
@org.springframework.stereotype.Component
public class MqMetrics {

    public static final String CONSUME_DEAD = "shop_mq_consume_dead_total";
    public static final String CONSUME_RETRY = "shop_mq_consume_retry_total";
    public static final String OUTBOX_SUSPENDED = "shop_outbox_suspended_total";
    public static final String TOPIC_NO_SUBSCRIBER = "shop_mq_topic_no_subscriber";

    private final MeterRegistry registry;

    /** 服务运行态由 Spring 注入 Prometheus registry；无 registry（极简单测）时传 null 空转。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public MqMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void consumeDead(String topic, String group) {
        if (registry != null) {
            registry.counter(CONSUME_DEAD, "topic", topic, "group", group).increment();
        }
    }

    public void consumeRetry(String topic, String group) {
        if (registry != null) {
            registry.counter(CONSUME_RETRY, "topic", topic, "group", group).increment();
        }
    }

    public void outboxSuspended(long delta) {
        if (registry != null && delta > 0) {
            registry.counter(OUTBOX_SUSPENDED).increment(delta);
        }
    }

    public void topicNoSubscriber(String topic) {
        if (registry != null) {
            registry.counter(TOPIC_NO_SUBSCRIBER, "topic", topic).increment();
        }
    }
}
