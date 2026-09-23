package com.shop.framework.outbox;

import com.shop.framework.mq.MqProducer;
import com.shop.framework.outbox.entity.OutboxMessage;
import com.shop.framework.outbox.mapper.OutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

/**
 * P1-1 / M3：relay 逐条投递——成功 CAS 置位；失败走退避记账且不抛断整批。
 * 事务生效由独立 Bean + REQUIRES_NEW 保证（Spring 代理集成测试覆盖，此处验证记账语义）。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayDispatcherTest {

    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private MqProducer mqProducer;

    private OutboxRelayDispatcher dispatcher() {
        return new OutboxRelayDispatcher(outboxMapper, mqProducer, 20);
    }

    private OutboxMessage msg(long id, String topic, String tag, String key, String body) {
        OutboxMessage m = new OutboxMessage();
        m.setId(id);
        m.setTopic(topic);
        m.setTag(tag);
        m.setBizKey(key);
        m.setBodyJson(body);
        m.setRetryCount(0);
        return m;
    }

    @Test
    void 投递成功_发送原文并CAS置已投递() {
        OutboxRelayDispatcher d = dispatcher();
        OutboxMessage m = msg(1L, "ORDER_PAID", "pay", "O1", "{\"orderNo\":\"O1\"}");

        d.dispatch(m);

        verify(mqProducer).sendRaw("ORDER_PAID", "pay", "{\"orderNo\":\"O1\"}", "O1");
        verify(outboxMapper).markSent(1L);
        verify(outboxMapper, never()).recordFailure(anyLong(), anyInt(), anyString());
    }

    @Test
    void 空tag按null发送() {
        OutboxRelayDispatcher d = dispatcher();
        OutboxMessage m = msg(2L, "T", "", "k", "{}");

        d.dispatch(m);

        verify(mqProducer).sendRaw("T", null, "{}", "k");
        verify(outboxMapper).markSent(2L);
    }

    @Test
    void 投递抛错_记录失败退避_不向上抛() {
        OutboxRelayDispatcher d = dispatcher();
        OutboxMessage m = msg(3L, "T", null, "k", "{}");
        doThrow(new RuntimeException("broker down"))
                .when(mqProducer).sendRaw("T", null, "{}", "k");

        d.dispatch(m); // 不抛异常：保证整批继续

        verify(outboxMapper).recordFailure(eq(3L), eq(20), eq("java.lang.RuntimeException: broker down"));
        verify(outboxMapper, never()).markSent(3L);
    }
}
