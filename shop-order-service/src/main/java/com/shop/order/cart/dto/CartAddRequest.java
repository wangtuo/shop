package com.shop.order.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 加入购物车请求。
 */
@Data
public class CartAddRequest implements Serializable {

    @NotNull(message = "skuId 不能为空")
    private Long skuId;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于 0")
    private Integer qty = 1;
}
