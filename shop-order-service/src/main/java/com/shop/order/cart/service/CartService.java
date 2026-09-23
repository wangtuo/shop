package com.shop.order.cart.service;

import com.shop.order.cart.dto.CartAddRequest;
import com.shop.order.cart.dto.CartSelectRequest;
import com.shop.order.cart.dto.CartViewVO;

/**
 * 购物车服务（design 5.4）。
 */
public interface CartService {

    /** 加入购物车（不扣库存；非在售/零库存商品标记为失效）。 */
    long add(Long userId, CartAddRequest request);

    /** 修改数量。 */
    void updateQuantity(Long userId, Long cartId, Integer qty);

    /** 删除条目。 */
    void delete(Long userId, Long cartId);

    /** 移入收藏夹并从购物车删除。 */
    void moveToFavorite(Long userId, Long cartId);

    /** 按条目勾选/取消勾选（ids 为空且带 shopId 时按店铺操作，再为空则全选）。 */
    void select(Long userId, CartSelectRequest request);

    /** 全选/全不选（可限定店铺）。 */
    void selectAll(Long userId, Integer selected, Long shopId);

    /** 反选。 */
    void invert(Long userId);

    /** 一键清理失效商品。 */
    int clearInvalid(Long userId);

    /** 按店铺分组视图（实时刷新失效标记与价格变动提示）。 */
    CartViewVO view(Long userId);
}
