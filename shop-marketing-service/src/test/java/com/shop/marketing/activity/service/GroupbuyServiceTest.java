package com.shop.marketing.activity.service;

import com.shop.common.exception.BizException;
import com.shop.common.util.JsonUtils;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.Groupbuy;
import com.shop.marketing.activity.entity.GroupbuyMember;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.GroupbuyMapper;
import com.shop.marketing.activity.mapper.GroupbuyMemberMapper;
import com.shop.marketing.activity.support.ActivityRule;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 拼团：开团/参团/成团/24h 失败，每人限 1 次。 */
@ExtendWith(MockitoExtension.class)
class GroupbuyServiceTest {

    @Mock private ActivityMapper activityMapper;
    @Mock private GroupbuyMapper groupbuyMapper;
    @Mock private GroupbuyMemberMapper memberMapper;
    @Mock private OutboxPublisher outboxPublisher;
    @Mock private IdGenerator idGenerator;

    private GroupbuyService service;

    @BeforeEach
    void setUp() {
        service = new GroupbuyService(activityMapper, groupbuyMapper, memberMapper, outboxPublisher, idGenerator);
    }

    private Activity activity(int required) {
        Activity a = new Activity();
        a.setId(11L);
        a.setType(11);
        a.setStatus(1);
        a.setStartTime(LocalDateTime.now().minusHours(1));
        a.setEndTime(LocalDateTime.now().plusHours(10));
        ActivityRule rule = new ActivityRule();
        rule.setRequiredPeople(required);
        rule.setLeaderDiscountFen(500L);
        a.setRuleJson(JsonUtils.toJson(rule));
        return a;
    }

    private Groupbuy group(String no, int joined, int required) {
        Groupbuy g = new Groupbuy();
        g.setId(1L);
        g.setGroupNo(no);
        g.setActivityId(11L);
        g.setLeaderUserId(1L);
        g.setJoinedCount(joined);
        g.setRequiredPeople(required);
        g.setStatus(0);
        g.setExpireTime(LocalDateTime.now().plusHours(20));
        return g;
    }

    @Test
    @DisplayName("无未满团时团长开团，发 OPEN 事件，24h 后过期")
    void openOrJoin_无团_开团() {
        when(activityMapper.selectById(11L)).thenReturn(activity(3));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(groupbuyMapper.selectOne(any())).thenReturn(null);
        when(idGenerator.nextIdString()).thenReturn("1001");

        String no = service.openOrJoin(1L, 11L, "O1");

        assertTrue(no.startsWith("G"));
        ArgumentCaptor<Groupbuy> captor = ArgumentCaptor.forClass(Groupbuy.class);
        verify(groupbuyMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getJoinedCount());
        assertEquals(3, captor.getValue().getRequiredPeople());
        assertTrue(captor.getValue().getExpireTime().isAfter(LocalDateTime.now().plusHours(23)));
        verify(memberMapper).insert(any(GroupbuyMember.class));
        verify(outboxPublisher).publish(any(), eq("1"), any(), eq("O1"));
    }

    @Test
    @DisplayName("有未满团则参团，发 JOIN 事件且人数 +1")
    void openOrJoin_有团_参团() {
        when(activityMapper.selectById(11L)).thenReturn(activity(3));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(groupbuyMapper.selectOne(any())).thenReturn(group("G1", 1, 3), group("G1", 2, 3));
        when(groupbuyMapper.joinGroup("G1")).thenReturn(1);

        service.openOrJoin(2L, 11L, "O2");

        verify(groupbuyMapper).joinGroup("G1");
        verify(outboxPublisher).publish(any(), eq("2"), any(), eq("O2"));
        verify(groupbuyMapper, never()).markSuccess(any(), any());
    }

