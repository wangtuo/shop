package com.shop.aftersale.mq.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MQ 消费流水（event_id 唯一去重）。
 */
@Data
@TableName("t_aftersale_mq_consume")
public class MqConsumeLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String topic;
    private String bizNo;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
