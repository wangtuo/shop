package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 拼团成员（每人每活动限 1 次，唯一索引保证）：0参团中 1已成团 2已退出。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_groupbuy_member")
public class GroupbuyMember extends BaseEntity {

    private String groupNo;
    private Long activityId;
    private Long userId;
    private String orderNo;
    private Integer leaderFlag;
    private Integer status;
}
