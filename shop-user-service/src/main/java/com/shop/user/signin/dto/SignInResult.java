package com.shop.user.signin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 签到结果：今日所得积分、连签天数、里程碑成长值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignInResult {

    /** 签到日期 */
    private LocalDate signDate;

    /** 连续签到第几天 */
    private Integer continuousDays;

    /** 本次获得积分（第 1 天 5 … 第 7 天 50） */
    private Long pointsEarned;

    /** 本次获得成长值（每 7 天 +50） */
    private Integer growthEarned;
}
