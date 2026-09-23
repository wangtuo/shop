package com.shop.framework.mq;

import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** C15：eventId 解析顺序、noid 合成确定性与开关行为。 */
class EventNormalizerTest {

    private EventNormalizer normalizer(boolean syntheticEnabled) {
        MqProperties properties = new MqProperties();
        properties.getEvent().setSyntheticEnabled(syntheticEnabled);
        return new EventNormalizer(properties);
    }

    private MessageView messageView(Map<String, String> props, List<String> keys, String msgId) {
        MessageView mv = mock(MessageView.class);
        when(mv.getTopic()).thenReturn("shop_order_created");
        when(mv.getProperties()).thenReturn(props);
        when(mv.getKeys()).thenReturn(keys);
        MessageId id = mock(MessageId.class);
        when(id.toString()).thenReturn(msgId);
        when(mv.getMessageId()).thenReturn(id);
        return mv;
    }

    @Test
    void header显式eventId优先_不合成() {
        MessageView mv = messageView(Map.of("eventId", "evt-header-1"), List.of("ORDER-1"), "mid-1");
        MqConsumeContext ctx = normalizer(true).normalize(mv, "{\"eventId\":\"evt-body-1\"}");
        assertEquals("evt-header-1", ctx.getEventId());
        assertFalse(ctx.isSynthetic());
    }

    @Test
    void header下划线与大小写别名均可识别() {
        MessageView mv = messageView(Map.of("EVENT_ID", "evt-alias"), Collections.emptyList(), "m");
        assertEquals("evt-alias", normalizer(true).normalize(mv).getEventId());
    }

    @Test
    void header缺失时取消息体eventId字段() {
        MessageView mv = messageView(Collections.emptyMap(), List.of("ORDER-2"), "m");
        MqConsumeContext ctx = normalizer(true).normalize(mv, "{\"eventId\":\"evt-body-2\",\"x\":1}");
        assertEquals("evt-body-2", ctx.getEventId());
        assertFalse(ctx.isSynthetic());
    }

    @Test
    void 全部缺失时按topic加bizKey合成_与order历史noid口径一致() {
        MessageView mv = messageView(Collections.emptyMap(), List.of("ORDER-3"), "mid-3");
        MqConsumeContext ctx = normalizer(true).normalize(mv, "{\"orderNo\":\"ORDER-3\"}");
        assertEquals("noid:shop_order_created:ORDER-3", ctx.getEventId());
        assertTrue(ctx.isSynthetic());
        assertEquals("ORDER-3", ctx.bizKey());
    }

    @Test
    void keys为空时回退msgId合成() {
        MessageView mv = messageView(Collections.emptyMap(), Collections.emptyList(), "msg-id-9");
        MqConsumeContext ctx = normalizer(true).normalize(mv, "not-a-json");
        assertEquals("noid:shop_order_created:msg-id-9", ctx.getEventId());
        assertTrue(ctx.isSynthetic());
    }

    @Test
    void 同一消息两次合成结果一致_保证重试命中同一幂等行() {
        MessageView mv = messageView(Collections.emptyMap(), List.of("ORDER-7"), "mid-7");
        EventNormalizer n = normalizer(true);
        assertEquals(n.normalize(mv).getEventId(), n.normalize(mv).getEventId());
    }

    @Test
    void 关闭合成时缺失eventId返回null_由registrar走ACK告警备选() {
        MessageView mv = messageView(Collections.emptyMap(), List.of("ORDER-8"), "mid-8");
        MqConsumeContext ctx = normalizer(false).normalize(mv, "{}");
        assertNull(ctx.getEventId());
        assertFalse(ctx.isSynthetic());
    }

    @Test
    void 非JSON消息体不报错_继续合成() {
        MessageView mv = messageView(Collections.emptyMap(), List.of("B-1"), "m");
        assertEquals("noid:shop_order_created:B-1", normalizer(true).normalize(mv, "plain-text").getEventId());
    }
}
