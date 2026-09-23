package com.shop.user.mq.listener;

import com.shop.api.product.event.CommentCreatedEvent;
import com.shop.common.constant.MqTopics;
import com.shop.framework.mq.MqListener;
import com.shop.user.mq.service.UserBehaviorMqService;
import com.shop.user.mq.service.impl.UserBehaviorMqServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * COMMENT_CREATED 消费者（cg_user_comment_created）：
 * 评价发积分（20/带图 30，日限 100）+ 成长值 +10；晒单仅成长值 +20（无积分）。
 */
@Component
@RequiredArgsConstructor
public class CommentCreatedListener implements MqListener<CommentCreatedEvent> {

    private final UserBehaviorMqService mqService;

    @Override
    public String topic() {
        return MqTopics.COMMENT_CREATED;
    }

    @Override
    public String consumerGroup() {
        return UserBehaviorMqServiceImpl.GROUP_COMMENT_CREATED;
    }

    @Override
    public Class<CommentCreatedEvent> type() {
        return CommentCreatedEvent.class;
    }

    @Override
    public void onMessage(CommentCreatedEvent message) {
        mqService.handleCommentCreated(message);
    }
}
