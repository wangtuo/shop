package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 每日积分获取汇总（t_user_points_daily）：评价 100/天、分享 20/天、签到 50/天。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_points_daily")
public class UserPointsDaily extends BaseEntity {

    private Long userId;

    private LocalDate statDate;

    /** PointsScene */
    private Integer scene;

    /** 当日该场景已获取积分 */
    private Long earnedPoints;
}
