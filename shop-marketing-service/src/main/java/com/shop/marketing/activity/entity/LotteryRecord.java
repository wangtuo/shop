package com.shop.marketing.activity.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 抽奖记录。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_lottery_record")
public class LotteryRecord extends BaseEntity {

    private Long activityId;
    private Long userId;
    private Integer costPoints;
    private String prizeCode;
    private String prizeName;
    private String orderNo;
}
