package com.shop.marketing.activity.groupbuy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 拼团事件受理待办（t_groupbuy_event_todo，marketing V5 D1）。
 *
 * <p>TRADE C25 三接口（/inner/order/group/renew|succeed|failed）未就绪/调用失败期间的落地锚点：
 * 成团/失败事件先落本表再调订单域，失败置 {@link #STATUS_RETRY} 由 {@code GroupbuyEventRetryJob} 兜底。
 */
@Data
@TableName("t_groupbuy_event_todo")
public class GroupbuyEventTodo implements Serializable {

    public static final int STATUS_INIT = 0;
    /** 已通知订单域成功 */
    public static final int STATUS_DONE = 1;
    /** 调用订单域失败，待 MQ 重投/补偿 Job 重试 */
    public static final int STATUS_RETRY = 2;

    /** 最大补偿次数，超过后停止重试并 P0 告警人工介入 */
    public static final int MAX_RETRY = 10;

    @TableId(type = IdType.INPUT)
    private Long id;
    /** GroupbuyEvent.eventId（uk_event_id，第一道幂等） */
    private String eventId;
    private String groupNo;
    private Long activityId;
    private String orderNo;
    private Long userId;
    private Integer leaderFlag;
    /** 3 成团 4 失败 */
    private Integer opType;
    private Integer handleStatus;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
