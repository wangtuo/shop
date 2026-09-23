package com.shop.pay.mq.support;

import com.shop.api.order.event.OrderCancelledEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.model.BaseEvent;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.pay.mq.entity.MqConsumeLog;
import com.shop.pay.mq.mapper.MqConsumeMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R4-27：firstConsume 的 eventId 归一化——body 优先，空白回退框架上下文，双空 fail-fast。
 */
@ExtendWith(MockitoExtension.class)
class MqConsumeSupportTest {

    @Mock private MqConsumeMapper mqConsumeMapper;

    @AfterEach
    void clearContext() {
        MqConsumeContext.clear();
    }

    private BaseEvent event(String eventId) {
        OrderCancelledEvent e = new OrderCancelledEvent();
        e.setEventId(eventId);
        e.setBizNo("O1");
        return e;
    }

    @Test
    void 首次消费_bodyEventId非空_返回true并按body落库() {
        when(mqConsumeMapper.insertIgnore(any(MqConsumeLog.class))).thenReturn(1);

        assertTrue(new MqConsumeSupport(mqConsumeMapper)
                .firstConsume("cg1", MqTopics.ORDER_CANCELLED, event("EV1")));

        ArgumentCaptor<MqConsumeLog> captor = ArgumentCaptor.forClass(MqConsumeLog.class);
        verify(mqConsumeMapper).insertIgnore(captor.capture());
        assertEquals("EV1", captor.getValue().getEventId());
    }

    @Test
    void 重复消费_返回false() {
        when(mqConsumeMapper.insertIgnore(any(MqConsumeLog.class))).thenReturn(0);

        assertFalse(new MqConsumeSupport(mqConsumeMapper)
                .firstConsume("cg1", MqTopics.ORDER_CANCELLED, event("EV1")));
    }

    @Test
    void r427_bodyEventId空白_回退上下文eventId并回填事件体() {
        MqConsumeContext.bind(new MqConsumeContext(
                "noid:shop_order_cancelled:O1", MqTopics.ORDER_CANCELLED, "O1", "rmq-1", true));
        BaseEvent e = event("  ");
        when(mqConsumeMapper.insertIgnore(any(MqConsumeLog.class))).thenReturn(1);

        assertTrue(new MqConsumeSupport(mqConsumeMapper)
                .firstConsume("cg1", MqTopics.ORDER_CANCELLED, e));
        assertEquals("noid:shop_order_cancelled:O1", e.getEventId());
    }

    @Test
    void r427_body与上下文双空_failFast不写库() {
        BaseEvent e = event(null);

        assertThrows(IllegalStateException.class, () -> new MqConsumeSupport(mqConsumeMapper)
                .firstConsume("cg1", MqTopics.ORDER_CANCELLED, e));
        verify(mqConsumeMapper, never()).insertIgnore(any());
    }
}
