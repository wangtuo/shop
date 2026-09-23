package com.shop.marketing.mq;

import com.shop.api.marketing.event.GroupbuyEvent;
import com.shop.api.order.client.OrderClient;
import com.shop.common.exception.BizException;
import com.shop.common.result.Result;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.groupbuy.entity.GroupbuyEventTodo;
import com.shop.marketing.activity.groupbuy.mapper.GroupbuyEventTodoMapper;
import com.shop.marketing.activity.groupbuy.service.GroupbuyEventAcceptService;
import com.shop.marketing.mq.mapper.MqConsumeLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B1：tag 3||4 订阅元数据、双幂等（mq_consume eventId + todo）、type=1/2 no-op、
 * 订单域失败落 todo=2 并抛出交 MQ 重试（不吞单不伪成功）。
 */
@ExtendWith(MockitoExtension.class)
class GroupbuyEventListenerTest {

    @Mock private MqConsumeLogMapper mqLogMapper;
    @Mock private GroupbuyEventTodoMapper todoMapper;
    @Mock private OrderClient orderClient;
    @Mock private IdGenerator idGenerator;

    private GroupbuyEventListener listener;

    private static PlatformTransactionManager noopTx() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() {
                return new Object();
            }
            @Override protected void doBegin(Object transaction,
                                            org.springframework.transaction.TransactionDefinition definition) {
                // 单测无真实数据源，REQUIRES_NEW 空操作
            }
            @Override protected void doCommit(DefaultTransactionStatus status) {
                // no-op
            }
            @Override protected void doRollback(DefaultTransactionStatus status) {
                // no-op
            }
        };
    }

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(idGenerator.nextId()).thenReturn(1L);
        MqConsumeTemplate consumeTemplate = new MqConsumeTemplate(mqLogMapper, idGenerator);
        GroupbuyEventAcceptService acceptService = new GroupbuyEventAcceptService(
                todoMapper, orderClient, idGenerator, noopTx());
        listener = new GroupbuyEventListener(consumeTemplate, acceptService);
    }

    private GroupbuyEvent event(String eid, int type, String orderNo) {
        GroupbuyEvent e = GroupbuyEvent.builder()
                .activityId(11L).groupNo("G1").userId(9L).orderNo(orderNo).type(type)
                .leaderFlag(type == 1 ? 1 : 0).build();
        e.setEventId(eid);
        e.setBizNo(orderNo);
        return e;
    }

    private GroupbuyEventTodo todo(String eid, int opType, String orderNo) {
        GroupbuyEventTodo t = new GroupbuyEventTodo();
        t.setId(100L);
        t.setEventId(eid);
        t.setGroupNo("G1");
        t.setActivityId(11L);
        t.setOrderNo(orderNo);
        t.setUserId(9L);
        t.setLeaderFlag(0);
        t.setOpType(opType);
        t.setHandleStatus(GroupbuyEventTodo.STATUS_INIT);
        t.setRetryCount(0);
        return t;
    }

    @Test
    @DisplayName("订阅元数据：topic/tag=3||4/消费组/事件类型")
    void metadata() {
        assertEquals("shop_groupbuy_event", listener.topic());
        assertEquals("3||4", listener.tag());
        assertEquals("cg_marketing_groupbuy", listener.consumerGroup());
        assertEquals(GroupbuyEvent.class, listener.type());
    }

    @Test
    @DisplayName("type=3 成团：落 todo=0，调 markGroupSucceeded + renew 各一次，成功置 1")
    void successEvent() {
        GroupbuyEvent e = event("e3", 3, "O3");
        when(mqLogMapper.insertIgnore(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(todoMapper.selectByEventId("e3")).thenReturn(null, todo("e3", 3, "O3"));
        when(todoMapper.insertIgnore(any())).thenReturn(1);
        when(todoMapper.selectByBizKey("G1", "O3", 3)).thenReturn(null);
        when(orderClient.markGroupSucceeded(any())).thenReturn(Result.success());
        when(orderClient.renewGroupPayDeadline(any())).thenReturn(Result.success());

        listener.onMessage(e);

        verify(orderClient).markGroupSucceeded(any());
        verify(orderClient).renewGroupPayDeadline(any());
        verify(todoMapper).markHandled(100L);
    }

    @Test
    @DisplayName("type=4 失败：仅调 markGroupFailed 一次，成功置 1")
    void failedEvent() {
        GroupbuyEvent e = event("e4", 4, "O4");
        when(mqLogMapper.insertIgnore(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(todoMapper.selectByEventId("e4")).thenReturn(null, todo("e4", 4, "O4"));
        when(todoMapper.insertIgnore(any())).thenReturn(1);
        when(todoMapper.selectByBizKey("G1", "O4", 4)).thenReturn(null);
        when(orderClient.markGroupFailed(any())).thenReturn(Result.success());

        listener.onMessage(e);

        verify(orderClient).markGroupFailed(any());
        verify(orderClient, never()).markGroupSucceeded(any());
        verify(todoMapper).markHandled(100L);
    }

    @Test
    @DisplayName("type=1/2 开团参团：落消费记录后 no-op，不触订单域、不写 todo")
    void openJoinNoop() {
        when(mqLogMapper.insertIgnore(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        listener.onMessage(event("e1", 1, "O1"));
        listener.onMessage(event("e2", 2, "O2"));
        verifyNoInteractions(orderClient);
        verify(todoMapper, never()).insertIgnore(any());
    }

    @Test
    @DisplayName("mq_consume 幂等：同一 eventId 重放只通知一次（第二次流水插入 0 行直接跳过）")
    void duplicateEventIdSkipped() {
        GroupbuyEvent e = event("e3", 3, "O3");
        when(mqLogMapper.insertIgnore(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1).thenReturn(0);
        when(todoMapper.selectByEventId("e3")).thenReturn(null, todo("e3", 3, "O3"));
        when(todoMapper.insertIgnore(any())).thenReturn(1);
        when(todoMapper.selectByBizKey("G1", "O3", 3)).thenReturn(null);
        when(orderClient.markGroupSucceeded(any())).thenReturn(Result.success());
        when(orderClient.renewGroupPayDeadline(any())).thenReturn(Result.success());

        listener.onMessage(e);
        listener.onMessage(e);

        verify(orderClient).markGroupSucceeded(any());
        verify(orderClient).renewGroupPayDeadline(any());
    }

    @Test
    @DisplayName("todo 已置 1 的重放：不重复通知订单域")
    void alreadyDoneSkipped() {
        GroupbuyEvent e = event("e3", 3, "O3");
        GroupbuyEventTodo done = todo("e3", 3, "O3");
        done.setHandleStatus(GroupbuyEventTodo.STATUS_DONE);
        when(mqLogMapper.insertIgnore(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(todoMapper.selectByEventId("e3")).thenReturn(done);

        listener.onMessage(e);

        verifyNoInteractions(orderClient);
        verify(todoMapper, never()).markHandled(anyLong());
    }

    @Test
    @DisplayName("TRADE 未就绪（Feign 404）：todo 置 2 待重试且异常抛出交 MQ 重投，不伪成功")
    void orderDomainFailureMarksRetryAndRethrows() {
        GroupbuyEvent e = event("e3", 3, "O3");
        when(mqLogMapper.insertIgnore(anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
        when(todoMapper.selectByEventId("e3")).thenReturn(null, todo("e3", 3, "O3"));
        when(todoMapper.insertIgnore(any())).thenReturn(1);
        when(todoMapper.selectByBizKey("G1", "O3", 3)).thenReturn(null);
        when(orderClient.markGroupSucceeded(any()))
                .thenThrow(new RuntimeException("404 Not Found: /inner/order/group/succeed"));
        when(todoMapper.markRetry(anyLong(), anyInt())).thenReturn(1);

        assertThrows(BizException.class, () -> listener.onMessage(e));

        verify(todoMapper).markRetry(100L, GroupbuyEventTodo.MAX_RETRY);
        verify(todoMapper, never()).markHandled(anyLong());
    }
}
