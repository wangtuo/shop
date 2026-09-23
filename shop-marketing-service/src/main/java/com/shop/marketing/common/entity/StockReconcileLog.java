package com.shop.marketing.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存对账自愈留痕（V5 t_stock_reconcile_log，W4-4/P1-2）。
 *
 * <p>scope：1 秒杀 Redis/DB 对账；2 秒杀预占回补。
 * action：0 仅告警 / 1 重建 Redis / 2 自动停售 / 3 释放预占。
 * 不继承 BaseEntity（该表仅 id/create_time，无 update_time/deleted 列）。
 */
@Data
@TableName("t_stock_reconcile_log")
public class StockReconcileLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 对账域：1秒杀Redis/DB 2秒杀预占回补 */
    private Integer scope;
    private Long activityId;
    private Long skuId;
    /** 偏差量（Redis-DB，带符号） */
    private Long deviation;
    /** 处置：0告警 1重建Redis 2停售 3释放预占 */
    private Integer action;
    /** 处置前指标 JSON */
    private String metricBefore;
    private LocalDateTime createTime;
}
