package com.shop.order.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 待发货修改地址请求。
 */
@Data
public class AddressUpdateRequest implements Serializable {

    @NotNull(message = "地址 ID 不能为空")
    private Long addressId;
}
