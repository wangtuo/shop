-- =====================================================================
-- V4__pay_multi_attempt.sql — P2-5 修复：同一订单支持多次支付尝试
--
-- 背景：t_pay_order.uk_order_no 一个订单只允许一行支付单，渠道 FAIL/CLOSED
--   （支付失败 / 渠道关单 / 15 分钟延时核查置关闭）后用户在订单超时取消前
--   点"重新支付"只能拿到一张死单（FIXES_D P2-5 留痕）。
--
-- 方案（对齐 DDL_REVIEW.md "墓碑槽位" 模式：0=活 / 自身雪花 id=死）：
--   active_slot = 0  当前活跃支付单（每订单至多一条）
--   active_slot = id 被取代的终态（FAIL 40 / CLOSED 50）历史行，
--                  入槽即释放 (order_no, 0) 活跃槽位，允许重新发起支付
--   唯一键 uk_order_no(order_no) → uk_order_active(order_no, active_slot)
--
-- 幂等可重复执行：列/键存在性一律先查 information_schema 再 ALTER。
-- =====================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS shop_pay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_pay;

DROP PROCEDURE IF EXISTS pay_v4_multi_attempt;
DELIMITER //
CREATE PROCEDURE pay_v4_multi_attempt()
BEGIN
    -- 1) 活跃槽位列：存量行默认 0（旧 uk_order_no 保证一订单一行，不存在撞槽）
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 't_pay_order'
                     AND COLUMN_NAME = 'active_slot') THEN
        ALTER TABLE t_pay_order
            ADD COLUMN active_slot BIGINT NOT NULL DEFAULT 0
                COMMENT '活跃槽位：0=当前活跃支付单（每订单至多一条）；被取代的终态行置为自身ID（雪花值）以释放槽位'
                AFTER order_no;
    END IF;

    -- 2) 下线单订单单列唯一键
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 't_pay_order'
                 AND INDEX_NAME = 'uk_order_no') THEN
        ALTER TABLE t_pay_order DROP INDEX uk_order_no;
    END IF;

    -- 3) 复合唯一键：每订单至多一条 active_slot=0 的活跃行，历史终态行各占自身 id 槽位
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 't_pay_order'
                 AND INDEX_NAME = 'uk_order_active') THEN
        ALTER TABLE t_pay_order
            ADD UNIQUE KEY uk_order_active (order_no, active_slot);
    END IF;
END //
DELIMITER ;

CALL pay_v4_multi_attempt();
DROP PROCEDURE IF EXISTS pay_v4_multi_attempt;
