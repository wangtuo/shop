package com.shop.product.stock.reconcile.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shop.product.stock.entity.ProductStockLog;
import com.shop.product.stock.reconcile.entity.StockReconcileLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存对账自有 Mapper（P1-1）：直接查 t_product_stock_log / t_product_stock_reconcile_log，
 * 不复用也不修改 ProductStockLogMapper（TRADE-2 独占维护库存核心 Mapper）。
 */
@Mapper
public interface StockReconcileMapper extends BaseMapper<StockReconcileLog> {

    /**
     * 扫描悬挂的 LOCKED(0) 流水（走 V4 索引 idx_status_time）：
     * create_time 早于 cutoff（now-10min，避开正常在途窗口），分批 200。
     */
    @Select("SELECT * FROM t_product_stock_log WHERE status = 0 AND create_time < #{cutoff} "
            + "AND deleted = 0 ORDER BY id ASC LIMIT #{limit}")
    List<ProductStockLog> selectLockedLogsBefore(@Param("cutoff") LocalDateTime cutoff,
                                                 @Param("limit") int limit);

    /**
     * 预售尾款违约扫描：type=2 预售且 status=1（定金已扣减入占用）的流水。
     * 预售无 LOCKED/扣减流水是合法态，扫描器不造流水。
     */
    @Select("SELECT * FROM t_product_stock_log WHERE type = 2 AND status = 1 "
            + "AND create_time < #{cutoff} AND deleted = 0 ORDER BY id ASC LIMIT #{limit}")
    List<ProductStockLog> selectPresaleDeductedBefore(@Param("cutoff") LocalDateTime cutoff,
                                                      @Param("limit") int limit);

    /**
     * 同窗口告警/处置抑制与「连续两轮无订单」升级判定：
     * 统计 since 之后指定 orderNo + issueType + action 的留痕行数。
     */
    @Select("SELECT COUNT(*) FROM t_product_stock_reconcile_log "
            + "WHERE order_no = #{orderNo} AND issue_type = #{issueType} AND action = #{action} "
            + "AND create_time >= #{since}")
    long countSince(@Param("orderNo") String orderNo,
                    @Param("issueType") int issueType,
                    @Param("action") int action,
                    @Param("since") LocalDateTime since);
}
