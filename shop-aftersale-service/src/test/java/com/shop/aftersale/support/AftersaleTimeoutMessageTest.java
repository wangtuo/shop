package com.shop.aftersale.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * P2-1：超时事件确定性 eventId 工厂——MQ 延时消息与 60s 扫表双路径据此共享幂等键。
 */
class AftersaleTimeoutMessageTest {

    @Test
    void forAftersale_eventId确定且重发恒定() {
        AftersaleTimeoutMessage m1 = AftersaleTimeoutMessage.forAftersale("AS1001", AftersaleDelayTopics.KIND_AUDIT);
        AftersaleTimeoutMessage m2 = AftersaleTimeoutMessage.forAftersale("AS1001", AftersaleDelayTopics.KIND_AUDIT);

        assertEquals("TO-AS1001-audit", m1.getEventId());
        assertEquals(m1.getEventId(), m2.getEventId());
        assertEquals("AS1001", m1.getBizNo());
        assertEquals("AS1001", m1.getAftersaleNo());
        assertNotNull(m1.getOccurredAt());
    }

    @Test
    void forAftersale_不同kind_eventId不同() {
        String audit = AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_AUDIT).getEventId();
        String receive = AftersaleTimeoutMessage.forAftersale("AS1", AftersaleDelayTopics.KIND_RECEIVE).getEventId();
        assertNotEquals(audit, receive);
    }

    @Test
    void forInsurance_eventId以insuranceId派生且每理赔单唯一() {
        AftersaleTimeoutMessage m1 = AftersaleTimeoutMessage.forInsurance(7L, "AS9", AftersaleDelayTopics.KIND_INSURANCE);
        AftersaleTimeoutMessage m1Replay = AftersaleTimeoutMessage.forInsurance(7L, "AS9", AftersaleDelayTopics.KIND_INSURANCE);
        AftersaleTimeoutMessage m2 = AftersaleTimeoutMessage.forInsurance(8L, "AS10", AftersaleDelayTopics.KIND_INSURANCE);

        assertEquals("TO-INS-7-insurance", m1.getEventId());
        assertEquals(m1.getEventId(), m1Replay.getEventId());
        assertNotEquals(m1.getEventId(), m2.getEventId());
        // bizNo 统一为售后单号，流水排查与消息 keys 口径一致
        assertEquals("AS9", m1.getBizNo());
    }

    @Test
    void deriveId_保险类优先insuranceId_关单类按售后单号() {
        assertEquals("TO-INS-3-insurance",
                AftersaleTimeoutMessage.deriveId("AS1", AftersaleDelayTopics.KIND_INSURANCE, 3L));
        assertEquals("TO-AS1-evidence",
                AftersaleTimeoutMessage.deriveId("AS1", AftersaleDelayTopics.KIND_EVIDENCE, null));
    }
}
