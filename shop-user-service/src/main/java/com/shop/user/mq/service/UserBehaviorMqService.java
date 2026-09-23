package com.shop.user.mq.service;

import com.shop.api.product.event.CommentCreatedEvent;

/**
 * 用户行为事件消费处理（评价/晒单激励，B6-b）。
 */
public interface UserBehaviorMqService {

    /**
     * 评价/晒单创建：评价发积分 + 成长值；晒单仅发成长值。以 eventId + bizNo 双重幂等。
     */
    void handleCommentCreated(CommentCreatedEvent event);
}
