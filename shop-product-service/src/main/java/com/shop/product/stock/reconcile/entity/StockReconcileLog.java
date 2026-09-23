package com.shop.product.stock.reconcile.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 普通库存对账留痕（P1-1/B5，表 t_product_stock_reconcile_log，product V4）。
 *
 * <p>每次对账处置落一行，用于审计、同窗口告警抑制与「连续两轮无订单」升级判定。
 * 该表为运维台账，无 deleted/update_time 列，故不继承 BaseEntity。
 */
@Data
@TableName("t_product_stock_reconcile_log")
public class StockReconcileLog {

    /** 雪花主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联订单号（无有效订单时可为空，以 log_id 挂流水） */
    private String orderNo;

    /** SKU ID */
    private Long skuId;

    /** 悬挂的 t_product_stock_log.id */
    private Long logId;

    /** 问题类型：1 终态订单残留 LOCKED 2 无有效订单 LOCKED 3 预售支付后未扣/尾款违约 */
    private Integer issueType;

    /** 处置：0 告警 1 自动释放 2 补扣（confirm/出账） */
    private Integer action;

    /** 处置/告警详情 */
    private String detail;

    /** 记录时间 */
    private LocalDateTime createTime;
}
