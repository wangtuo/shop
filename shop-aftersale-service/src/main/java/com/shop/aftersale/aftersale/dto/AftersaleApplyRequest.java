package com.shop.aftersale.aftersale.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 售后申请请求。金额由服务端按 design 8.4 计算，前端不传金额。
 */
@Data
public class AftersaleApplyRequest implements Serializable {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /** 1 仅退款 2 退货退款 3 换货 4 补发货 5 价保 */
    @NotNull(message = "售后类型不能为空")
    private Integer type;

    /** 责任方 1 商家 2 买家 3 运费险，默认买家责任 */
    private Integer responsibilitySide = 2;

    @NotNull(message = "售后原因不能为空")
    private String reason;

    @NotEmpty(message = "售后明细不能为空")
    private List<Item> items;

    /** 换货目标 SKU（type=3） */
    private Long exchangeSkuId;

    /** 价保是否大促期（30 天价保），type=5 */
    private Boolean bigPromotion = false;

    /** 商家责任时买家垫付的寄回运费（分），服务端据此给运费补偿 */
    private Long returnFreightFen = 0L;

    @Data
    public static class Item implements Serializable {
        @NotNull(message = "订单明细ID不能为空")
        private Long orderItemId;

        @NotNull
        @Min(value = 1, message = "售后数量必须大于0")
        @Max(value = 999, message = "售后数量非法")
        private Integer qty;
    }
}
