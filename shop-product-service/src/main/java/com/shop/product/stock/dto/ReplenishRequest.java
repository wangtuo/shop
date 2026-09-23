package com.shop.product.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 商户补货请求。
 */
@Data
public class ReplenishRequest implements Serializable {

    /** 补货数量（正整数），加入可售库存 */
    @NotNull(message = "补货数量不能为空")
    @Min(value = 1, message = "补货数量必须大于 0")
    private Integer qty;
}
