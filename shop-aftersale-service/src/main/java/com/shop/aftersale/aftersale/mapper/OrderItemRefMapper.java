package com.shop.aftersale.aftersale.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.aftersale.aftersale.entity.OrderItemRef;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderItemRefMapper extends BaseMapper<OrderItemRef> {

    @Select("SELECT * FROM t_aftersale_order_item_ref WHERE order_item_id = #{orderItemId} AND deleted = 0")
    OrderItemRef selectByOrderItemId(@Param("orderItemId") Long orderItemId);

    /** 占用进行中售后（同一明细同时仅一笔）。 */
    @Update("UPDATE t_aftersale_order_item_ref SET active_no = #{aftersaleNo}, update_time = NOW() "
            + "WHERE order_item_id = #{orderItemId} AND active_no = '' AND deleted = 0")
    int occupy(@Param("orderItemId") Long orderItemId, @Param("aftersaleNo") String aftersaleNo);

    /** 释放进行中标记（拒绝/撤销）。 */
    @Update("UPDATE t_aftersale_order_item_ref SET active_no = '', update_time = NOW() "
            + "WHERE order_item_id = #{orderItemId} AND active_no = #{aftersaleNo} AND deleted = 0")
    int release(@Param("orderItemId") Long orderItemId, @Param("aftersaleNo") String aftersaleNo);

    /**
     * 累计退款金额（守恒：累计退款 <= 实付），并清进行中标记。影响 0 行即超额。
     */
    @Update("UPDATE t_aftersale_order_item_ref SET refunded_fen = refunded_fen + #{refundFen}, "
            + "active_no = CASE WHEN active_no = #{aftersaleNo} THEN '' ELSE active_no END, "
            + "update_time = NOW() WHERE order_item_id = #{orderItemId} AND deleted = 0 "
            + "AND refunded_fen + #{refundFen} <= paid_fen")
    int addRefunded(@Param("orderItemId") Long orderItemId,
                    @Param("refundFen") long refundFen,
                    @Param("aftersaleNo") String aftersaleNo);
}
