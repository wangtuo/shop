package com.shop.common.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ 事件基类。事件体必须字段自包含，消费者不允许回查生产者库。
 */
@Data
public abstract class BaseEvent implements Serializable {

    /** 事件唯一 ID（幂等键） */
    private String eventId = UUID.randomUUID().toString().replace("-", "");

    /** 事件产生时间 */
    private LocalDateTime occurredAt = LocalDateTime.now();

    /** 业务主键（订单号/支付单号等），作为消息 key 用于顺序与排查 */
    private String bizNo;
}
