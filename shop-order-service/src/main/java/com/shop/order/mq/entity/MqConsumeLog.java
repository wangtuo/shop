package com.shop.order.mq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MQ 消费幂等流水（t_order_mq_consume）。
 */
@Data
@TableName("t_order_mq_consume")
public class MqConsumeLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventId;
    private String topic;
    private String bizNo;
    /** 0 处理中 1 成功 */
    private Integer status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
