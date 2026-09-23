package com.shop.settlement.mq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * MQ 消费幂等流水（t_sett_mq_consume），event_id 唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sett_mq_consume")
public class SettMqConsume extends BaseEntity {

    private String eventId;
    private String topic;
    private String consumerGroup;
    private String bizNo;
    /** 1 成功 0 失败 */
    private Integer status;
}
