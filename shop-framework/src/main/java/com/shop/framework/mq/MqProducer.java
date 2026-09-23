package com.shop.framework.mq;

import com.shop.common.util.JsonUtils;
import com.shop.framework.metrics.BizMetrics;
import com.shop.framework.web.trace.TraceMdcFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * RocketMQ 生产者封装：JSON 事件体、业务 key、定时/延时消息。
 * 生产者客户端在首次发送时懒加载，避免无 MQ 环境阻塞应用启动。
 */
@Component
public class MqProducer {

    private static final Logger log = LoggerFactory.getLogger(MqProducer.class);

    private final MqProperties properties;
    private volatile Producer producer;

    /** O6：发布计数/失败/耗时；字段注入可空（单测/无注册表空转）。 */
    @Autowired(required = false)
    private BizMetrics bizMetrics;

    public MqProducer(MqProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.warn("RocketMQ 未启用（shop.mq.enabled=false），消息发送将仅记录日志");
        }
    }

    private Producer producer() {
        if (!properties.isEnabled()) {
            return null;
        }
        Producer p = producer;
        if (p == null) {
            synchronized (this) {
                if (producer == null) {
                    try {
                        ClientServiceProvider provider = ClientServiceProvider.loadService();
                        ClientConfiguration conf = ClientConfiguration.newBuilder()
                                .setEndpoints(properties.getEndpoints())
                                .setRequestTimeout(java.time.Duration.ofSeconds(
                                        properties.getRequestTimeoutSeconds()))
                                .build();
                        producer = provider.newProducerBuilder()
                                .setClientConfiguration(conf)
                                .build();
                        log.info("RocketMQ 生产者已启动 endpoints={}", properties.getEndpoints());
                    } catch (ClientException e) {
                        throw new IllegalStateException("RocketMQ 生产者初始化失败", e);
                    }
                }
                p = producer;
            }
        }
        return p;
    }

    /**
     * 同步发送。
     */
    public void send(String topic, String tag, Object payload, String bizKey) {
        if (!properties.isEnabled()) {
            log.info("[MQ-DRYRUN] topic={} tag={} key={} payload={}", topic, tag, bizKey, JsonUtils.toJson(payload));
            return;
        }
        try {
            Producer p = producer();
            if (p == null) {
                return;
            }
            long start = System.nanoTime();
            try {
                p.send(build(topic, tag, payload, bizKey, null));
            } catch (Exception e) {
                if (bizMetrics != null) {
                    bizMetrics.mqPublishFailed(topic, tag);
                }
                throw e;
            }
            if (bizMetrics != null) {
                bizMetrics.mqPublish(topic, tag);
                bizMetrics.mqPublishTimer(topic, System.nanoTime() - start);
            }
        } catch (Exception e) {
            log.error("MQ 同步发送失败 topic={} key={}", topic, bizKey, e);
            throw new RuntimeException("MQ 发送失败: " + topic, e);
        }
    }

    /**
     * 异步发送（支付成功通知等不阻塞主链路场景）。
     */
    public CompletableFuture<Void> sendAsync(String topic, String tag, Object payload, String bizKey) {
        if (!properties.isEnabled()) {
            log.info("[MQ-DRYRUN] topic={} tag={} key={}", topic, tag, bizKey);
            return CompletableFuture.completedFuture(null);
        }
        try {
            Producer p = producer();
            if (p == null) {
                return CompletableFuture.completedFuture(null);
            }
            return p.sendAsync(build(topic, tag, payload, bizKey, null))
                    .whenComplete((receipt, ex) -> {
                        if (ex != null) {
                            if (bizMetrics != null) {
                                bizMetrics.mqPublishFailed(topic, tag);
                            }
                        } else if (bizMetrics != null) {
                            bizMetrics.mqPublish(topic, tag);
                        }
                    }).thenAccept(receipt -> {
                    });
        } catch (Exception e) {
            log.error("MQ 异步发送失败 topic={} key={}", topic, bizKey, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 同步发送已序列化的 JSON 原文（outbox relay 使用，避免二次序列化）。
     */
    public void sendRaw(String topic, String tag, String rawJsonBody, String bizKey) {
        if (!properties.isEnabled()) {
            log.info("[MQ-DRYRUN] topic={} tag={} key={} payload={}", topic, tag, bizKey, rawJsonBody);
            return;
        }
        try {
            Producer p = producer();
            if (p == null) {
                return;
            }
            long start = System.nanoTime();
            try {
                p.send(buildRaw(topic, tag, rawJsonBody, bizKey, null));
            } catch (Exception e) {
                if (bizMetrics != null) {
                    bizMetrics.mqPublishFailed(topic, tag);
                }
                throw e;
            }
            if (bizMetrics != null) {
                bizMetrics.mqPublish(topic, tag);
                bizMetrics.mqPublishTimer(topic, System.nanoTime() - start);
            }
        } catch (Exception e) {
            log.error("MQ 同步发送失败 topic={} key={}", topic, bizKey, e);
            throw new RuntimeException("MQ 发送失败: " + topic, e);
        }
    }

    /**
     * 延时消息：delaySeconds 后投递。用于支付超时关单、自动收货、售后超时等。
     */
    public void sendDelay(String topic, String tag, Object payload, String bizKey, long delaySeconds) {
        if (!properties.isEnabled()) {
            log.info("[MQ-DRYRUN][delay={}s] topic={} tag={} key={}", delaySeconds, topic, tag, bizKey);
            return;
        }
        try {
            Producer p = producer();
            if (p == null) {
                return;
            }
            long deliverAt = System.currentTimeMillis() + delaySeconds * 1000L;
            long start = System.nanoTime();
            try {
                p.send(build(topic, tag, payload, bizKey, deliverAt));
            } catch (Exception inner) {
                if (bizMetrics != null) {
                    bizMetrics.mqPublishFailed(topic, tag);
                }
                throw inner;
            }
            if (bizMetrics != null) {
                bizMetrics.mqPublish(topic, tag);
                bizMetrics.mqPublishTimer(topic, System.nanoTime() - start);
            }
        } catch (Exception e) {
            log.error("MQ 延时消息发送失败 topic={} key={}", topic, bizKey, e);
            throw new RuntimeException("MQ 延时消息发送失败: " + topic, e);
        }
    }

    private Message build(String topic, String tag, Object payload, String bizKey, Long deliveryTimestamp) {
        return buildRaw(topic, tag, JsonUtils.toJson(payload), bizKey, deliveryTimestamp);
    }

    private Message buildRaw(String topic, String tag, String rawJsonBody, String bizKey, Long deliveryTimestamp) {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        byte[] body = rawJsonBody.getBytes(StandardCharsets.UTF_8);
        var builder = provider.newMessageBuilder()
                .setTopic(topic)
                .setBody(body);
        if (tag != null && !tag.isBlank()) {
            builder.setTag(tag);
        }
        if (bizKey != null) {
            builder.setKeys(bizKey);
        }
        if (deliveryTimestamp != null) {
            builder.setDeliveryTimestamp(deliveryTimestamp);
        }
        // O1：MQ 最小链路透传（不实现跨 MQ span）——把入口 MDC 的 traceId/requestId
        // 写入消息 property，消费端 MqConsumerRegistrar 恢复 MDC，消费日志可与生产侧串联。
        String traceId = MDC.get(TraceMdcFilter.MDC_TRACE_ID);
        if (traceId != null && !traceId.isBlank()) {
            builder.addProperty(TraceMdcFilter.MDC_TRACE_ID, traceId);
        }
        String requestId = MDC.get(TraceMdcFilter.MDC_REQUEST_ID);
        if (requestId != null && !requestId.isBlank()) {
            builder.addProperty(TraceMdcFilter.MDC_REQUEST_ID, requestId);
        }
        return builder.build();
    }

    @PreDestroy
    public void close() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
