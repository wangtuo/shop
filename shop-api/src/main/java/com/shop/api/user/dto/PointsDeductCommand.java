package com.shop.api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 积分实扣命令：支付成功后，将下单冻结的积分正式扣除。
 *
 * <p>幂等键为 {@code bizNo}（订单号），与冻结记录一一对应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PointsDeductCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 业务单号（订单号），幂等键 */
    @NotBlank(message = "业务单号不能为空")
    @Size(max = 64, message = "业务单号长度不能超过 64")
    private String bizNo;
}
