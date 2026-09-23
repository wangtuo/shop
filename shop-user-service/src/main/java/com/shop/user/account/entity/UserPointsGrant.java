package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 积分获取批次（t_user_points_grant）：365 天有效，消耗 FIFO 冲减最早到期批次。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_points_grant")
public class UserPointsGrant extends BaseEntity {

    private Long userId;

    /** 获取业务单号（幂等键） */
    private String bizNo;

    /** 获取场景（PointsScene） */
    private Integer scene;

    /** 本批次获取总数 */
    private Long pointsTotal;

    /** 剩余可用（FIFO 冲减） */
    private Long pointsRemaining;

    private LocalDateTime grantTime;

    /** 过期时间 = 获取时间 + 365 天 */
    private LocalDateTime expireTime;

    /** 0 可用 1 已过期清零 */
    private Integer status;

    @Version
    private Integer version;
}
