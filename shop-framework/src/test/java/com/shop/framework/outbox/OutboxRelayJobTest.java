package com.shop.framework.outbox;

import com.shop.framework.mq.MqProducer;
import com.shop.framework.outbox.entity.OutboxMessage;
import com.shop.framework.outbox.mapper.OutboxMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 慢车道重排/死信告警与人工 requeue。快车道逐条投递事务见 {@link OutboxRelayDispatcherTest}。
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayJobTest {

    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private MqProducer mqProducer;
    @Mock
    private OutboxRelayDispatcher dispatcher;

    private OutboxRelayJob job() {
        return new OutboxRelayJob(outboxMapper, dispatcher, 100, 300, 3);
    }

    @Test
    void 慢车道放行冷却期满且未超挂起上限的消息() {
        when(outboxMapper.requeueSuspended(300L, 3)).thenReturn(7);
        when(outboxMapper.countDead(3)).thenReturn(0L);

        job().requeueSuspended();

        verify(outboxMapper).requeueSuspended(300L, 3);
    }

    @Test
    void 存在超限死信_扫描仍正常且计数暴露给告警() {
        when(outboxMapper.requeueSuspended(300L, 3)).thenReturn(0);
        when(outboxMapper.countDead(3)).thenReturn(2L);

        job().requeueSuspended(); // 不抛异常；死信计数由 ERROR 日志接告警

        verify(outboxMapper).countDead(3);
    }

    @Test
    void requeue_挂起消息放行() {
        when(outboxMapper.requeue(9L)).thenReturn(1);
        assertEquals(1, job().requeue(9L));
    }
}
