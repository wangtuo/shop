package com.shop.settlement.mq.service;

import com.shop.framework.mq.MqConsumeContext;
import com.shop.settlement.mq.mapper.MqConsumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * MQ 消费幂等服务：以 eventId 落消费流水（INSERT IGNORE）。
 * 必须在业务事务内调用，业务回滚则消费登记一并回滚，支持 Broker 重试。
 */
@Service
@RequiredArgsConstructor
public class MqConsumeService {

    private final MqConsumeMapper mqConsumeMapper;

    /**
     * 尝试登记消费。
     *
     * @return true 首次消费需处理；false 重复 eventId 直接 ACK
     */
    public boolean tryRecord(String eventId, String topic, String consumerGroup, String bizNo) {
        return mqConsumeMapper.insertIgnore(resolveEventId(eventId, topic), topic, consumerGroup,
                bizNo == null ? "" : bizNo) > 0;
    }

    /**
     * R4-27：eventId 优先取消息体信封值；空白时回退框架 EventNormalizer 绑定的上下文
     * eventId（header / noid 合成），避免空键写库；两源皆空（非 MQ 线程误调用）fail-fast。
     */
    static String resolveEventId(String bodyEventId, String topic) {
        if (bodyEventId != null && !bodyEventId.isBlank()) {
            return bodyEventId;
        }
        String ctx = MqConsumeContext.currentEventId();
        if (ctx == null || ctx.isBlank()) {
            throw new IllegalStateException("消费事件 eventId 为空且无 MQ 上下文，topic=" + topic);
        }
        return ctx;
    }
}
