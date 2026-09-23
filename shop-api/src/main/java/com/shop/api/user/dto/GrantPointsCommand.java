package com.shop.api.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 积分发放命令：消费返积分、签到、评价、分享、晒单、补偿等场景发放积分。
 *
 * <p>消费发放数量 = 实付金额（元） × 等级积分倍率；积分自获取之日起 365 天有效。
 *
 * <p>规则来源：design.md 2.2.2 积分规则。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GrantPointsCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    /** 业务单号（订单号/签到流水等），幂等键 */
    @NotBlank(message = "业务单号不能为空")
    @Size(max = 64, message = "业务单号长度不能超过 64")
    private String bizNo;

    /** 发放积分个数（已按等级倍率计算后的最终值） */
    @NotNull(message = "积分数量不能为空")
    @Positive(message = "积分数量必须为正数")
    private Long points;

    /** 积分获取场景，取值见 {@code PointsScene} */
    @NotNull(message = "积分场景不能为空")
    @Min(value = 1, message = "积分场景取值非法")
    private Integer scene;
}
