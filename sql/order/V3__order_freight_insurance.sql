-- =============================================================================
-- V3__order_freight_insurance.sql — 订单运费险列（GAP_PLAN_FUNDS.md §3 D3，卡 B11）
--
-- 库：shop_order。order 域唯一 V3：MARKETING D3 的 group_succeed/renew_count/
-- parent_order_no 已由 MASTER §2.1 第 4 条裁决作废，禁止加入本文件。
-- information_schema.COLUMNS 守卫，幂等可重复执行。
-- =============================================================================

USE shop_order;

DROP PROCEDURE IF EXISTS order_v3_add_insurance;
DELIMITER //
CREATE PROCEDURE order_v3_add_insurance()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_order' AND TABLE_NAME='t_order_order'
                     AND COLUMN_NAME='insurance_premium_fen') THEN
        ALTER TABLE t_order_order
            ADD COLUMN insurance_premium_fen BIGINT NOT NULL DEFAULT 0
                COMMENT '运费险保费（分，已含在 pay_fen 内）' AFTER pay_fen,
            ADD COLUMN has_freight_insurance TINYINT NOT NULL DEFAULT 0
                COMMENT '是否购运费险 0否 1是' AFTER insurance_premium_fen;
    END IF;
END //
DELIMITER ;
CALL order_v3_add_insurance();
DROP PROCEDURE IF EXISTS order_v3_add_insurance;
