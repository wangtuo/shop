package com.shop.order.cart.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.shop.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户收藏夹（t_order_favorite，购物车「移入收藏」目标）。
 * <p>deleted 语义同 {@link CartItem}：0=未删除；删除时写入该行雪花 id，
 * 配合 uk(user_id, sku_id, deleted) 支持取消收藏后重新收藏。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_order_favorite")
public class Favorite extends BaseEntity {

    private Long userId;
    private Long skuId;
    private Long spuId;
    private Long merchantId;
    private Long shopId;
    private String skuName;
    private String specText;
    private String image;
    /** 收藏时单价（分） */
    private Long priceFen;
}
