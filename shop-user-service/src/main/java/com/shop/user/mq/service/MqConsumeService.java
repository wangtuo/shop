package com.shop.user.mq.service;

/**
 * MQ 消费幂等服务：eventId 去重，业务处理与流水落库同事务。
 *
 * <p>eventId 统一由 shop-framework 的 {@code EventNormalizer} 在进入 listener 前解析
 * （消息 header → 事件体 eventId → {@code noid:topic:bizKey} 确定性合成）并绑定到
 * {@code MqConsumeContext}，本服务直接取 {@code MqConsumeContext.currentEventId()}，
 * 业务侧不再私拼/私校 eventId。
 */
public interface MqConsumeService {

    /**
     * 登记消费流水（幂等）。eventId 取自当前 MQ 消费上下文（{@code MqConsumeContext}）。
     *
     * @return true 首次消费（调用方继续处理）；false 重复 eventId（直接 ACK 跳过）
     */
    boolean beginConsume(String topic, String consumerGroup, String bizNo);
}
