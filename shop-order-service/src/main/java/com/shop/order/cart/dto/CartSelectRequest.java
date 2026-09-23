package com.shop.order.cart.dto;

import com.shop.common.constant.BatchSizes;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 购物车勾选/批量勾选请求（ids 为空表示全选/全不选，可带 shopId 限定店铺）。
 */
@Data
public class CartSelectRequest implements Serializable {

    /** 购物车条目 ID 列表；为空时按店铺（或全部）操作 */
    @Size(max = BatchSizes.CART_IDS_MAX, message = "购物车条目数不能超过 100")
    private List<Long> ids;

    /** 0 取消勾选 1 勾选 */
    @NotNull(message = "selected 不能为空")
    private Integer selected;

    /** 限定店铺 ID（可空） */
    private Long shopId;
}
