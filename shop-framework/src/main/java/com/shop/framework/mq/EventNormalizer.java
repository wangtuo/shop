package com.shop.framework.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.message.MessageView;

import java.util.Collection;
import java.util.Map;

/**
 * 事件标准化（C15 / R-MQ）：在业务 listener 执行前统一解析 eventId。
 *
 * <p>解析顺序（首个非空值生效，确定性可重放）：
 * <ol>
 *   <li>消息属性/header：{@code eventId} / {@code event-id} / {@code event_id}（大小写不敏感）；</li>
 *   <li>消息体 JSON 字段 {@code eventId}（事件信封字段，解析失败静默跳过，不影响主流程）；</li>
 *   <li>均缺失时合成 {@code noid:{topic}:{bizKey|msgId}}——bizKey 取 RocketMQ keys
 *       （MqProducer 约定 keys=bizKey），keys 也为空时用 msgId 兜底。</li>
 * </ol>
 *
 * <p>合成口径与 shop-order 历史私有拼接 {@code noid:topic:bizNo} 完全一致，重试时同消息
 * 合成结果不变，消费流水幂等行稳定命中。业务侧私有拼接/IllegalArgumentException 分支
 * （shop-order MqConsumeService、shop-user MqConsumeServiceImpl）应在各自业务波删除，
 * 统一改由本类 + {@link MqConsumeContext#currentEventId()} 提供。
 *
 * <p>开关 {@code shop.mq.event.synthetic-enabled}（默认 true）。关闭时缺失 eventId 的消息
 * 不进入业务 listener，由 registrar 记 ERROR + 终态丢弃指标后 ACK（备选策略）。
 */
public class EventNormalizer {

    public static final String NOID_PREFIX = "noid:";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final boolean syntheticEnabled;

    public EventNormalizer(MqProperties properties) {
        this.syntheticEnabled = properties.getEvent().isSyntheticEnabled();
    }

    public boolean isSyntheticEnabled() {
        return syntheticEnabled;
    }

    /** 仅从 header/keys 解析（不解析消息体）。 */
    public MqConsumeContext normalize(MessageView messageView) {
        return normalize(messageView, null);
    }

    /**
     * @param rawBodyJson 原始消息体 JSON（反序列化前的同一份字节），允许 null
     */
    public MqConsumeContext normalize(MessageView messageView, String rawBodyJson) {
        String topic = messageView.getTopic();
        String bizKey = firstNonBlank(messageView.getKeys());
        String msgId = String.valueOf(messageView.getMessageId());

        String eventId = resolveFromHeaders(messageView.getProperties());
        if (isBlank(eventId)) {
            eventId = resolveFromBody(rawBodyJson);
        }

        boolean synthetic = false;
        if (isBlank(eventId)) {
            if (!syntheticEnabled) {
                // 关闭兜底：返回 eventId=null 的上下文，由 registrar 走 ACK+ERROR 备选分支
                return new MqConsumeContext(null, topic, bizKey, msgId, false);
            }
            String suffix = bizKey != null ? bizKey : msgId;
            eventId = NOID_PREFIX + topic + ":" + suffix;
            synthetic = true;
        }
        return new MqConsumeContext(eventId, topic, bizKey, msgId, synthetic);
    }

    private static String resolveFromHeaders(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String normalized = e.getKey().toLowerCase().replace('-', '_');
            if (("event_id".equals(normalized) || "eventid".equals(normalized))
                    && !isBlank(e.getValue())) {
                return e.getValue().trim();
            }
        }
        return null;
    }

    /** 从事件体信封字段取 eventId；任何异常（非 JSON/无字段）都视为缺失，不阻断消费。 */
    private static String resolveFromBody(String rawBodyJson) {
        if (isBlank(rawBodyJson)) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(rawBodyJson);
            JsonNode v = node.get("eventId");
            if (v != null && v.isTextual() && !isBlank(v.asText())) {
                return v.asText().trim();
            }
        } catch (Exception ignored) {
            // 非 JSON 事件（纯值对象等）没有统一信封字段，走合成兜底
        }
        return null;
    }

    private static String firstNonBlank(Collection<String> keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!isBlank(key)) {
                return key.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
