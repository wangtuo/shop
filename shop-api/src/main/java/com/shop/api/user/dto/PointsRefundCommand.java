package com.shop.api.user.dto;

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
 * 积分退回命令：退款成功后按退款比例退回积分（优惠券不退，积分按比例退）。
 *
 * <p>幂等键为 {@code bizNo}（退款单号）。
 *
 * <p>规则来源：design.md 6.4.3 部分退款 / 8.4 退款金额计算。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointsRefundCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "用户ID不能为空")
    @Positive(message = "用户ID必须为正数")
    private Long userId;

    /** 业务单号（退款单号），幂等键 */
    @NotBlank(message = "业务单号不能为空")
    @Size(max = 64, message = "业务单号长度不能超过 64")
    private String bizNo;

    /** 按退款比例计算后实际退回的积分个数 */
    @NotNull(message = "积分数量不能为空")
    @Positive(message = "积分数量必须为正数")
    private Long points;
}
