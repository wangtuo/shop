package com.shop.user.mq.service.impl;

import com.shop.api.product.event.CommentCreatedEvent;
import com.shop.api.user.dto.GrantPointsCommand;
import com.shop.api.user.dto.GrowthCommand;
import com.shop.api.user.enums.GrowthScene;
import com.shop.api.user.enums.PointsScene;
import com.shop.common.constant.MqTopics;
import com.shop.user.account.service.AccountService;
import com.shop.user.account.service.GrowthService;
import com.shop.user.member.PointsCalc;
import com.shop.user.mq.service.MqConsumeService;
import com.shop.user.mq.service.UserBehaviorMqService;
import com.shop.user.profile.entity.User;
import com.shop.user.profile.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评价/晒单事件消费（cg_user_comment_created，B6-b）。
 *
 * <p>behaviorType=1 评价：积分 20（带图 30，日限 100 由 grantPoints clamp）+ 成长值 +10；
 * behaviorType=2 晒单：仅成长值 +20，<b>不发积分</b>（design 2.2.2 无晒单积分规则）。
 *
 * <p>幂等：t_user_mq_consume(event_id) 闸门 + 积分/成长值流水 bizNo UK 双保险，
 * 消费流水与业务入账在同一事务。
 */
@Service
@RequiredArgsConstructor
public class UserBehaviorMqServiceImpl implements UserBehaviorMqService {

    private static final Logger log = LoggerFactory.getLogger(UserBehaviorMqServiceImpl.class);

    public static final String GROUP_COMMENT_CREATED = "cg_user_comment_created";

    /** 行为类型：1 评价 2 晒单（与商品域 C42 契约一致） */
    private static final int BEHAVIOR_COMMENT = 1;
    private static final int BEHAVIOR_SHOW_ORDER = 2;

    /** 评价成长值 +10/单 */
    private static final int COMMENT_GROWTH = 10;
    /** 晒单成长值 +20/单 */
    private static final int SHOW_ORDER_GROWTH = 20;

    private final MqConsumeService consumeService;
    private final AccountService accountService;
    private final GrowthService growthService;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleCommentCreated(CommentCreatedEvent event) {
        if (event == null || event.getCommentId() == null) {
            log.warn("COMMENT_CREATED 事件体为空或缺少 commentId，ACK 跳过");
            return;
        }
        String bizNo = "COMMENT:" + event.getCommentId();
        if (!consumeService.beginConsume(MqTopics.COMMENT_CREATED,
                GROUP_COMMENT_CREATED, bizNo)) {
            return;
        }
        Integer behaviorType = event.getBehaviorType();
        if (behaviorType == null
                || (behaviorType != BEHAVIOR_COMMENT && behaviorType != BEHAVIOR_SHOW_ORDER)) {
            // 未知类型属终态毒丸：warn 后 ACK，不抛异常避免无限重试
            log.warn("未知评价行为类型，ACK 跳过 eventId={} commentId={} behaviorType={}",
                    event.getEventId(), event.getCommentId(), behaviorType);
            return;
        }
        User user = event.getUserId() == null ? null : userMapper.selectById(event.getUserId());
        if (user == null) {
            log.warn("COMMENT_CREATED 对应用户不存在，跳过激励发放 userId={} commentId={}",
                    event.getUserId(), event.getCommentId());
            return;
        }
        if (behaviorType == BEHAVIOR_COMMENT) {
            // 评价积分：20/带图 30；日限 100 打满时 grantPoints 内部 clamp 返回 0，不报错
            long points = PointsCalc.commentPoints(Boolean.TRUE.equals(event.getWithImage()));
            accountService.grantPoints(GrantPointsCommand.builder()
                    .userId(event.getUserId())
                    .bizNo(bizNo)
                    .points(points)
                    .scene(PointsScene.COMMENT)
                    .build());
            // 成长值无日限，照常 +10
            growthService.addGrowth(GrowthCommand.builder()
                    .userId(event.getUserId())
                    .bizNo(bizNo)
                    .growth(COMMENT_GROWTH)
                    .scene(GrowthScene.COMMENT)
                    .build());
        } else {
            // 晒单：仅成长值 +20，不发积分（勘误红线）
            growthService.addGrowth(GrowthCommand.builder()
                    .userId(event.getUserId())
                    .bizNo("SHOW:" + event.getCommentId())
                    .growth(SHOW_ORDER_GROWTH)
                    .scene(GrowthScene.SHOW_ORDER)
                    .build());
        }
    }
}
