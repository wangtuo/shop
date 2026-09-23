package com.shop.framework.mq;

import com.shop.common.util.JsonUtils;
import com.shop.framework.metrics.BizMetrics;
import com.shop.framework.web.trace.TraceMdcFilter;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自动扫描全部 {@link MqListener} Bean 并注册 RocketMQ PushConsumer。
 * 多实例部署时同一 consumerGroup 内做队列负载均衡（重平衡），天然支持水平扩容。
 *
 * <p><b>HA 语义：</b>消费者注册在<b>独立守护线程</b>上异步进行，绝不阻塞/拖垮
 * ApplicationContext 启动——Broker/Proxy 滚动发布、开机时序抖动（gRPC telemetry
 * 同步路由失败 {@code Task was cancelled}）时，HTTP 服务照常就绪，注册线程按
 * {@code shop.mq.register-backoff-millis} 指数退避（封顶 30s + 抖动）无限重试；全部消费者
 * 注册完成后状态翻 UP（{@link #allRegistered()} 与 MQ 健康指示器据此展示）。
 * 配合事务性 Outbox：MQ 中断期间生产端事件落库不丢，消费者注册恢复后自动续上消费。
 *
 * <p><b>R4-20 启动宽限：</b>守护线程不在 SmartLifecycle.start() 立即拉消息，而是等
 * {@link ApplicationReadyEvent} 后再延迟 {@code shop.mq.consume-start-delay-millis}
 * （默认 20s）注册。原因：Nacos 首次服务列表推送在 Started 后约 15s 才到，且 broker
 * 积压（含陈旧关单消息）会在消费者一注册时被数百线程并发拉走，打在 JIT/连接池/路由
 * 全冷的下游上，十几次快速失败即可长期熔断（W7 实证 2 分钟不恢复、E2E 25→13 例失败）。
 * 宽限期内消息留 broker（outbox 已同事务持久），滚动发布时由同组其他在线实例承担消费。
 */
@Component
public class MqConsumerRegistrar implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(MqConsumerRegistrar.class);

    private final MqProperties properties;
    private final List<MqListener<?>> listeners;
    private final EventNormalizer eventNormalizer;
    private final MqMetrics metrics;
    /** O6：消费成功/失败/死信计数（标签 topic,group,event,reason）。 */
    private final BizMetrics bizMetrics;
    private final Map<String, PushConsumer> consumers = new ConcurrentHashMap<>();
    private final ExecutorService registrar = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mq-consumer-registrar");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile boolean started;
    /** R4-20：ApplicationReadyEvent 是否已到。 */
    private volatile boolean readyEventFired;
    /** R4-20：宽限后的注册任务是否已提交（幂等防重复调度）。 */
    private volatile boolean registrationScheduled;
    /** R4-20：宽限结束的墙钟时刻（0=未进入宽限或宽限已结束）。 */
    private volatile long graceUntilMillis;
    /** 最近一次注册失败的描述（健康指示器输出）；全部成功后清空。 */
    private volatile String lastFailure;

    /** 测试/无指标环境便捷构造（指标空转、eventId 合成按 properties 默认开启）。 */
    public MqConsumerRegistrar(MqProperties properties, List<MqListener<?>> listeners) {
        this(properties, listeners, null);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public MqConsumerRegistrar(MqProperties properties, List<MqListener<?>> listeners,
                               io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.properties = properties;
        this.listeners = listeners;
        this.eventNormalizer = new EventNormalizer(properties);
        this.metrics = meterRegistry == null ? new MqMetrics(null) : new MqMetrics(meterRegistry);
        this.bizMetrics = new BizMetrics(meterRegistry);
    }

    @Override
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        if (!properties.isEnabled() || listeners.isEmpty()) {
            running = true;
            log.info("MQ 消费者注册跳过（enabled={}, listeners={}）", properties.isEnabled(), listeners.size());
            return;
        }
        log.info("MQ 消费者注册已就绪，等待 ApplicationReadyEvent + 启动宽限 {}ms 后开始拉取"
                        + "（listeners={}，宽限期不阻断 HTTP 服务、消息留 broker）",
                properties.getConsumeStartDelayMillis(), listeners.size());
        // C17/Z3：消费重试次数只能由 broker 订阅组配置决定（5.0.7 PushConsumerBuilder 无
        // maxReconsumeTimes/retry setter），create-topics.sh updateSubGroup -r 16 -q 1 是唯一
        // 生效入口；consume-max-attempts 为已弃用回显键，改它不影响实际行为。
        log.info("MQ 消费重试/DLQ 以 broker 订阅组配置为准（updateSubGroup -r 16 -q 1，"
                + "超过 16 次进 %DLQ%）；shop.mq.consume-max-attempts={} 仅回显、不接线",
                properties.getConsumeMaxAttempts());
        // 正常时序 ReadyEvent 在 start() 之后；若事件先到则在此补调度，二者只生效一次。
        onAfterStart();
    }

    /**
     * R4-20：应用完全就绪（HTTP 监听已起、CommandLineRunner 已跑完）后再进入启动宽限，
     * 宽限结束才在守护线程上开始注册 PushConsumer。极端容器里若 ReadyEvent 早于
     * SmartLifecycle.start（正常时序不会），{@code onAfterStart()} 保证不漏调度。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        readyEventFired = true;
        onAfterStart();
    }

    private synchronized void onAfterStart() {
        if (!started || !readyEventFired || registrationScheduled
                || !properties.isEnabled() || listeners.isEmpty()) {
            return;
        }
        registrationScheduled = true;
        long delay = Math.max(0L, properties.getConsumeStartDelayMillis());
        graceUntilMillis = delay == 0 ? 0 : System.currentTimeMillis() + delay;
        log.info("MQ 启动宽限开始：{}ms 后注册消费者（等待 JIT/Druid/Nacos 路由预热，"
                + "期间积压消息留在 broker，由同组其他在线实例或宽限后本实例消费）", delay);
        registrar.submit(() -> {
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            graceUntilMillis = 0;
            if (!Thread.currentThread().isInterrupted()) {
                registerAllLoop();
            }
        });
    }

    /**
     * 守护线程主循环：每一轮对所有未注册消费者各尝试一次，单个消费者失败不阻塞
     * 其他消费者；轮间按固定退避等待，无限重试直到全部注册完成。
     */
    private void registerAllLoop() {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration conf = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .setRequestTimeout(java.time.Duration.ofSeconds(
                        properties.getRequestTimeoutSeconds()))
                .build();
        Map<String, Integer> attempts = new ConcurrentHashMap<>();
        long base = Math.max(100L, properties.getRegisterBackoffMillis());
        while (!Thread.currentThread().isInterrupted()) {
            boolean allDone = true;
            for (MqListener<?> listener : listeners) {
                if (consumers.containsKey(listener.consumerGroup())) {
                    continue;
                }
                allDone = false;
                int n = attempts.merge(listener.consumerGroup(), 1, Integer::sum);
                try {
                    PushConsumer consumer = buildOnce(provider, conf, listener);
                    consumers.put(listener.consumerGroup(), consumer);
                    lastFailure = null;
                    log.info("MQ 消费者已注册 group={} topic={} tag={}（第{}次尝试，累计 {}/{}；"
                                    + "retryMaxTimes={} 取 broker 订阅组配置，超限进 %DLQ%）",
                            listener.consumerGroup(), listener.topic(),
                            listener.tag() == null || listener.tag().isBlank() ? "*" : listener.tag(),
                            n, consumers.size(), listeners.size(), properties.getConsumeMaxAttempts());
                } catch (Exception e) {
                    lastFailure = listener.consumerGroup() + "/" + listener.topic() + ": " + e;
                    log.warn("MQ 消费者注册失败，{}ms 后下一轮重试（第{}次）group={} topic={}",
                            backoffMillis(base, n), n, listener.consumerGroup(), listener.topic(), e);
                }
            }
            if (allDone) {
                running = true;
                log.info("MQ 全部 {} 个消费者注册完成", listeners.size());
                return;
            }
            // 指数退避封顶 30s + ±20% 抖动：broker 长时间中断时限制重试频率。
            // 底层 builder 在失败路径不释放 ClientImpl 的 gRPC 资源（rocketmq-client-java
            // 5.0.x PushConsumerBuilderImpl.build 失败无 close），风暴式重试会持续泄漏事件循环，
            // 退避封顶把泄漏速率压到每个未注册消费者约 2 次/分钟，恢复后一次成功即停止累积。
            long sleepMs = backoffMillis(base, attempts.values().stream()
                    .mapToInt(Integer::intValue).max().orElse(1));
            try {
                long jitter = (long) (sleepMs * 0.2 * (Math.random() * 2 - 1));
                Thread.sleep(Math.max(100L, sleepMs + jitter));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** base·2^(n-1)，封顶 30s。 */
    private static long backoffMillis(long base, int attempt) {
        return Math.min(30_000L, base * (1L << Math.min(attempt - 1, 10)));
    }

    /** 单次构造 PushConsumer（不做重试，重试由 {@link #registerAllLoop()} 统一驱动）。 */
    private PushConsumer buildOnce(ClientServiceProvider provider, ClientConfiguration conf,
                                   MqListener<?> listener)
            throws org.apache.rocketmq.client.apis.ClientException, InterruptedException {
        String tagExpr = listener.tag() == null || listener.tag().isBlank() ? "*" : listener.tag();
        FilterExpression filter = new FilterExpression(tagExpr, FilterExpressionType.TAG);
        return provider.newPushConsumerBuilder()
                .setClientConfiguration(conf)
                .setConsumerGroup(listener.consumerGroup())
                .setSubscriptionExpressions(Map.of(listener.topic(), filter))
                .setMessageListener(messageView -> {
                    // O1：从消息 property 恢复生产侧 MDC（消费线程为 RocketMQ 客户端线程，
                    // 与 HTTP 线程池无关），消费结束无条件清理，避免客户端线程复用串号。
                    Map<String, String> props = messageView.getProperties();
                    TraceMdcFilter.restoreMqTrace(
                            props.get(TraceMdcFilter.MDC_TRACE_ID),
                            props.get(TraceMdcFilter.MDC_REQUEST_ID));
                    try {
                        return dispatch(listener, messageView);
                    } finally {
                        TraceMdcFilter.clearRequestMdc();
                    }
                })
                .build();
    }

    /** 全部消费者是否已注册完成（MQ 健康指示器据此判定）。 */
    public boolean allRegistered() {
        return running;
    }

    /** R4-20：是否仍处于 ApplicationReady 后的启动宽限（此阶段不应期待任何消费进度）。 */
    public boolean isInStartupGrace() {
        return graceUntilMillis > System.currentTimeMillis();
    }

    public int registeredCount() {
        return consumers.size();
    }

    public int totalCount() {
        return listeners.size();
    }

    public String getLastFailure() {
        return lastFailure;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ConsumeResult dispatch(MqListener listener, MessageView messageView) {
        byte[] bodyBytes;
        String bodyJson;
        try {
            bodyBytes = toBytes(messageView.getBody());
            bodyJson = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            log.error("MQ 消息体读取失败，ACK 丢弃 group={} topic={} msgId={}",
                    listener.consumerGroup(), listener.topic(), messageView.getMessageId(), t);
            metrics.consumeDead(listener.topic(), listener.consumerGroup());
            bizMetrics.mqDeadLetter(listener.topic(), listener.consumerGroup(),
                    messageView.getTag().orElse(""), "body_read_error");
            return ConsumeResult.SUCCESS;
        }

        // 最外层装饰：统一 eventId（keys/header/body 字段 → noid 合成），绑定消费上下文
        MqConsumeContext context = eventNormalizer.normalize(messageView, bodyJson);
        if (context.getEventId() == null) {
            // shop.mq.event.synthetic-enabled=false 的备选策略：不放行业务、不阻塞队列
            log.error("MQ 消息缺失 eventId 且兜底合成已关闭，ACK 丢弃并告警 group={} topic={} msgId={} keys={}",
                    listener.consumerGroup(), listener.topic(),
                    messageView.getMessageId(), messageView.getKeys());
            metrics.consumeDead(listener.topic(), listener.consumerGroup());
            bizMetrics.mqDeadLetter(listener.topic(), listener.consumerGroup(),
                    messageView.getTag().orElse(""), "missing_event_id");
            return ConsumeResult.SUCCESS;
        }
        if (context.isSynthetic() && log.isDebugEnabled()) {
            log.debug("MQ eventId 缺失，框架合成 eventId={} group={} topic={}",
                    context.getEventId(), listener.consumerGroup(), listener.topic());
        }

        Object payload;
        try {
            payload = JsonUtils.fromJson(bodyJson, listener.type());
        } catch (Throwable t) {
            // 消息体无法反序列化属于毒丸：重试不会改变字节内容，ACK + error 日志 + 终态指标
            log.error("MQ 消息反序列化失败，ACK 丢弃 group={} topic={} msgId={} eventId={}",
                    listener.consumerGroup(), listener.topic(), messageView.getMessageId(),
                    context.getEventId(), t);
            metrics.consumeDead(listener.topic(), listener.consumerGroup());
            bizMetrics.mqDeadLetter(listener.topic(), listener.consumerGroup(),
                    messageView.getTag().orElse(""), "deserialize");
            return ConsumeResult.SUCCESS;
        }
        try {
            MqConsumeContext.bind(context);
            return invoke(listener, payload, messageView);
        } finally {
            MqConsumeContext.clear();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    ConsumeResult invoke(MqListener listener, Object payload, MessageView messageView) {
        String event = messageView.getTag().orElse("");
        try {
            listener.onMessage(payload);
            bizMetrics.mqConsume(listener.topic(), listener.consumerGroup(), event, "ok");
            return ConsumeResult.SUCCESS;
        } catch (Throwable t) {
            if (MqErrorPolicy.isTerminal(t)) {
                // 毒丸消息（参数/鉴权/金额终态错误）：重试永不会成功，ACK 丢弃避免占死消费线程；
                // 打印完整上下文供告警与对账人工核对，禁止静默吞掉。
                log.error("MQ 消费命中不可恢复错误，ACK 丢弃并等待对账/人工处理 group={} topic={} tag={} msgId={} eventId={} keys={}",
                        listener.consumerGroup(), listener.topic(),
                        messageView.getTag().orElse("*"),
                        messageView.getMessageId(), MqConsumeContext.currentEventId(),
                        messageView.getKeys(), t);
                metrics.consumeDead(listener.topic(), listener.consumerGroup());
                bizMetrics.mqDeadLetter(listener.topic(), listener.consumerGroup(), event, "terminal");
                return ConsumeResult.SUCCESS;
            }
            log.error("MQ 消费失败（可恢复，broker 将重试）group={} topic={} msgId={} eventId={}",
                    listener.consumerGroup(), listener.topic(), messageView.getMessageId(),
                    MqConsumeContext.currentEventId(), t);
            metrics.consumeRetry(listener.topic(), listener.consumerGroup());
            bizMetrics.mqConsumeFailed(listener.topic(), listener.consumerGroup(), event, "retryable");
            return ConsumeResult.FAILURE;
        }
    }

    private byte[] toBytes(ByteBuffer buffer) {
        if (buffer.hasArray()) {
            return java.util.Arrays.copyOfRange(buffer.array(),
                    buffer.arrayOffset() + buffer.position(),
                    buffer.arrayOffset() + buffer.limit());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[1024];
        while (buffer.hasRemaining()) {
            int n = Math.min(tmp.length, buffer.remaining());
            buffer.get(tmp, 0, n);
            out.write(tmp, 0, n);
        }
        return out.toByteArray();
    }

    @Override
    public synchronized void stop() {
        registrar.shutdownNow();
        consumers.values().forEach(c -> {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        });
        consumers.clear();
        running = false;
        started = false;
        readyEventFired = false;
        registrationScheduled = false;
        graceUntilMillis = 0;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }
}
