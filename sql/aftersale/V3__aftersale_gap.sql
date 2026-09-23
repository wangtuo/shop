-- =============================================================================
-- V3__aftersale_gap.sql — aftersale 域 W0.5 缺口 DDL（GAP_PLAN_FUNDS.md §3 D4）
--
-- B11：t_aftersale_window 补运费险保费快照（has_freight_insurance V2 已有）。
-- P2-1：无 DDL —— t_aftersale_mq_consume（UK event_id）现状已就绪，本文件不重建、
--       不加列，AftersaleTimeoutListener 直接复用。
-- 库：shop_aftersale；information_schema.COLUMNS 守卫，幂等可重复执行。
-- =============================================================================

USE shop_aftersale;

DROP PROCEDURE IF EXISTS aftersale_v3_add_premium;
DELIMITER //
CREATE PROCEDURE aftersale_v3_add_premium()
BEGIN
    -- 窗口表已有 has_freight_insurance（V2），补保费快照，理赔单调阅
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_aftersale' AND TABLE_NAME='t_aftersale_window'
                     AND COLUMN_NAME='premium_fen') THEN
        ALTER TABLE t_aftersale_window
            ADD COLUMN premium_fen BIGINT NOT NULL DEFAULT 0 COMMENT '运费险保费快照（分）'
                AFTER has_freight_insurance;
    END IF;
END //
DELIMITER ;
CALL aftersale_v3_add_premium();
DROP PROCEDURE IF EXISTS aftersale_v3_add_premium;
