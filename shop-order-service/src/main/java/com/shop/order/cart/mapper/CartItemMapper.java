package com.shop.order.cart.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.order.cart.entity.CartItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {

    /**
     * 条件更新失效标记（仅在标记变化时落库，避免无意义写）。
     */
    @Update("UPDATE t_order_cart SET invalid = #{invalid}, invalid_reason = #{reason} "
            + "WHERE id = #{id} AND user_id = #{userId} AND deleted = 0")
    int updateInvalid(@Param("id") Long id, @Param("userId") Long userId,
                      @Param("invalid") Integer invalid, @Param("reason") Integer reason);
}
