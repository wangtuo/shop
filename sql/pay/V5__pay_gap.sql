-- =============================================================================
-- V5__pay_gap.sql — pay 域 W0.5 缺口 DDL（GAP_PLAN_FUNDS.md §3 D1，卡 B8）
--
-- 退款单主动查询补偿列 + 渠道回调幂等表补 refund_no（退款回调复用，
-- notify_type=2 已在 V2 注释中定义）。
-- 库：shop_pay；information_schema 守卫，幂等可重复执行。
-- t_pay_order.pay_scene 注释扩展为 1普通 2组合 3代付 4保证金缴费（列已存在，无需 ALTER）。
-- =============================================================================

USE shop_pay;

DROP PROCEDURE IF EXISTS pay_v5_add_refund_columns;
DELIMITER //
CREATE PROCEDURE pay_v5_add_refund_columns()
BEGIN
    -- 1) 退款单增加主动查询补偿所需列
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_pay' AND TABLE_NAME='t_pay_refund'
                     AND COLUMN_NAME='last_query_time') THEN
        ALTER TABLE t_pay_refund
            ADD COLUMN last_query_time DATETIME NULL COMMENT '最近一次主动查询渠道时间' AFTER retry_count,
            ADD COLUMN query_count INT NOT NULL DEFAULT 0 COMMENT '主动查询次数' AFTER last_query_time,
            ADD KEY idx_status_query (status, last_query_time);
    END IF;

    -- 2) 渠道回调幂等表复用为退款回调（notify_type=2 已在 V2 注释中定义），补 refund_no 列
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_pay' AND TABLE_NAME='t_pay_notify_log'
                     AND COLUMN_NAME='refund_no') THEN
        ALTER TABLE t_pay_notify_log
            ADD COLUMN refund_no VARCHAR(32) NULL COMMENT '退款单号（notify_type=2 时填写）' AFTER pay_no,
            ADD KEY idx_refund_no (refund_no);
    END IF;
END //
DELIMITER ;
CALL pay_v5_add_refund_columns();
DROP PROCEDURE IF EXISTS pay_v5_add_refund_columns;
