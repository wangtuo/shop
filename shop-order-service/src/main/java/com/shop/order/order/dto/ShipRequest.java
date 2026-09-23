package com.shop.order.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家发货请求。
 */
@Data
public class ShipRequest implements Serializable {

    @NotBlank(message = "物流单号不能为空")
    private String logisticsNo;

    /** 物流公司 */
    private String logisticsCompany;
}
