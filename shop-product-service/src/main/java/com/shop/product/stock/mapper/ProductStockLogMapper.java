package com.shop.product.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.stock.entity.ProductStockLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 库存单据流水 Mapper。
 */
@Mapper
public interface ProductStockLogMapper extends BaseMapper<ProductStockLog> {

    /** 按唯一约束（order_no + sku + type）查流水。 */
    @Select("SELECT * FROM t_product_stock_log WHERE order_no = #{orderNo} AND sku_id = #{skuId} "
            + "AND type = #{type} AND deleted = 0 LIMIT 1")
    ProductStockLog selectByUk(@Param("orderNo") String orderNo,
                               @Param("skuId") Long skuId,
                               @Param("type") int type);

    /**
     * 售后回库：按订单 + SKU 查可回库流水（B13：status=1 已扣减 / 4 已出账均可回库，
     * 各自逆运算由 service 按当前状态选择；跨库存类型取一条，一单同 SKU 仅一行）。
     */
    @Select("SELECT * FROM t_product_stock_log WHERE order_no = #{orderNo} AND sku_id = #{skuId} "
            + "AND status IN (1, 4) AND deleted = 0 ORDER BY id LIMIT 1")
    ProductStockLog selectDeductedByOrderAndSku(@Param("orderNo") String orderNo, @Param("skuId") Long skuId);

    /** 取消订单事件体不带库存类型：按订单 + SKU 查锁定中流水。 */
    @Select("SELECT * FROM t_product_stock_log WHERE order_no = #{orderNo} AND sku_id = #{skuId} "
            + "AND status = 0 AND deleted = 0 ORDER BY id LIMIT 1")
    ProductStockLog selectLockedByOrderAndSku(@Param("orderNo") String orderNo, @Param("skuId") Long skuId);

    /** 支付成功但事件体无 items 时，按订单查出全部锁定中流水做 confirm。 */
    @Select("SELECT * FROM t_product_stock_log WHERE order_no = #{orderNo} AND status = 0 AND deleted = 0")
    List<ProductStockLog> selectLockedByOrder(@Param("orderNo") String orderNo);

    /**
     * 发货事件：按订单 + SKU 查任意状态流水（B13）。null=事件早于扣减到达（抛冲突重试）；
     * 1 可出账；4 重复发货幂等跳过；0/2/3/5 状态冲突可重试。
     */
    @Select("SELECT * FROM t_product_stock_log WHERE order_no = #{orderNo} AND sku_id = #{skuId} "
            + "AND deleted = 0 ORDER BY id LIMIT 1")
    ProductStockLog selectByOrderAndSku(@Param("orderNo") String orderNo, @Param("skuId") Long skuId);

    /** 发货事件体无 items 时，按订单查出全部流水逐笔判状态出账。 */
    @Select("SELECT * FROM t_product_stock_log WHERE order_no = #{orderNo} AND deleted = 0 ORDER BY id")
    List<ProductStockLog> selectByOrder(@Param("orderNo") String orderNo);

    /**
     * B5 尾款关联定金：查同 SKU 仍可关联的预售定金扣减流水（type=2 status=1），
     * 排除尾款单自身、排除已被其他尾款单关联的定金单，取最早一笔。
     */
    @Select("SELECT * FROM t_product_stock_log WHERE sku_id = #{skuId} AND type = 2 AND status = 1 "
            + "AND order_no <> #{excludeOrderNo} AND ref_order_no IS NULL AND deleted = 0 "
            + "ORDER BY id LIMIT 1")
    ProductStockLog selectPresaleDeposit(@Param("skuId") Long skuId,
                                         @Param("excludeOrderNo") String excludeOrderNo);

    /** B5 预售取消事件体无 items：按订单查出全部 status=1 的预售流水回补。 */
    @Select("SELECT * FROM t_product_stock_log WHERE order_no = #{orderNo} AND type = 2 "
            + "AND status = 1 AND deleted = 0 ORDER BY id")
    List<ProductStockLog> selectPresaleDeductedByOrder(@Param("orderNo") String orderNo);

    /** 条件推进流水状态，影响 0 行说明并发状态冲突。 */
    @Update("UPDATE t_product_stock_log SET status = #{toStatus}, update_time = NOW() "
            + "WHERE id = #{id} AND status = #{fromStatus} AND deleted = 0")
    int updateStatusIf(@Param("id") Long id,
                       @Param("fromStatus") int fromStatus,
                       @Param("toStatus") int toStatus);

    /**
     * 回库时同时记录回库原因。B13：1 已扣减（占用仓）/ 4 已出账 均可回库，
     * 数量逆运算由 service 按原状态选择（4 不再减 occupied）。
     */
    @Update("UPDATE t_product_stock_log SET status = 3, return_reason = #{reason}, update_time = NOW() "
            + "WHERE id = #{id} AND status IN (1, 4) AND deleted = 0")
    int markReturned(@Param("id") Long id, @Param("reason") int reason);

    /** B5 预售定金流水回补：1 已扣减 → 5 预售回补，影响 0 行说明状态并发冲突。 */
    @Update("UPDATE t_product_stock_log SET status = 5, update_time = NOW() "
            + "WHERE id = #{id} AND status = 1 AND deleted = 0")
    int markPresaleRecover(@Param("id") Long id);
}
