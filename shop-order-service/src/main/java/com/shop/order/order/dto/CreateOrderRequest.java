package com.shop.order.order.dto;

import com.shop.common.constant.BatchSizes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 创建订单请求（design 5.3.2 提交订单）。
 */
@Data
public class CreateOrderRequest implements Serializable {

    /** 客户端防重 Token（@Idempotent 幂等键） */
    @NotBlank(message = "clientToken 不能为空")
    private String clientToken;

    /**
     * 订单类型：1 普通 2 秒杀 3 拼团 4 预售。
     * 白名单 1-4：5=换货单无 POST /orders 建单入口，0/5/6 等非法值入口拒绝（API-T C30）。
     */
    @NotNull(message = "订单类型不能为空")
    @Min(value = 1, message = "订单类型非法")
    @Max(value = 4, message = "换货单不可由此入口创建")
    private Integer orderType;

    /** 订单来源：1 APP 2 H5 3 小程序 4 PC */
    @Min(value = 1, message = "订单来源非法")
    @Max(value = 4, message = "订单来源非法")
    private Integer source = 1;

    /** 购买商品行 */
    @NotEmpty(message = "订单商品不能为空")
    @Size(min = 1, max = BatchSizes.ITEMS_MAX, message = "订单商品行数必须在 1-100 之间")
    @Valid
    private List<Item> items;

    /** 收货地址 ID */
    @NotNull(message = "收货地址不能为空")
    private Long addressId;

    /**
     * 客户端上送运费（分）：仅接收保留兼容老端，建单时忽略其值，运费以产品域运费试算为唯一权威
     * （B7 C32）；与服务端值不一致时打 warn 日志记录篡改，不参与计价。
     */
    @Min(value = 0, message = "运费不能为负")
    private Long freightFen = 0L;

    /** 拟积分抵现金额（分，100 积分=1 元，封顶商品金额 50%）；为 null/0 不使用积分 */
    @Min(value = 0, message = "积分抵现金额不能为负")
    private Long usePointsFen = 0L;

    /** 用户选用的品类券 ID */
    private Long categoryCouponId;
    /** 用户选用的店铺券 ID */
    private Long shopCouponId;
    /** 用户选用的平台通用券 ID */
    private Long platformCouponId;

    /** 秒杀活动 ID（orderType=2 必填） */
    private Long seckillActivityId;
    /** 拼团活动 ID（orderType=3 必填） */
    private Long groupbuyActivityId;
    /** 拼团团号（参团时上送） */
    private String groupNo;
    /** 预售活动 ID（orderType=4 必填） */
    private Long presaleActivityId;
    /** 预售是否尾款阶段 */
    private Boolean presaleFinalStage;

    /** 是否购买运费险（FUNDS B11）：null/false 不买；保费服务端计算并入 payFen，不退 */
    private Boolean buyFreightInsurance;

    /** 来自购物车的条目 ID（下单成功后清理这些购物车项），直接购买为空 */
    @Size(max = BatchSizes.CART_IDS_MAX, message = "购物车条目数不能超过 100")
    private List<Long> fromCartIds;

    /** 买家备注 */
    private String remark;

    /** 发票信息（invoiceType=0/缺省不开票） */
    @Valid
    private InvoiceRequest invoice;

    /**
     * 订单商品行。
     */
    @Data
    public static class Item implements Serializable {

        @NotNull(message = "skuId 不能为空")
        private Long skuId;

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量必须大于 0")
        private Integer qty;
    }
}
