package com.shop.settlement.merchant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 平台保证金罚款请求（POST /admin/deposit/fine，GAP_PLAN_FUNDS B10）。
 * clientToken 为罚款幂等键（DP log_type=30 的 bizNo），重放不重复扣。
 */
@Data
public class DepositFineRequest {

    @NotNull(message = "商户ID不能为空")
    private Long merchantId;

    @NotNull(message = "罚款金额不能为空")
    @Min(value = 1, message = "罚款金额必须大于0")
    private Long amountFen;

    /** 罚款事由（落 DP 流水 remark） */
    @NotBlank(message = "罚款事由不能为空")
    @Size(max = 400)
    private String reason;

    /** 幂等令牌（管理端生成，同一令牌只生效一次） */
    @NotBlank(message = "幂等令牌不能为空")
    @Size(max = 64)
    private String clientToken;
}
