package com.shop.marketing.mq;

import com.shop.framework.id.IdGenerator;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.marketing.mq.mapper.MqConsumeLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4-27：消费幂等模板的 eventId 归一化——body 优先，空白回退框架上下文，双空 fail-fast。
 */
@ExtendWith(MockitoExtension.class)
class MqConsumeTemplateTest {

    @Mock private MqConsumeLogMapper mapper;
    @Mock private IdGenerator idGenerator;

    private MqConsumeTemplate template;

    @BeforeEach
    void setUp() {
        template = new MqConsumeTemplate(mapper, idGenerator);
        when(idGenerator.nextId()).thenReturn(1L);
    }

    @AfterEach
    void clearContext() {
        MqConsumeContext.clear();
    }

    @Test
    @DisplayName("body eventId非空_直接使用并执行业务")
    void bodyEventIdUsed() {
        when(mapper.insertIgnore(anyLong(), eq("EV1"), eq("t"), eq("O1"))).thenReturn(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        template.runOnce("EV1", "t", "O1", () -> ran.set(true));

        assertTrue(ran.get());
    }

    @Test
    @DisplayName("R4-27 body空白_以上下文noid eventId落库并执行业务")
    void blankBody_fallsBackToContext() {
        MqConsumeContext.bind(new MqConsumeContext("noid:t:O1", "t", "O1", "rmq-1", true));
        when(mapper.insertIgnore(anyLong(), eq("noid:t:O1"), eq("t"), eq("O1"))).thenReturn(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        template.runOnce(" ", "t", "O1", () -> ran.set(true));

        assertTrue(ran.get());
    }

    @Test
    @DisplayName("R4-27 重复行rows=0_不执行业务")
    void duplicate_actionSkipped() {
        when(mapper.insertIgnore(anyLong(), eq("EV1"), eq("t"), eq("O1"))).thenReturn(0);
        AtomicBoolean ran = new AtomicBoolean(false);

        template.runOnce("EV1", "t", "O1", () -> ran.set(true));

        assertFalse(ran.get());
    }

    @Test
    @DisplayName("R4-27 body空白且无上下文_failFast不写库不执行业务")
    void bothBlank_failFast() {
        AtomicBoolean ran = new AtomicBoolean(false);

        assertThrows(IllegalStateException.class,
                () -> template.runOnce(null, "t", "O1", () -> ran.set(true)));
        assertFalse(ran.get());
        verify(mapper, never()).insertIgnore(anyLong(), eq(null), eq("t"), eq("O1"));
    }
}
