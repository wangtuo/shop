package com.shop.user.mq;

import com.shop.api.product.event.CommentCreatedEvent;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.enums.GrowthScene;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.constant.MqTopics;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.GrowthService;
import com.shop.user.mq.service.MqConsumeService;
import com.shop.user.mq.service.impl.UserBehaviorMqServiceImpl;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * COMMENT_CREATED 消费（B6-b）：
 * 评价 20/带图 30 积分 + 10 成长值（bizNo=COMMENT:id）；晒单仅 20 成长值（bizNo=SHOW:id）无积分；
 * 重复 eventId no-op；未知类型/用户不存在 warn ACK；日限打满积分 0 但成长值照发。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserBehaviorMqServiceImplTest {

    @Mock
    private MqConsumeService consumeService;
    @Mock
    private AccountService accountService;
    @Mock
    private GrowthService growthService;
    @Mock
    private UserMapper userMapper;
    @InjectMocks
    private UserBehaviorMqServiceImpl mqService;

    private CommentCreatedEvent event(String eventId, long commentId, Long userId,
                                      int behaviorType, Boolean withImage) {
        CommentCreatedEvent e = CommentCreatedEvent.builder()
                .commentId(commentId).userId(userId).behaviorType(behaviorType).withImage(withImage)
                .build();
        e.setEventId(eventId);
        return e;
    }

    private void gatePass() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Test
    void 评价_不带图_20积分加10成长值_bizNo为COMMENT前缀() {
        gatePass();
        when(userMapper.selectById(1001L)).thenReturn(user(1001L));
        when(accountService.grantPoints(any())).thenReturn(20L);

        mqService.handleCommentCreated(event("evt-c1", 9001L, 1001L, 1, false));

        verify(consumeService).beginConsume(eq(MqTopics.COMMENT_CREATED),
                eq(UserBehaviorMqServiceImpl.GROUP_COMMENT_CREATED), eq("COMMENT:9001"));

        ArgumentCaptor<GrantPointsCommand> pointsCaptor = ArgumentCaptor.forClass(GrantPointsCommand.class);
        verify(accountService).grantPoints(pointsCaptor.capture());
        assertEquals("COMMENT:9001", pointsCaptor.getValue().getBizNo());
        assertEquals(20L, pointsCaptor.getValue().getPoints());
        assertEquals(PointsScene.COMMENT, pointsCaptor.getValue().getScene());
        assertEquals(1001L, pointsCaptor.getValue().getUserId());

        ArgumentCaptor<GrowthCommand> growthCaptor = ArgumentCaptor.forClass(GrowthCommand.class);
        verify(growthService).addGrowth(growthCaptor.capture());
        assertEquals("COMMENT:9001", growthCaptor.getValue().getBizNo());
        assertEquals(10, growthCaptor.getValue().getGrowth());
        assertEquals(GrowthScene.COMMENT, growthCaptor.getValue().getScene());
    }

    @Test
    void 评价_带图_30积分加10成长值() {
        gatePass();
        when(userMapper.selectById(1001L)).thenReturn(user(1001L));
        when(accountService.grantPoints(any())).thenReturn(30L);

        mqService.handleCommentCreated(event("evt-c2", 9002L, 1001L, 1, true));

        ArgumentCaptor<GrantPointsCommand> pointsCaptor = ArgumentCaptor.forClass(GrantPointsCommand.class);
        verify(accountService).grantPoints(pointsCaptor.capture());
        assertEquals(30L, pointsCaptor.getValue().getPoints());
        verify(growthService).addGrowth(any(GrowthCommand.class));
    }

    @Test
    void 晒单_仅20成长值_bizNo为SHOW前缀_不发积分() {
        gatePass();
        when(userMapper.selectById(1001L)).thenReturn(user(1001L));

        mqService.handleCommentCreated(event("evt-s1", 9003L, 1001L, 2, true));

        verify(accountService, never()).grantPoints(any());
        ArgumentCaptor<GrowthCommand> growthCaptor = ArgumentCaptor.forClass(GrowthCommand.class);
        verify(growthService).addGrowth(growthCaptor.capture());
        assertEquals("SHOW:9003", growthCaptor.getValue().getBizNo());
        assertEquals(20, growthCaptor.getValue().getGrowth());
        assertEquals(GrowthScene.SHOW_ORDER, growthCaptor.getValue().getScene());
    }

    @Test
    void 重复eventId_闸门no_op_不发放任何激励() {
        when(consumeService.beginConsume(anyString(), anyString(), anyString()))
                .thenReturn(false);

        mqService.handleCommentCreated(event("evt-dup", 9004L, 1001L, 1, false));

        verify(accountService, never()).grantPoints(any());
        verify(growthService, never()).addGrowth(any());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void 未知行为类型_warn后ACK_不抛异常不发放() {
        gatePass();

        assertDoesNotThrow(() ->
                mqService.handleCommentCreated(event("evt-x", 9005L, 1001L, 9, false)));

        verify(accountService, never()).grantPoints(any());
        verify(growthService, never()).addGrowth(any());
        verify(userMapper, never()).selectById(any());
    }

    @Test
    void 用户不存在_warn跳过_不抛异常不发放() {
        gatePass();
        when(userMapper.selectById(404L)).thenReturn(null);

        assertDoesNotThrow(() ->
                mqService.handleCommentCreated(event("evt-u", 9006L, 404L, 1, false)));

        verify(accountService, never()).grantPoints(any());
        verify(growthService, never()).addGrowth(any());
    }

    @Test
    void 评价日限打满_grantPoints返回0_成长值仍照发10() {
        gatePass();
        when(userMapper.selectById(1001L)).thenReturn(user(1001L));
        when(accountService.grantPoints(any())).thenReturn(0L);

        mqService.handleCommentCreated(event("evt-cap", 9007L, 1001L, 1, false));

        ArgumentCaptor<GrowthCommand> growthCaptor = ArgumentCaptor.forClass(GrowthCommand.class);
        verify(growthService).addGrowth(growthCaptor.capture());
        assertEquals(10, growthCaptor.getValue().getGrowth());
        assertEquals("COMMENT:9007", growthCaptor.getValue().getBizNo());
    }
}
