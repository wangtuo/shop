package com.shop.framework.outbox;

import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.entity.OutboxMessage;
import com.shop.framework.outbox.mapper.OutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P1-1：outbox 登记必须与业务同事务，字段（含延时 deliverAt）正确落表。 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxMapper outboxMapper;
    @Mock
    private IdGenerator idGenerator;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(outboxMapper, idGenerator);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void beginTx() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @Test
    void 事务内登记_普通事件_待投递且deliverAt为当前时刻() {
        beginTx();
        when(idGenerator.nextId()).thenReturn(1001L);

        publisher.publish("ORDER_PAID", "pay", new TestEvent("O1", 20000L), "O1");

        ArgumentCaptor<OutboxMessage> cap = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMapper).insert(cap.capture());
        OutboxMessage m = cap.getValue();
        assertEquals(1001L, m.getId());
        assertEquals("ORDER_PAID", m.getTopic());
        assertEquals("pay", m.getTag());
        assertEquals("O1", m.getBizKey());
        assertEquals(0, m.getStatus());
        assertTrue(m.getBodyJson().contains("\"orderNo\":\"O1\""));
        assertTrue(m.getBodyJson().contains("20000"));
        assertTrue(!m.getDeliverAt().isAfter(LocalDateTime.now().plusSeconds(1)));
    }

    @Test
    void 事务内登记_延时事件_deliverAt后延() {
        beginTx();
        when(idGenerator.nextId()).thenReturn(1002L);
        LocalDateTime before = LocalDateTime.now().plusSeconds(86400).minusSeconds(2);

        publisher.publishDelay("ORDER_DELAY", null, new TestEvent("O2", 1L), "O2", 86400L);

        ArgumentCaptor<OutboxMessage> cap = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMapper).insert(cap.capture());
        OutboxMessage m = cap.getValue();
        assertEquals("", m.getTag());
        assertTrue(m.getDeliverAt().isAfter(before));
    }

    @Test
    void 事务外登记_failFast拒绝() {
        assertThrows(IllegalStateException.class,
                () -> publisher.publish("T", null, new TestEvent("O3", 1L), "O3"));
    }

    @Test
    void topic为空_参数拒绝() {
        beginTx();
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish("  ", null, new TestEvent("O4", 1L), "O4"));
    }

    /** 简单 POJO 验证 JSON 序列化口径。 */
    public record TestEvent(String orderNo, long payFen) {
    }
}
