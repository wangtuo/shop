package com.shop.product.freight.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 运费模板启用/停用请求。停用默认模板后，店铺取价回退 DEFAULT_NONE（0 运费）。
 */
@Data
public class FreightTemplateStatusRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 0 停用 1 启用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值非法")
    @Max(value = 1, message = "状态值非法")
    private Integer status;
}
