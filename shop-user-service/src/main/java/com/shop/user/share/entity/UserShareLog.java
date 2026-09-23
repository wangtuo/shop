package com.shop.user.share.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户分享行为流水（t_user_share_log，V3__user_gap.sql）。
 * request_no 为客户端幂等键（UK）；points_earned 为日限 clamp 后实际入账积分（可能为 0）。
 *
 * <p>注意：该表仅有 create_time，无 update_time/deleted 列，故不继承 BaseEntity。
 */
@Data
@TableName("t_user_share_log")
public class UserShareLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 客户端幂等号 */
    private String requestNo;

    /** 分享目标类型：1 商品 2 活动 3 其他 */
    private Integer targetType;

    /** 目标 ID（可空） */
    private String targetId;

    /** 本次实际入账积分（日限 clamp 后可能为 0） */
    private Long pointsEarned;

    private LocalDateTime shareTime;

    private LocalDateTime createTime;
}
