package com.shop.user.mq;

import com.shop.framework.id.IdGenerator;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.user.mq.entity.UserMqConsume;
import com.shop.user.mq.mapper.UserMqConsumeMapper;
import com.shop.user.mq.service.impl.MqConsumeServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1-D：消费闸门 eventId 统一取自 {@link MqConsumeContext}（框架 EventNormalizer 已保证非空，
 * 含 noid 合成键），本服务不再私拼/私校 eventId。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MqConsumeServiceImplTest {

    @Mock
    private UserMqConsumeMapper consumeMapper;
    @Mock
    private IdGenerator idGenerator;
    @InjectMocks
    private MqConsumeServiceImpl consumeService;

    @AfterEach
    void tearDown() {
        MqConsumeContext.clear();
    }

    private void bind(String eventId) {
        MqConsumeContext.bind(new MqConsumeContext(eventId, "shop_comment_created",
                "COMMENT:9001", "msg-1", eventId.startsWith("noid:")));
    }

    @Test
    void 首次消费_流水eventId取框架上下文_返回true() {
        bind("evt-framework-1");
        when(consumeMapper.selectCount(any())).thenReturn(0L);
        when(idGenerator.nextId()).thenReturn(5001L);

        boolean first = consumeService.beginConsume(
                "shop_comment_created", "cg_user_comment_created", "COMMENT:9001");

        assertTrue(first);
        ArgumentCaptor<UserMqConsume> captor = ArgumentCaptor.forClass(UserMqConsume.class);
        verify(consumeMapper).insert(captor.capture());
        assertEquals("evt-framework-1", captor.getValue().getEventId());
        assertEquals("shop_comment_created", captor.getValue().getTopic());
        assertEquals("cg_user_comment_created", captor.getValue().getConsumerGroup());
        assertEquals("COMMENT:9001", captor.getValue().getBizNo());
    }

    @Test
    void 框架合成noid键_同样落库幂等_不抛异常() {
        bind("noid:shop_comment_created:COMMENT:9002");
        when(consumeMapper.selectCount(any())).thenReturn(0L);

        boolean first = consumeService.beginConsume(
                "shop_comment_created", "cg_user_comment_created", "COMMENT:9002");

        assertTrue(first);
        ArgumentCaptor<UserMqConsume> captor = ArgumentCaptor.forClass(UserMqConsume.class);
        verify(consumeMapper).insert(captor.capture());
        assertEquals("noid:shop_comment_created:COMMENT:9002", captor.getValue().getEventId());
    }

    @Test
    void eventId已存在_直接ACK返回false() {
        bind("evt-dup");
        when(consumeMapper.selectCount(any())).thenReturn(1L);

        assertFalse(consumeService.beginConsume(
                "shop_comment_created", "cg_user_comment_created", "COMMENT:9003"));
        verify(consumeMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void 并发插入UK冲突_返回false() {
        bind("evt-race");
        when(consumeMapper.selectCount(any())).thenReturn(0L);
        when(consumeMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_event_id"));

        assertFalse(consumeService.beginConsume(
                "shop_comment_created", "cg_user_comment_created", "COMMENT:9004"));
    }
}
