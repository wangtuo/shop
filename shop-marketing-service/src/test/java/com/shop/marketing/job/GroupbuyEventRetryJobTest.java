package com.shop.marketing.job;

import com.shop.api.order.client.OrderClient;
import com.shop.common.result.Result;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.groupbuy.entity.GroupbuyEventTodo;
import com.shop.marketing.activity.groupbuy.mapper.GroupbuyEventTodoMapper;
import com.shop.marketing.activity.groupbuy.service.GroupbuyEventAcceptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B1 补偿 Job：status=2 退避到期重试成功翻 1；退避未到跳过；失败计数 +1，达上限停止（P0 告警在 service）。
 */
@ExtendWith(MockitoExtension.class)
class GroupbuyEventRetryJobTest {

    @Mock private GroupbuyEventTodoMapper todoMapper;
    @Mock private OrderClient orderClient;
    @Mock private IdGenerator idGenerator;

    private GroupbuyEventAcceptService service;
    private GroupbuyEventRetryJob job;

    private static PlatformTransactionManager noopTx() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() {
                return new Object();
            }
            @Override protected void doBegin(Object transaction,
                                            org.springframework.transaction.TransactionDefinition definition) {
                // no-op
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
        service = new GroupbuyEventAcceptService(todoMapper, orderClient, idGenerator, noopTx());
        job = new GroupbuyEventRetryJob(service);
    }

    private GroupbuyEventTodo row(long id, int opType, int status, int retryCount, LocalDateTime updateTime) {
        GroupbuyEventTodo t = new GroupbuyEventTodo();
        t.setId(id);
        t.setEventId("e" + id);
        t.setGroupNo("G1");
        t.setActivityId(11L);
        t.setOrderNo("O" + id);
        t.setUserId(9L);
        t.setLeaderFlag(0);
        t.setOpType(opType);
        t.setHandleStatus(status);
        t.setRetryCount(retryCount);
        t.setUpdateTime(updateTime);
        return t;
    }

    @Test
    @DisplayName("status=2 退避到期：TRADE 就绪后重试成功，翻 1")
    void retrySucceedsAfterBackoff() {
        GroupbuyEventTodo r = row(100L, 3, GroupbuyEventTodo.STATUS_RETRY, 1, LocalDateTime.now().minusMinutes(3));
        when(todoMapper.selectRetryable(GroupbuyEventTodo.MAX_RETRY,
                GroupbuyEventAcceptService.RETRY_BATCH_LIMIT)).thenReturn(List.of(r));
        when(orderClient.markGroupSucceeded(any())).thenReturn(Result.success());
        when(orderClient.renewGroupPayDeadline(any())).thenReturn(Result.success());
        when(todoMapper.markHandled(100L)).thenReturn(1);

        job.retry();

        verify(orderClient).markGroupSucceeded(any());
        verify(orderClient).renewGroupPayDeadline(any());
        verify(todoMapper).markHandled(100L);
        verify(todoMapper, never()).markRetry(anyLong(), anyInt());
    }

    @Test
    @DisplayName("status=2 退避未到（retry_count×60s）：本轮跳过不打订单域")
    void backoffNotElapsedSkips() {
        GroupbuyEventTodo r = row(101L, 4, GroupbuyEventTodo.STATUS_RETRY, 2, LocalDateTime.now());
        when(todoMapper.selectRetryable(anyInt(), anyInt())).thenReturn(List.of(r));

        int n = service.retryDue(100);

        assertEquals(0, n);
        verifyNoInteractions(orderClient);
        verify(todoMapper, never()).markHandled(anyLong());
    }

    @Test
    @DisplayName("重试仍失败：retry_count +1 保持 status=2，等下轮（第 9 次失败时不吞异常，达上限停止并告警）")
    void retryFailureIncrementsCount() {
        GroupbuyEventTodo r = row(102L, 3, GroupbuyEventTodo.STATUS_RETRY, 9, LocalDateTime.now().minusHours(1));
        when(todoMapper.selectRetryable(anyInt(), anyInt())).thenReturn(List.of(r));
        when(orderClient.markGroupSucceeded(any()))
                .thenThrow(new RuntimeException("connection refused"));
        when(todoMapper.markRetry(102L, GroupbuyEventTodo.MAX_RETRY)).thenReturn(1);

        int n = service.retryDue(100);

        assertEquals(0, n);
        verify(todoMapper).markRetry(102L, GroupbuyEventTodo.MAX_RETRY);
        verify(todoMapper, never()).markHandled(anyLong());
    }
}
