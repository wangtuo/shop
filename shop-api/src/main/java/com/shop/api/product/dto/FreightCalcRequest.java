package com.shop.api.product.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 运费试算请求（TRADE C32）：服务端取价为唯一权威口径。
 *
 * <p>地址二选一：显式省市区（province/city/district）或收货地址 ID（addressId）；
 * orderNo 可空（确认订单页未下单场景），已下单回算时携带。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FreightCalcRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单号（已下单回算时携带，确认订单页试算可空） */
    private String orderNo;

    /** 试算商品行 */
    @NotEmpty(message = "items 不能为空")
    @Valid
    @Builder.Default
    private List<FreightItem> items = new ArrayList<>();

    /** 省份（与 city/district 同时上送；与 addressId 二选一） */
    private String province;

    /** 城市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 收货地址 ID（与省市区二选一） */
    private Long addressId;

    /**
     * 运费试算单行。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FreightItem implements Serializable {

        private static final long serialVersionUID = 1L;

        /** SKU ID */
        @NotNull(message = "skuId 不能为空")
        private Long skuId;

        /** 购买数量 */
        @NotNull(message = "qty 不能为空")
        @Min(value = 1, message = "qty 必须大于 0")
        private Integer qty;
    }
}
