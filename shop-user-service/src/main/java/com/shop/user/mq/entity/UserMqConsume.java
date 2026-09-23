package com.shop.user.mq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MQ 消费幂等流水（t_user_mq_consume），event_id 唯一，业务处理与流水插入在同一事务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_mq_consume")
public class UserMqConsume extends BaseEntity {

    /** 事件唯一 ID（幂等键） */
    private String eventId;

    private String topic;

    /** 消费者组 cg_user_xxx */
    private String consumerGroup;

    /** 业务单号 */
    private String bizNo;

    /** 0 处理中 1 成功 */
    private Integer status;
}
