package com.shop.settlement.merchant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 调整商户等级请求（平台运营）。 */
@Data
public class UpdateLevelRequest {

    @NotNull(message = "商户等级不能为空")
    @Min(value = 0, message = "等级取值0~3")
    @Max(value = 3, message = "等级取值0~3")
    private Integer level;
}
