package com.shop.aftersale.aftersale.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家收货（用户寄回后确认/拒收）。
 */
@Data
public class MerchantReceiveRequest implements Serializable {
    /** true 确认收货 false 拒收 */
    @NotNull(message = "收货结论不能为空")
    private Boolean accept;
    private String rejectReason;
}
