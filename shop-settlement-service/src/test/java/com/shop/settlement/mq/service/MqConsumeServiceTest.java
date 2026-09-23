package com.shop.settlement.mq.service;

import com.shop.framework.mq.MqConsumeContext;
import com.shop.settlement.mq.mapper.MqConsumeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * MQ 消费幂等服务单测：INSERT IGNORE 首次 1 行需处理，重复 0 行直接 ACK；bizNo 为 null 兜底空串。
 */
@ExtendWith(MockitoExtension.class)
class MqConsumeServiceTest {

    @Mock private MqConsumeMapper mqConsumeMapper;

    private MqConsumeService service;

    @BeforeEach
    void setUp() {
        service = new MqConsumeService(mqConsumeMapper);
    }

    @Test
    @DisplayName("tryRecord_首次eventId返回true")
    void firstEvent_true() {
        when(mqConsumeMapper.insertIgnore(eq("EV1"), eq("shop_order_paid"), eq("cg_sett_paid"), eq("O1")))
                .thenReturn(1);
        assertTrue(service.tryRecord("EV1", "shop_order_paid", "cg_sett_paid", "O1"));
    }

    @Test
    @DisplayName("tryRecord_重复eventId返回false_null_bizNo转空串")
    void duplicateEvent_false() {
        when(mqConsumeMapper.insertIgnore(anyString(), anyString(), anyString(), eq("")))
                .thenReturn(0);
        assertFalse(service.tryRecord("EV1", "t", "g", null));
    }

    @AfterEach
    void clearContext() {
        MqConsumeContext.clear();
    }

    @Test
    @DisplayName("R4-27 body eventId空白_回退框架上下文eventId落库")
    void blankBodyEvent_fallsBackToContext() {
        MqConsumeContext.bind(new MqConsumeContext(
                "noid:shop_order_paid:O9", "shop_order_paid", "O9", "rmq-1", true));
        when(mqConsumeMapper.insertIgnore(eq("noid:shop_order_paid:O9"),
                eq("shop_order_paid"), eq("g"), eq("O9"))).thenReturn(1);
        assertTrue(service.tryRecord("  ", "shop_order_paid", "g", "O9"));
    }

    @Test
    @DisplayName("R4-27 body与上下文eventId双空_failFast不查库")
    void bothBlank_throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.tryRecord(null, "shop_order_paid", "g", "O9"));
        assertTrue(ex.getMessage().contains("eventId"));
    }

    @Test
    @DisplayName("R4-27 resolveEventId_body非空时优先body不读上下文")
    void bodyEventWins() {
        MqConsumeContext.bind(new MqConsumeContext("ctx-id", "t", "b", "m", false));
        assertEquals("body-id", MqConsumeService.resolveEventId("body-id", "t"));
    }
}
