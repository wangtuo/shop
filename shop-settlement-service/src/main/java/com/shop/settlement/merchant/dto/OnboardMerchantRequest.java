package com.shop.settlement.merchant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 商户入驻（平台运营）请求。 */
@Data
public class OnboardMerchantRequest {

    @NotNull(message = "商户ID不能为空")
    private Long merchantId;

    @Size(max = 128)
    private String merchantName;

    private Long categoryId;

    @Size(max = 128)
    private String categoryName;

    @NotNull(message = "类目默认佣金率不能为空")
    @Min(value = 0, message = "佣金率不能为负")
    @Max(value = 10000, message = "佣金率不能超过100%")
    private Integer commissionRateBps;

    /** 应缴保证金（分），按类目 1000~50000 元 */
    @NotNull(message = "应缴保证金不能为空")
    @Min(value = 100000, message = "保证金最低1000元")
    @Max(value = 5000000, message = "保证金最高50000元")
    private Long depositRequiredFen;
}
