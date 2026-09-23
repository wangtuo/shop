package com.shop.framework.outbox;

import com.shop.common.util.JsonUtils;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.entity.OutboxMessage;
import com.shop.framework.outbox.mapper.OutboxMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * outbox 事件登记：<b>必须在业务本地事务内调用</b>。
 *
 * <p>事件行随业务数据一同 commit / rollback；投递交给 {@link OutboxRelayJob}，
 * 因此应用在 commit 前崩溃不会产生幽灵消息，commit 后崩溃不会丢消息。
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxMapper outboxMapper;
    private final IdGenerator idGenerator;

    /** 登记普通事件（事务内）。 */
    public void publish(String topic, String tag, Object payload, String bizKey) {
        enqueue(topic, tag, payload, bizKey, 0L);
    }

    /** 登记延时事件（事务内）：relay 在 delaySeconds 后投递。 */
    public void publishDelay(String topic, String tag, Object payload, String bizKey, long delaySeconds) {
        enqueue(topic, tag, payload, bizKey, Math.max(0L, delaySeconds));
    }

    private void enqueue(String topic, String tag, Object payload, String bizKey, long delaySeconds) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("outbox topic 不能为空");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // fail-fast：outbox 的全部价值在于与业务同事务。事务外登记请直接使用 MqProducer。
            throw new IllegalStateException("OutboxPublisher 必须在事务内调用（事件与业务状态同提交/回滚）");
        }
        LocalDateTime now = LocalDateTime.now();
        OutboxMessage msg = new OutboxMessage();
        msg.setId(idGenerator.nextId());
        msg.setTopic(topic);
        msg.setTag(tag == null ? "" : tag);
        msg.setBizKey(bizKey == null ? "" : bizKey);
        msg.setBodyJson(JsonUtils.toJson(payload));
        msg.setDeliverAt(delaySeconds > 0 ? now.plusSeconds(delaySeconds) : now);
        msg.setStatus(0);
        msg.setRetryCount(0);
        msg.setLastError("");
        msg.setCreateTime(now);
        msg.setUpdateTime(now);
        outboxMapper.insert(msg);
    }
}
