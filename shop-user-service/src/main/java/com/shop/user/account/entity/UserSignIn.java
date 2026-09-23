package com.shop.user.account.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 签到记录（t_user_sign_in）：每人每天一条，UK(user_id, sign_date)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_sign_in")
public class UserSignIn extends BaseEntity {

    private Long userId;

    private LocalDate signDate;

    /** 连续签到第几天（中断从 1 重新计） */
    private Integer continuousDays;

    /** 本次获得积分（5/10/15/20/25/30/50，之后 50） */
    private Long pointsEarned;

    /** 本次获得成长值（每 7 天 +50） */
    private Integer growthEarned;
}
