package com.shop.pay.mq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MQ 消费幂等流水（t_pay_mq_consume）：消费处理与流水插入同一事务，重复 eventId 直接 ACK。
 * consumeStatus 1 成功 2 失败。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pay_mq_consume")
public class MqConsumeLog extends BaseEntity {

    private String topic;
    /** 事件 ID（消费组内唯一，幂等键） */
    private String eventId;
    private String bizNo;
    private String consumerGroup;
    private Integer consumeStatus;
    private String payload;
    private String errorMsg;
}
