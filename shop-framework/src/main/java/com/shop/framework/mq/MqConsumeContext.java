package com.shop.framework.mq;

/**
 * 单条消息的消费上下文（C15 事件标准化）。
 *
 * <p>由 {@link EventNormalizer} 在进入业务 listener 前构造并绑定到 ThreadLocal，
 * 业务代码（含短事务内的幂等落库）通过 {@link #currentEventId()} 取统一非空 eventId，
 * 不再允许各服务私拼 {@code noid:topic:bizNo} 或对 null eventId 各自抛异常。
 *
 * <p>合成 eventId 确定性：同一消息（同 topic + bizKey/msgId）多次投递合成结果一致，
 * 保证 broker 重试时命中同一消费流水行（幂等不被重试打破）。
 */
public class MqConsumeContext {

    private static final ThreadLocal<MqConsumeContext> CURRENT = new ThreadLocal<>();

    private final String eventId;
    private final String topic;
    private final String bizKey;
    private final String msgId;
    /** true=框架兜底合成（上游未带 eventId）；false=上游显式携带。 */
    private final boolean synthetic;

    public MqConsumeContext(String eventId, String topic, String bizKey, String msgId, boolean synthetic) {
        this.eventId = eventId;
        this.topic = topic;
        this.bizKey = bizKey;
        this.msgId = msgId;
        this.synthetic = synthetic;
    }

    public static void bind(MqConsumeContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 当前消费上下文；未处于 MQ 消费线程时为 null。 */
    public static MqConsumeContext current() {
        return CURRENT.get();
    }

    /** 当前消息的统一 eventId（永不返回空白：缺失时已由框架合成 noid 键）。 */
    public static String currentEventId() {
        MqConsumeContext c = CURRENT.get();
        return c == null ? null : c.eventId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String bizKey() {
        return bizKey;
    }

    public String getMsgId() {
        return msgId;
    }

    public boolean isSynthetic() {
        return synthetic;
    }
}
