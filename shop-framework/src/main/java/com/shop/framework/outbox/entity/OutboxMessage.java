package com.shop.framework.outbox.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表（transactional outbox）。
 *
 * <p>业务状态变更与事件写入在<strong>同一本地事务</strong>提交，杜绝「事务回滚但 MQ 已发」的幽灵消息；
 * 后台投递任务 {@code OutboxRelayJob} 按 id 顺序投递，成功后 CAS 置已投递，崩溃可续投，
 * 消费端按 bizNo/事件幂等去重（至少一次）。
 *
 * <p>每个业务库各持一份（DDL 见 {@code sql/common/V3__outbox.sql}）。
 */
@Data
@TableName("t_mq_outbox")
public class OutboxMessage {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String topic;
    private String tag;
    private String bizKey;
    /** 事件 JSON（与 MqProducer 直送同一序列化口径） */
    private String bodyJson;

    /** 最早可投递时间；普通消息为创建时刻，延时消息为 now + delay */
    private LocalDateTime deliverAt;

    /** 0 待投递 / 1 已投递 / 2 超过重试上限挂起（慢车道有限次自动重排，超限人工/对账介入） */
    private Integer status;
    private Integer retryCount;
    /** 进入挂起(status=2)的累计次数；慢车道自动重排据此封顶 */
    private Integer suspendCount;
    private String lastError;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
