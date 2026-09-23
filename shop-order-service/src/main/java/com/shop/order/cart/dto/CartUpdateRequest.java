package com.shop.order.cart.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改购物车数量请求。
 */
@Data
public class CartUpdateRequest implements Serializable {

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于 0")
    @Max(value = 99, message = "单件商品数量不能超过 99")
    private Integer qty;
}
