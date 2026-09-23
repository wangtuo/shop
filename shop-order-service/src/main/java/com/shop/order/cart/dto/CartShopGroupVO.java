package com.shop.order.cart.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 购物车按店铺分组视图（design 5.4）。
 */
@Data
@Builder
public class CartShopGroupVO implements Serializable {

    private Long merchantId;
    private Long shopId;
    private List<CartItemVO> items;
    /** 本组是否全部勾选 */
    private Boolean allSelected;
    /** 本组勾选件数（SKU 行数） */
    private Integer selectedCount;
    /** 本组勾选商品总件数（数量合计） */
    private Integer selectedQty;
    /** 本组勾选商品实时金额（分） */
    private Long selectedAmountFen;
    /** 本组失效条目数 */
    private Integer invalidCount;
}
