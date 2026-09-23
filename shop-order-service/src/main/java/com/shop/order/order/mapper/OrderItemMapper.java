package com.shop.order.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.order.order.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 限购校验：统计用户对某 SKU 的历史成交数量（已支付且未取消的订单明细）。
     */
    @Select("SELECT COALESCE(SUM(i.qty), 0) FROM t_order_item i "
            + "INNER JOIN t_order_order o ON o.order_no = i.order_no AND o.deleted = 0 "
            + "WHERE i.user_id = #{userId} AND i.sku_id = #{skuId} "
            + "AND i.deleted = 0 AND o.status NOT IN (10, 50)")
    int sumPurchasedQty(@Param("userId") Long userId, @Param("skuId") Long skuId);

    /** 售后事件回写明细状态与关联售后单。 */
    @Update("UPDATE t_order_item SET aftersale_status = #{toStatus}, aftersale_no = #{aftersaleNo}, "
            + "version = version + 1 WHERE id = #{itemId} AND aftersale_status = #{fromStatus} "
            + "AND deleted = 0")
    int updateAftersaleStatus(@Param("itemId") Long itemId, @Param("fromStatus") int fromStatus,
                              @Param("toStatus") int toStatus, @Param("aftersaleNo") String aftersaleNo);

    /** 不校验当前明细状态的强制回写（状态机映射由消费侧保证，重复事件幂等覆盖）。 */
    @Update("UPDATE t_order_item SET aftersale_status = #{toStatus}, aftersale_no = #{aftersaleNo}, "
            + "version = version + 1 WHERE id = #{itemId} AND deleted = 0")
    int forceAftersaleStatus(@Param("itemId") Long itemId, @Param("toStatus") int toStatus,
                             @Param("aftersaleNo") String aftersaleNo);

    /** REFUND_SUCCESS：累计退款额与已退款状态（按售后单关联的明细）。 */
    @Update("UPDATE t_order_item SET aftersale_status = 3, refunded_fen = refunded_fen + #{refundFen}, "
            + "version = version + 1 WHERE id = #{itemId} AND deleted = 0")
    int markRefunded(@Param("itemId") Long itemId, @Param("refundFen") long refundFen);
}
