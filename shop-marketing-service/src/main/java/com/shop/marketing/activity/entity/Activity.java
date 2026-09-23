package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 营销活动主表（10秒杀 11拼团 12预售 13砍价 14抽奖，ActivityTypes）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_activity")
public class Activity extends BaseEntity {

    private Long merchantId;
    private Long shopId;
    private String name;
    private Integer type;
    /** 0下架 1进行中 2已结束 3已取消 */
    private Integer status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 差异化规则 JSON（拼团人数/团长优惠、预售定金膨胀尾款窗口、砍价、抽奖） */
    private String ruleJson;
    private Integer version;

    // ---- marketing V5 审核流（D1）----
    /** 审核态：0草稿 1待审核 2通过 3驳回，存量默认 2 */
    private Integer auditStatus;
    private LocalDateTime submitTime;
    private Long auditUserId;
    private LocalDateTime auditTime;
    private String auditRemark;
    /** 是否到点自动结束：0否 1是（W4-4 SeckillAutoEndJob 消费） */
    private Integer autoEnd;
}
