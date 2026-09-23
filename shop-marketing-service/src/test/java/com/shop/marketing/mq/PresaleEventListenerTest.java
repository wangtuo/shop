package com.shop.marketing.mq;

import com.shop.api.marketing.enums.PresaleOpType;
import com.shop.api.marketing.event.PresaleEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.marketing.activity.service.PresaleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PRESALE_EVENT 自消费（cg_marketing_presale_cancel）。
 * R4-25 回归：延时行键带 #final 后缀；延时消息体缺 eventId 时必须回退框架归一化
 * 上下文 eventId，保证延时行与取消后即时 CANCEL 行各自独立幂等。
 */
@ExtendWith(MockitoExtension.class)
class PresaleEventListenerTest {

    @Mock private MqConsumeTemplate consumeTemplate;
    @Mock private PresaleService presaleService;

    private PresaleEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new PresaleEventListener(consumeTemplate, presaleService);
    }

    @AfterEach
    void tearDown() {
        MqConsumeContext.clear();
    }

    private PresaleEvent event(Integer type, String eventId) {
        PresaleEvent e = new PresaleEvent();
        e.setActivityId(9L);
        e.setUserId(7L);
        e.setOrderNo("O1");
        e.setType(type);
        e.setEventId(eventId);
        return e;
    }

    private void stubRunOnceCaptures() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(3)).run();
            return null;
        }).when(consumeTemplate).runOnce(anyString(), anyString(), anyString(), any(Runnable.class));
    }

    @Test
    @DisplayName("非CANCEL事件_直接忽略_不进幂等模板不取消")
    void onMessage_nonCancel_ignored() {
        listener.onMessage(event(PresaleOpType.DEPOSIT_PAID.getCode(), "EV-LOCK-1"));
        verifyNoInteractions(consumeTemplate);
        verifyNoInteractions(presaleService);
    }

    @Test
    @DisplayName("CANCEL带body eventId_以body eventId入幂等模板并执行cancelByOrderNo")
    void onMessage_cancel_withBodyEventId() {
        stubRunOnceCaptures();
        listener.onMessage(event(PresaleOpType.CANCEL.getCode(), "EV-CANCEL-1"));
        verify(consumeTemplate).runOnce(eq("EV-CANCEL-1"), eq(MqTopics.PRESALE_EVENT), eq("O1"),
                any(Runnable.class));
        verify(presaleService).cancelByOrderNo("O1");
    }

    @Test
    @DisplayName("R4-25_CANCEL消息体缺eventId_回退MqConsumeContext归一化eventId(含#final键)")
    void onMessage_cancel_blankEventId_fallsBackToContext() {
        stubRunOnceCaptures();
        // 模拟 EventNormalizer 在线程上下文绑定的归一化结果（keys=bizKey，即带 #final 的延时行键）
        MqConsumeContext.bind(new MqConsumeContext("noid:shop_presale_event:O1#final",
                MqTopics.PRESALE_EVENT, "O1#final", "rmq-msg-1", true));

        listener.onMessage(event(PresaleOpType.CANCEL.getCode(), "  "));

        verify(consumeTemplate).runOnce(eq("noid:shop_presale_event:O1#final"),
                eq(MqTopics.PRESALE_EVENT), eq("O1"), any(Runnable.class));
        verify(presaleService).cancelByOrderNo("O1");
    }

    @Test
    @DisplayName("CANCEL_消费模板判定重复(rows=0)_不执行cancelByOrderNo")
    void onMessage_cancel_duplicate_skipsAction() {
        // 默认未 stub runOnce → action 不执行，模拟 insertIgnore 命中重复
        listener.onMessage(event(PresaleOpType.CANCEL.getCode(), "EV-DUP"));
        verify(consumeTemplate).runOnce(eq("EV-DUP"), eq(MqTopics.PRESALE_EVENT), eq("O1"),
                any(Runnable.class));
        verify(presaleService, never()).cancelByOrderNo(anyString());
    }

    @Test
    @DisplayName("topic/consumerGroup/type声明正确")
    void descriptor() {
        assertEquals(MqTopics.PRESALE_EVENT, listener.topic());
        assertEquals("cg_marketing_presale_cancel", listener.consumerGroup());
        assertEquals(com.shop.api.marketing.event.PresaleEvent.class, listener.type());
    }
}
