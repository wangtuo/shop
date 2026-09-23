package com.shop.marketing.mq;

import com.shop.api.user.event.UserRegisteredEvent;
import com.shop.common.constant.MqTopics;
import com.shop.marketing.gift.NewUserGiftService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 卡 B6：注册监听器契约——topic/group/type 正确；eventId 幂等模板驱动发券；
 * 重复事件（runOnce 不再回调 action）零发券；异常向上抛由框架重试。
 */
@ExtendWith(MockitoExtension.class)
class UserRegisteredListenerTest {

    @Mock
    private MqConsumeTemplate consumeTemplate;
    @Mock
    private NewUserGiftService newUserGiftService;
    @InjectMocks
    private UserRegisteredListener listener;

    private UserRegisteredEvent event(long userId, String eventId) {
        UserRegisteredEvent e = new UserRegisteredEvent();
        e.setUserId(userId);
        e.setEventId(eventId);
        return e;
    }

    @Test
    void 订阅契约正确() {
        assertEquals(MqTopics.USER_REGISTERED, listener.topic());
        assertEquals("cg_marketing_user_registered", listener.consumerGroup());
        assertEquals(UserRegisteredEvent.class, listener.type());
    }

    @Test
    void 首次事件经幂等模板发券() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(3)).run();
            return null;
        }).when(consumeTemplate).runOnce(any(), any(), any(), any());

        listener.onMessage(event(1001L, "evt-1"));

        verify(newUserGiftService).issueGift(1001L);
        verify(consumeTemplate).runOnce(eq("evt-1"), eq(MqTopics.USER_REGISTERED), eq("1001"), any());
    }

    @Test
    void 重复事件幂等模板不回调则零发券() {
        // runOnce 命中 eventId 流水：不执行 action
        listener.onMessage(event(1001L, "evt-dup"));
        verify(newUserGiftService, never()).issueGift(1001L);
    }

    @Test
    void 发券异常向上抛出由Broker重试() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(3)).run();
            return null;
        }).when(consumeTemplate).runOnce(any(), any(), any(), any());
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(newUserGiftService).issueGift(1001L);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> listener.onMessage(event(1001L, "evt-2")));
    }
}
