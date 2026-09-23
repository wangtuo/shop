package com.shop.api.user.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 资金账户金额变动命令，同时用于余额账户扣款/入账与赠金账户扣款。
 *
 * <p>金额一律使用 Long，单位：分，禁止 double/float。
 * 幂等键为 {@code bizNo}（支付单号/退款单号）。
 *
 * <p>规则来源：design.md 2.2.1 账户体系、6.4.1 退款路径。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AmountCommand implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /** 业务单号（支付单号/退款单号），幂等键 */
    @NotBlank(message = "业务单号不能为空")
    @Size(max = 64, message = "业务单号长度不能超过 64")
    private String bizNo;

    /** 变动金额，单位：分（恒为正数，方向由方法语义决定） */
    @NotNull(message = "金额不能为空")
    @Min(value = 1, message = "金额必须大于 0")
    private Long amountFen;

    /** 备注（如退款原因、补偿说明） */
    @Size(max = 256, message = "备注长度不能超过 256")
    private String remark;
}
