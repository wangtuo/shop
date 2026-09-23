-- =============================================================================
-- V8__settlement_r4_24_recovery_idx.sql — R4-24 加固：保证金到账对账兜底扫描索引
-- （settlement 域；版本号沿用 V7 之后。V6 被 common 七库脚本占用。）
--
-- 背景（修复评审项 #2）：
--   DepositPayRecoveryJob 每 60s 扫描 log_type=10 AND status=10 AND deleted=0 的
--   缴费单，谓词含 create_time < ? 与 (last_query_time IS NULL OR last_query_time < ?)，
--   ORDER BY (last_query_time IS NULL) DESC, id ASC（未查询优先，避免废弃单饿死新单）。
--   用户发起但永不完成的缴费支付意向（FAIL/CLOSED 后不再支付）会长期停留 status=10，
--   无支撑索引时批次扫描随存量增长退化为全表排序；加 4 列复合索引使等值前导列命中 range/
--   ref，排序在未查明细前只走索引（SELECT * 再回表，单批 ≤100 行，回表代价可接受）。
--
-- 幂等：information_schema 守卫，已存在则跳过；可重复执行。
-- 库：shop_settlement。
-- =============================================================================

USE shop_settlement;

DROP PROCEDURE IF EXISTS settlement_v8_deposit_recovery_idx;
DELIMITER //
CREATE PROCEDURE settlement_v8_deposit_recovery_idx()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'shop_settlement'
                     AND TABLE_NAME = 't_sett_deposit_log'
                     AND INDEX_NAME = 'idx_deposit_pay_recovery') THEN
        ALTER TABLE t_sett_deposit_log
            ADD KEY idx_deposit_pay_recovery (log_type, status, deleted, id);
    END IF;
END //
DELIMITER ;
CALL settlement_v8_deposit_recovery_idx();
DROP PROCEDURE IF EXISTS settlement_v8_deposit_recovery_idx;
