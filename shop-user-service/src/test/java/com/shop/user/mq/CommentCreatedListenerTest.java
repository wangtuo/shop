package com.shop.user.mq;

import com.shop.api.product.event.CommentCreatedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.user.mq.listener.CommentCreatedListener;
import com.shop.user.mq.service.UserBehaviorMqService;
import com.shop.user.mq.service.impl.UserBehaviorMqServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;

/**
 * CommentCreatedListener 透传：topic/group/type 元数据正确，onMessage 原样委托 mqService。
 */
@ExtendWith(MockitoExtension.class)
class CommentCreatedListenerTest {

    @Mock
    private UserBehaviorMqService mqService;
    @InjectMocks
    private CommentCreatedListener listener;

    @Test
    void 元数据与委托正确() {
        org.junit.jupiter.api.Assertions.assertEquals(MqTopics.COMMENT_CREATED, listener.topic());
        org.junit.jupiter.api.Assertions.assertEquals(
                UserBehaviorMqServiceImpl.GROUP_COMMENT_CREATED, listener.consumerGroup());
        org.junit.jupiter.api.Assertions.assertEquals(CommentCreatedEvent.class, listener.type());

        CommentCreatedEvent event = CommentCreatedEvent.builder().commentId(1L).userId(2L).build();
        listener.onMessage(event);
        verify(mqService).handleCommentCreated(event);
        assertSame(CommentCreatedEvent.class, listener.type());
    }
}
