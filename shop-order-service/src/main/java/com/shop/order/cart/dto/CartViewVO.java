package com.shop.order.cart.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 购物车整体视图。
 */
@Data
@Builder
public class CartViewVO implements Serializable {

    /** 按店铺分组 */
    private List<CartShopGroupVO> shopGroups;
    /** 商品条目数（SKU 行数，上限 99） */
    private Integer totalCount;
    /** 商品总件数（数量合计） */
    private Integer totalQty;
    /** 勾选商品件数（数量合计） */
    private Integer selectedQty;
    /** 失效商品条目数 */
    private Integer invalidCount;
    /** 是否全选 */
    private Boolean allSelected;
    /** 勾选商品实时总金额（分，不含运费/优惠，供前端展示） */
    private Long selectedAmountFen;
}
