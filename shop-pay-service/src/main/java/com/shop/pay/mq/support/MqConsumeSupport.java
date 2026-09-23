package com.shop.pay.mq.support;

import com.shop.common.util.JsonUtils;
import com.shop.common.model.BaseEvent;
import com.shop.framework.mq.MqConsumeContext;
import com.shop.pay.mq.entity.MqConsumeLog;
import com.shop.pay.mq.mapper.MqConsumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MQ 消费幂等支撑：INSERT IGNORE 消费流水，重复 eventId 返回 false（消费方直接 ACK）。
 * 消费处理与流水插入须在同一事务（CONTRACTS.md §5、WAVE2_BRIEF §3.4）。
 */
@Component
@RequiredArgsConstructor
public class MqConsumeSupport {

    private final MqConsumeMapper mqConsumeMapper;

    /**
     * @return true 首次消费；false 重复事件，直接 ACK
     */
    public boolean firstConsume(String consumerGroup, String topic, BaseEvent event) {
        // R4-27：body eventId 空白时回退框架归一化上下文（header / noid 合成），双空 fail-fast
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            String ctx = MqConsumeContext.currentEventId();
            if (ctx == null || ctx.isBlank()) {
                throw new IllegalStateException("消费事件 eventId 为空且无 MQ 上下文，topic=" + topic);
            }
            event.setEventId(ctx);
        }
        MqConsumeLog log = new MqConsumeLog();
        log.setConsumerGroup(consumerGroup);
        log.setTopic(topic);
        log.setEventId(event.getEventId());
        log.setBizNo(event.getBizNo());
        log.setConsumeStatus(1);
        log.setPayload(JsonUtils.toJson(event));
        return mqConsumeMapper.insertIgnore(log) > 0;
    }
}
