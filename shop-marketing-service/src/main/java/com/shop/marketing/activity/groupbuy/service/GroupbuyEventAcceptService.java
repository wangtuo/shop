package com.shop.marketing.activity.groupbuy.service;

import com.shop.api.marketing.enums.GroupbuyOpType;
import com.shop.api.marketing.event.GroupbuyEvent;
import com.shop.api.order.client.OrderClient;
import com.shop.api.order.dto.GroupFailedCommand;
import com.shop.api.order.dto.GroupPayRenewCommand;
import com.shop.api.order.dto.GroupSucceedCommand;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.groupbuy.entity.GroupbuyEventTodo;
import com.shop.marketing.activity.groupbuy.mapper.GroupbuyEventTodoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 拼团成团/失败事件受理（B1 营销半卡）。
 *
 * <p>状态机：事件先落 t_groupbuy_event_todo（0 待处理）→ 调订单域 C25 接口
 * → 成功置 1 已通知；失败（404/Feign 异常/Result 失败，TRADE 未就绪期间的常态）置 2 待重试并抛出，
 * 由 MQ 重投 + {@code GroupbuyEventRetryJob} 扫表双兜底，绝不伪成功吞单。
 *
 * <p>双幂等：{@code eventId}（uk_event_id + t_marketing_mq_consume）与
 * {@code groupNo + orderNo + opType}（同团同成员同操作只通知一次，订单域接口自身亦按 orderNo 幂等）。
 *
 * <p>营销域职责边界：type=3 时拼团无券/积分占用可回补，团长价已在锁定快照记账，此处只做资源确认留痕；
 * 订单状态推进/续期/关单/退款编排全部由订单域负责，营销不直接调 PayClient。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupbuyEventAcceptService {

    /** 成团后未支付团员续期 30 分钟（C25） */
    public static final long GROUP_RENEW_PLUS_SECONDS = 30 * 60L;
    /** 失败重试指数退避基数：retry_count × 60s */
    public static final long RETRY_BACKOFF_BASE_SECONDS = 60L;
    /** 单次补偿批量上限（限频，配合 2 分钟 cron） */
    public static final int RETRY_BATCH_LIMIT = 100;

    private final GroupbuyEventTodoMapper todoMapper;
    private final OrderClient orderClient;
    private final IdGenerator idGenerator;
    private final PlatformTransactionManager transactionManager;

    /**
     * MQ 消费入口（被 MqConsumeTemplate.runOnce 包裹，外层另有 mq_consume eventId 幂等）。
     * 待办落库/状态翻转全部使用独立事务，保证 Feign 失败回滚不丢待办。
     */
    public void accept(GroupbuyEvent e) {
        GroupbuyEventTodo todo = loadOrCreate(e);
        if (todo.getHandleStatus() != null && todo.getHandleStatus() == GroupbuyEventTodo.STATUS_DONE) {
            return;
        }
        // 第二道幂等：同团同成员同类型已通知成功（eventId 不同的重复投递）直接短路
        GroupbuyEventTodo same = todoMapper.selectByBizKey(e.getGroupNo(), e.getOrderNo(), e.getType());
        if (same != null && !same.getEventId().equals(e.getEventId())
                && same.getHandleStatus() != null
                && same.getHandleStatus() == GroupbuyEventTodo.STATUS_DONE) {
            log.info("拼团事件业务幂等命中，跳过重复通知 groupNo={} orderNo={} type={}",
                    e.getGroupNo(), e.getOrderNo(), e.getType());
            return;
        }
        dispatchAndUpdate(e, todo.getId());
    }

    /**
     * 补偿 Job 入口：扫描 0/2 状态待办，2 状态按 retry_count×60s 指数退避，达 {@link GroupbuyEventTodo#MAX_RETRY}
     * 停止并 P0 告警。返回本次成功通知条数。
     */
    public int retryDue(int limit) {
        List<GroupbuyEventTodo> rows = todoMapper.selectRetryable(GroupbuyEventTodo.MAX_RETRY, limit);
        LocalDateTime now = LocalDateTime.now();
        int succeeded = 0;
        for (GroupbuyEventTodo row : rows) {
            int retryCount = row.getRetryCount() == null ? 0 : row.getRetryCount();
            if (row.getHandleStatus() == GroupbuyEventTodo.STATUS_RETRY
                    && row.getUpdateTime() != null
                    && now.isBefore(row.getUpdateTime().plusSeconds(retryCount * RETRY_BACKOFF_BASE_SECONDS))) {
                continue;
            }
            GroupbuyEvent e = toEvent(row);
            try {
                dispatch(e);
                todoMapper.markHandled(row.getId());
                succeeded++;
                log.info("拼团事件补偿通知成功 eventId={} groupNo={} orderNo={} type={} retryCount={}",
                        e.getEventId(), e.getGroupNo(), e.getOrderNo(), e.getType(), retryCount);
            } catch (Exception ex) {
                int next = retryCount + 1;
                todoMapper.markRetry(row.getId(), GroupbuyEventTodo.MAX_RETRY);
                if (next >= GroupbuyEventTodo.MAX_RETRY) {
                    log.error("P0 拼团事件补偿已达上限 {} 次仍失败，需人工介入 eventId={} groupNo={} orderNo={} type={}",
                            GroupbuyEventTodo.MAX_RETRY, e.getEventId(), e.getGroupNo(), e.getOrderNo(), e.getType(), ex);
                } else {
                    log.warn("拼团事件补偿失败，置待办重试 eventId={} groupNo={} orderNo={} type={} retryCount={}: {}",
                            e.getEventId(), e.getGroupNo(), e.getOrderNo(), e.getType(), next, ex.getMessage());
                }
            }
        }
        return succeeded;
    }

    private void dispatchAndUpdate(GroupbuyEvent e, Long todoId) {
        try {
            dispatch(e);
        } catch (Exception ex) {
            // 独立事务落 2：TRADE 未就绪（404）/网络失败时待办必须在外层 MQ 事务回滚后仍然保留，禁止伪成功
            try {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
                tx.executeWithoutResult(s -> todoMapper.markRetry(todoId, GroupbuyEventTodo.MAX_RETRY));
            } catch (Exception markEx) {
                log.error("拼团事件待办置失败状态异常 eventId={} groupNo={} orderNo={}",
                        e.getEventId(), e.getGroupNo(), e.getOrderNo(), markEx);
            }
            log.warn("拼团事件通知订单域失败，已落待办待重试，消息将交 MQ 重投 eventId={} groupNo={} orderNo={} type={}: {}",
                    e.getEventId(), e.getGroupNo(), e.getOrderNo(), e.getType(), ex.getMessage());
            throw ex instanceof BizException ? (BizException) ex
                    : new BizException(ErrorCode.SYSTEM_ERROR, "拼团事件通知订单域失败：" + ex.getMessage());
        }
        todoMapper.markHandled(todoId);
    }

    /**
     * 订单域对接（C25）。type=3：营销资源确认 + markGroupSucceeded（已付款转待发货/未付款挂成团标记）
     * + renewGroupPayDeadline（未付款 +30 分钟，已付款由订单域 CAS 拒绝为 no-op）；
     * type=4：markGroupFailed（关单/退款编排全部在订单域，营销不碰 PayClient）。
     */
    private void dispatch(GroupbuyEvent e) {
        int type = e.getType() == null ? 0 : e.getType();
        if (type == GroupbuyOpType.SUCCESS.getCode()) {
            confirmMarketingResource(e);
            ensureSuccess(orderClient.markGroupSucceeded(GroupSucceedCommand.builder()
                    .orderNo(e.getOrderNo()).groupNo(e.getGroupNo()).build()), "markGroupSucceeded", e);
            ensureSuccess(orderClient.renewGroupPayDeadline(GroupPayRenewCommand.builder()
                    .groupNo(e.getGroupNo()).orderNo(e.getOrderNo())
                    .plusSeconds(GROUP_RENEW_PLUS_SECONDS).build()), "renewGroupPayDeadline", e);
        } else if (type == GroupbuyOpType.FAIL.getCode()) {
            ensureSuccess(orderClient.markGroupFailed(GroupFailedCommand.builder()
                    .orderNo(e.getOrderNo()).groupNo(e.getGroupNo()).build()), "markGroupFailed", e);
        } else {
            // 1 开团 / 2 参团不触发订单动作（订阅 tag=3||4 已过滤，防御性 no-op）
            log.debug("拼团事件 type={} 无需通知订单域 eventId={}", type, e.getEventId());
        }
    }

    /** 拼团营销资源确认：拼团与券/积分互斥，锁定期无券预核销/积分占用；团长价优惠以下单锁定快照为准，此处仅留痕。 */
    private void confirmMarketingResource(GroupbuyEvent e) {
        log.info("拼团成团购营销资源确认 groupNo={} orderNo={} leaderFlag={}（无券/积分占用需回补，团长价以锁定快照为准）",
                e.getGroupNo(), e.getOrderNo(), e.getLeaderFlag());
    }

    private void ensureSuccess(Result<?> result, String action, GroupbuyEvent e) {
        if (result == null || !result.isSuccess()) {
            String code = result == null ? "null" : String.valueOf(result.getCode());
            String msg = result == null ? "响应为空" : result.getMessage();
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "拼团事件通知订单域失败 action=" + action + " code=" + code + " msg=" + msg
                            + " groupNo=" + e.getGroupNo() + " orderNo=" + e.getOrderNo());
        }
    }

    /**
     * 待办先于订单域调用落库，且必须独立事务提交：accept 运行在 MqConsumeTemplate 的事务里，
     * Feign 失败外层回滚时 status=0/2 行仍须保留，故显式 REQUIRES_NEW（不依赖自调用会失效的 @Transactional）。
     */
    public GroupbuyEventTodo loadOrCreate(GroupbuyEvent e) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return tx.execute(status -> {
            GroupbuyEventTodo existed = todoMapper.selectByEventId(e.getEventId());
            if (existed != null) {
                return existed;
            }
            GroupbuyEventTodo todo = new GroupbuyEventTodo();
            todo.setId(idGenerator.nextId());
            todo.setEventId(e.getEventId());
            todo.setGroupNo(e.getGroupNo());
            todo.setActivityId(e.getActivityId());
            todo.setOrderNo(e.getOrderNo());
            todo.setUserId(e.getUserId());
            todo.setLeaderFlag(e.getLeaderFlag() == null ? 0 : e.getLeaderFlag());
            todo.setOpType(e.getType());
            todo.setHandleStatus(GroupbuyEventTodo.STATUS_INIT);
            todo.setRetryCount(0);
            try {
                todoMapper.insertIgnore(todo);
            } catch (DuplicateKeyException ignore) {
                // 并发落同一 eventId，回查拿现存行
            }
            GroupbuyEventTodo current = todoMapper.selectByEventId(e.getEventId());
            if (current != null) {
                return current;
            }
            throw new BizException(ErrorCode.SYSTEM_ERROR, "拼团事件待办落库失败 eventId=" + e.getEventId());
        });
    }

    private GroupbuyEvent toEvent(GroupbuyEventTodo row) {
        GroupbuyEvent e = GroupbuyEvent.builder()
                .activityId(row.getActivityId())
                .groupNo(row.getGroupNo())
                .userId(row.getUserId())
                .orderNo(row.getOrderNo())
                .type(row.getOpType())
                .leaderFlag(row.getLeaderFlag())
                .build();
        e.setEventId(row.getEventId());
        e.setBizNo(row.getOrderNo());
        return e;
    }
}
