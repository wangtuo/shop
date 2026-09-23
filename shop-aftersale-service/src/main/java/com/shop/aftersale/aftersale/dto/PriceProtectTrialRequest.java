package com.shop.aftersale.aftersale.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 价保试算请求。
 */
@Data
public class PriceProtectTrialRequest implements Serializable {
    @NotBlank(message = "订单号不能为空")
    private String orderNo;
    @NotNull(message = "订单明细ID不能为空")
    private Long orderItemId;
    /** 是否大促价保期（30 天） */
    private Boolean bigPromotion = false;
}
