package com.shop.aftersale.aftersale.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户寄回物流 / 商家换货补发物流。
 */
@Data
public class LogisticsRequest implements Serializable {
    @NotBlank(message = "物流公司不能为空")
    private String company;
    @NotBlank(message = "物流单号不能为空")
    private String logisticsNo;
    /** 商家换货发货时的目标 SKU（换货） */
    private Long exchangeSkuId;
}
