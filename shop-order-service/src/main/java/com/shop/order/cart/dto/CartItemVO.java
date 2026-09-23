package com.shop.order.cart.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 购物车单行视图。失效与价格变动在查询时实时调商品域判定。
 */
@Data
@Builder
public class CartItemVO implements Serializable {

    private Long cartId;
    private Long skuId;
    private Long spuId;
    private Long merchantId;
    private Long shopId;
    private String skuName;
    private String specText;
    private String image;
    /** 加购时价格（分） */
    private Long addPriceFen;
    /** 商品域当前价格（分） */
    private Long currentPriceFen;
    private Integer qty;
    private Integer selected;
    /** 0 有效 1 失效 */
    private Integer invalid;
    /** 失效原因：0 未失效 1 已下架 2 售罄 3 已删除 */
    private Integer invalidReason;
    /** 价格已变提示（不删除，前端提示用户） */
    private Boolean priceChanged;
}
