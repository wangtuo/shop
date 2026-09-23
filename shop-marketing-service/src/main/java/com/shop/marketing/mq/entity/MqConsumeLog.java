package com.shop.marketing.mq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** MQ 消费幂等流水（t_marketing_mq_consume）。 */
@Data
@TableName("t_marketing_mq_consume")
public class MqConsumeLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String eventId;
    private String topic;
    private String bizNo;
    /** 0成功 1失败待重试 */
    private Integer status;
    /** 逻辑删除：0 否 1 是（append-only 幂等表，恒为 0；补齐与 t_marketing_mq_consume.deleted 的列映射） */
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
