package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 拼团团实例：0进行中 1成团 2失败（24h 时效）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_groupbuy")
public class Groupbuy extends BaseEntity {

    private String groupNo;
    private Long activityId;
    private Long leaderUserId;
    /** 2/3/5/10 */
    private Integer requiredPeople;
    private Integer joinedCount;
    private Integer status;
    private LocalDateTime expireTime;
    private LocalDateTime successTime;
    private Integer version;
}
