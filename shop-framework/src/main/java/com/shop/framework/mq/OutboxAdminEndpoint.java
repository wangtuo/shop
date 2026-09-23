package com.shop.framework.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.framework.outbox.OutboxRelayJob;
import com.shop.framework.outbox.entity.OutboxMessage;
import com.shop.framework.outbox.mapper.OutboxMapper;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部 outbox 运维端点（C15 DLQ 台面最小版）。
 *
 * <p>默认<b>不注册</b>（{@code shop.mq.admin.enabled=false}）。开启后暴露：
 * <ul>
 *   <li>{@code GET /actuator/shopmqoutbox}：列出最近 50 条 status=2（挂起/死信）outbox；</li>
 *   <li>{@code POST /actuator/shopmqoutbox {"id":123}}：人工放行单条挂起消息，
 *       复用 {@link OutboxRelayJob#requeue(long)}（仅 2→0 生效）。</li>
 * </ul>
 *
 * <p>生产开放要求：仅内网 + 内部 token（networkpolicy / ingress 白名单），并在
 * management.endpoints.web.exposure.include 中显式加入 shopmqoutbox。
 * <b>不做批量自动重放</b>——首版只支持逐条人工 requeue + RUNBOOK。
 */
@Component
@Endpoint(id = "shopmqoutbox")
@ConditionalOnProperty(prefix = "shop.mq.admin", name = "enabled", havingValue = "true")
public class OutboxAdminEndpoint {

    private static final int LIST_LIMIT = 50;

    private final OutboxMapper outboxMapper;
    private final OutboxRelayJob outboxRelayJob;

    public OutboxAdminEndpoint(OutboxMapper outboxMapper, OutboxRelayJob outboxRelayJob) {
        this.outboxMapper = outboxMapper;
        this.outboxRelayJob = outboxRelayJob;
    }

    @ReadOperation
    public Map<String, Object> suspended() {
        List<OutboxMessage> rows = outboxMapper.selectList(new LambdaQueryWrapper<OutboxMessage>()
                .eq(OutboxMessage::getStatus, 2)
                .orderByDesc(OutboxMessage::getId)
                .last("LIMIT " + LIST_LIMIT));
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (OutboxMessage m : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("topic", m.getTopic());
            item.put("tag", m.getTag());
            item.put("bizKey", m.getBizKey());
            item.put("retryCount", m.getRetryCount());
            item.put("suspendCount", m.getSuspendCount());
            item.put("lastError", m.getLastError());
            item.put("updateTime", m.getUpdateTime() == null ? null : m.getUpdateTime().toString());
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", 2);
        result.put("limit", LIST_LIMIT);
        result.put("count", items.size());
        result.put("items", items);
        result.put("queriedAt", LocalDateTime.now().toString());
        return result;
    }

    /**
     * 人工放行：{@code POST /actuator/shopmqoutbox}，JSON 参数 {@code {"id": 123}}。
     * 返回 requeued=1 表示放行成功；0 表示消息不存在或当前非挂起态（幂等，不报错）。
     */
    @WriteOperation
    public Map<String, Object> requeue(Long id) {
        if (id == null) {
            throw new IllegalArgumentException("参数 id 必填");
        }
        int updated = outboxRelayJob.requeue(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("requeued", updated);
        return result;
    }
}
