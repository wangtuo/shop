package com.shop.marketing.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.marketing.enums.GroupbuyOpType;
import com.shop.api.marketing.event.GroupbuyEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.Groupbuy;
import com.shop.marketing.activity.entity.GroupbuyMember;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.GroupbuyMapper;
import com.shop.marketing.activity.mapper.GroupbuyMemberMapper;
import com.shop.marketing.activity.support.ActivityRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 拼团服务（design.md 4.4）：2/3/5/10 人团、24h 成团时效、每人每活动限 1 次、
 * 团长优惠随活动价由订单域上送、失败由事件驱动自动退款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupbuyService {

    /** 成团有效期 24 小时 */
    public static final int GROUP_EXPIRE_HOURS = 24;

    private final ActivityMapper activityMapper;
    private final GroupbuyMapper groupbuyMapper;
    private final GroupbuyMemberMapper memberMapper;
    private final OutboxPublisher outboxPublisher;
    private final IdGenerator idGenerator;

    /**
     * 下单锁定：有未满团则参团，否则团长开团；满员即时成团。
     * 同一用户同一活动只能参与 1 次（唯一索引兜底）。
     */
    @Transactional(rollbackFor = Exception.class)
    public String openOrJoin(Long userId, Long activityId, String orderNo) {
        Activity activity = activityMapper.selectById(activityId);
        validateOngoing(activity);
        Long existed = memberMapper.selectCount(new LambdaQueryWrapper<GroupbuyMember>()
                .eq(GroupbuyMember::getActivityId, activityId)
                .eq(GroupbuyMember::getUserId, userId));
        if (existed != null && existed > 0) {
            throw new BizException(ErrorCode.LIMIT_PURCHASE, "同一拼团活动只能参与一次");
        }
        Groupbuy open = groupbuyMapper.selectOne(new LambdaQueryWrapper<Groupbuy>()
                .eq(Groupbuy::getActivityId, activityId)
                .eq(Groupbuy::getStatus, 0)
                .gt(Groupbuy::getExpireTime, LocalDateTime.now())
                .apply("joined_count < required_people")
                .last("LIMIT 1"));
        if (open == null) {
            return openGroup(activity, userId, orderNo);
        }
        return joinGroup(open, userId, orderNo);
    }

    private String openGroup(Activity activity, Long userId, String orderNo) {
        int required = requiredPeople(activity);
        Groupbuy group = new Groupbuy();
        String groupNo = "G" + idGenerator.nextIdString();
        group.setGroupNo(groupNo);
        group.setActivityId(activity.getId());
        group.setLeaderUserId(userId);
        group.setRequiredPeople(required);
        group.setJoinedCount(1);
        group.setStatus(0);
        group.setExpireTime(LocalDateTime.now().plusHours(GROUP_EXPIRE_HOURS));
        groupbuyMapper.insert(group);
        insertMember(groupNo, activity.getId(), userId, orderNo, 1);
        sendEvent(activity.getId(), groupNo, userId, orderNo, GroupbuyOpType.OPEN.getCode(), required, 1);
        return groupNo;
    }

    private String joinGroup(Groupbuy group, Long userId, String orderNo) {
        insertMember(group.getGroupNo(), group.getActivityId(), userId, orderNo, 0);
        if (groupbuyMapper.joinGroup(group.getGroupNo()) == 0) {
            throw new BizException(ErrorCode.CONFLICT, "拼团人数已满，请改开新团");
        }
        sendEvent(group.getActivityId(), group.getGroupNo(), userId, orderNo,
                GroupbuyOpType.JOIN.getCode(), group.getRequiredPeople(), 0);
        Groupbuy fresh = groupbuyMapper.selectOne(new LambdaQueryWrapper<Groupbuy>()
                .eq(Groupbuy::getGroupNo, group.getGroupNo()));
        if (fresh != null && fresh.getJoinedCount() >= fresh.getRequiredPeople()
                && groupbuyMapper.markSuccess(group.getGroupNo(), LocalDateTime.now()) > 0) {
            memberMapper.markSuccess(group.getGroupNo());
            // B1：逐成员发成团事件（消费端按成员 orderNo 调订单域续期/转待发货），leaderFlag 从成员记录透传
            List<GroupbuyMember> members = memberMapper.selectList(new LambdaQueryWrapper<GroupbuyMember>()
                    .eq(GroupbuyMember::getGroupNo, group.getGroupNo()));
            for (GroupbuyMember m : members) {
                sendEvent(group.getActivityId(), group.getGroupNo(), m.getUserId(), m.getOrderNo(),
                        GroupbuyOpType.SUCCESS.getCode(), group.getRequiredPeople(),
                        m.getLeaderFlag() == null ? 0 : m.getLeaderFlag());
            }
        }
        return group.getGroupNo();
    }

    private void insertMember(String groupNo, Long activityId, Long userId, String orderNo, int leaderFlag) {
        GroupbuyMember member = new GroupbuyMember();
        member.setGroupNo(groupNo);
        member.setActivityId(activityId);
        member.setUserId(userId);
        member.setOrderNo(orderNo);
        member.setLeaderFlag(leaderFlag);
        member.setStatus(0);
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.LIMIT_PURCHASE, "同一拼团活动只能参与一次");
        }
    }

    /** 订单取消/超时：成员退出并回减人数（成团后退出由售后期流程处理，此处仅释放参团中记录）。 */
    @Transactional(rollbackFor = Exception.class)
    public void release(String orderNo) {
        GroupbuyMember member = memberMapper.selectOne(new LambdaQueryWrapper<GroupbuyMember>()
                .eq(GroupbuyMember::getOrderNo, orderNo));
        if (member == null || member.getStatus() != 0) {
            return;
        }
        if (memberMapper.markLeave(orderNo) == 0) {
            return;
        }
        groupbuyMapper.leaveGroup(member.getGroupNo());
    }

    /**
     * 24h 定时扫描（与 MQ 延时双保险）：超期未满团标记失败，逐成员发失败事件，
     * 由订单/支付域驱动自动退款。返回失败团数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int expireGroups() {
        LocalDateTime now = LocalDateTime.now();
        List<Groupbuy> expired = groupbuyMapper.selectList(new LambdaQueryWrapper<Groupbuy>()
                .eq(Groupbuy::getStatus, 0)
                .lt(Groupbuy::getExpireTime, now));
        int failed = 0;
        for (Groupbuy group : expired) {
            // 逐团条件更新：影响 0 行说明扫描期间恰好满员成团，跳过
            if (groupbuyMapper.markExpired(group.getGroupNo(), now) == 0) {
                continue;
            }
            List<GroupbuyMember> members = memberMapper.selectList(new LambdaQueryWrapper<GroupbuyMember>()
                    .eq(GroupbuyMember::getGroupNo, group.getGroupNo())
                    .eq(GroupbuyMember::getStatus, 0));
            for (GroupbuyMember m : members) {
                sendEvent(group.getActivityId(), group.getGroupNo(), m.getUserId(), m.getOrderNo(),
                        GroupbuyOpType.FAIL.getCode(), group.getRequiredPeople(),
                        m.getLeaderFlag() == null ? 0 : m.getLeaderFlag());
                memberMapper.markLeave(m.getOrderNo());
            }
            failed++;
        }
        return failed;
    }

    private int requiredPeople(Activity activity) {
        ActivityRule rule = JsonUtils.fromJson(activity.getRuleJson(), ActivityRule.class);
        int required = rule == null || rule.getRequiredPeople() == null ? 2 : rule.getRequiredPeople();
        if (required != 2 && required != 3 && required != 5 && required != 10) {
            throw new BizException(ErrorCode.PARAM_INVALID, "拼团人数仅支持 2/3/5/10");
        }
        return required;
    }

    private void validateOngoing(Activity activity) {
        if (activity == null || activity.getStatus() != 1) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "拼团活动不存在或已下架");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "不在拼团活动时间内");
        }
    }

    private void sendEvent(Long activityId, String groupNo, Long userId, String orderNo, int type,
                           int required, int leaderFlag) {
        GroupbuyEvent event = GroupbuyEvent.builder()
                .activityId(activityId).groupNo(groupNo).userId(userId).orderNo(orderNo)
                .type(type).requiredPeople(required).leaderFlag(leaderFlag).build();
        event.setBizNo(orderNo);
        // P1-1：事件随团/成员状态同事务提交，relay 至少一次投递，消费端按 eventId + groupNo/orderNo/type 双幂等
        outboxPublisher.publish(MqTopics.GROUPBUY_EVENT, String.valueOf(type), event, orderNo);
    }
}
