package com.shop.product.mq;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品域 MQ 消费流水。event_id 唯一，消费处理与本流水插入在同一事务。
 */
@Data
@TableName("t_product_mq_consume")
public class MqConsumeRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 事件唯一 ID（幂等键） */
    private String eventId;

    /** Topic */
    private String topic;

    /** 业务单号 */
    private String bizNo;

    /** 处理状态：1 成功 */
    private Integer status;

    /** 消费时间 */
    private LocalDateTime createTime;
}