    @Test
    @DisplayName("参团后满员：即时成团，成员转已成团，逐成员发 SUCCESS 事件并透传 leaderFlag")
    void openOrJoin_满员_成团并逐成员发事件() {
        when(activityMapper.selectById(11L)).thenReturn(activity(3));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(groupbuyMapper.selectOne(any())).thenReturn(group("G1", 2, 3), group("G1", 3, 3));
        when(groupbuyMapper.joinGroup("G1")).thenReturn(1);
        when(groupbuyMapper.markSuccess(eq("G1"), any())).thenReturn(1);
        GroupbuyMember leader = new GroupbuyMember();
        leader.setGroupNo("G1");
        leader.setActivityId(11L);
        leader.setUserId(1L);
        leader.setOrderNo("O1");
        leader.setLeaderFlag(1);
        GroupbuyMember joiner = new GroupbuyMember();
        joiner.setGroupNo("G1");
        joiner.setActivityId(11L);
        joiner.setUserId(3L);
        joiner.setOrderNo("O3");
        joiner.setLeaderFlag(0);
        when(memberMapper.selectList(any())).thenReturn(List.of(leader, joiner));

        service.openOrJoin(3L, 11L, "O3");

        verify(memberMapper).markSuccess("G1");
        ArgumentCaptor<com.shop.api.marketing.event.GroupbuyEvent> captor =
                ArgumentCaptor.forClass(com.shop.api.marketing.event.GroupbuyEvent.class);
        verify(outboxPublisher, org.mockito.Mockito.times(2))
                .publish(any(), eq("3"), captor.capture(), any());
        java.util.Map<String, Integer> leaderByOrder = new java.util.HashMap<>();
        captor.getAllValues().forEach(ev -> leaderByOrder.put(ev.getOrderNo(), ev.getLeaderFlag()));
        assertEquals(2, leaderByOrder.size());
        assertEquals(1, leaderByOrder.get("O1"));
        assertEquals(0, leaderByOrder.get("O3"));
        assertTrue(captor.getAllValues().stream().allMatch(ev -> "G1".equals(ev.getGroupNo())));
    }

    @Test
    @DisplayName("同一用户同一活动仅可参与一次")
    void openOrJoin_已参与过_拒绝() {
        when(activityMapper.selectById(11L)).thenReturn(activity(3));
        when(memberMapper.selectCount(any())).thenReturn(1L);
        assertThrows(BizException.class, () -> service.openOrJoin(1L, 11L, "O9"));
        verify(groupbuyMapper, never()).insert(any());
    }

    @Test
    @DisplayName("非法成团人数（非2/3/5/10）拒绝开团")
    void openOrJoin_非法人数_拒绝() {
        when(activityMapper.selectById(11L)).thenReturn(activity(4));
        when(memberMapper.selectCount(any())).thenReturn(0L);
        when(groupbuyMapper.selectOne(any())).thenReturn(null);
        assertThrows(BizException.class, () -> service.openOrJoin(1L, 11L, "O9"));
    }

    @Test
    @DisplayName("订单取消：参团中成员退出，人数回减")
    void release_参团中_退出并回减人数() {
        GroupbuyMember m = new GroupbuyMember();
        m.setGroupNo("G1");
        m.setStatus(0);
        when(memberMapper.selectOne(any())).thenReturn(m);
        when(memberMapper.markLeave("O2")).thenReturn(1);

        service.release("O2");

        verify(groupbuyMapper).leaveGroup("G1");
    }

    @Test
    @DisplayName("24h 超时未满团：标记失败，逐成员发 FAIL 事件驱动退款")
    void expireGroups_到期未满_发失败事件() {
        Groupbuy failed = group("G1", 1, 3);
        GroupbuyMember member = new GroupbuyMember();
        member.setGroupNo("G1");
        member.setActivityId(11L);
        member.setUserId(1L);
        member.setOrderNo("O1");
        member.setStatus(0);
        when(groupbuyMapper.selectList(any())).thenReturn(List.of(failed));
        when(groupbuyMapper.markExpired(eq("G1"), any())).thenReturn(1);
        when(memberMapper.selectList(any())).thenReturn(List.of(member));
        when(memberMapper.markLeave("O1")).thenReturn(1);

        int n = service.expireGroups();

        assertEquals(1, n);
        verify(outboxPublisher).publish(any(), eq("4"), any(), eq("O1"));
    }
}
