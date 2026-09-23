-- =============================================================================
-- V7__stock_warning_dedup.sql — R4-25 库存低库存预警去重（product 域）
--
-- 背景：
--   StockServiceImpl.afterStockChanged 仅按 available <= warn_threshold 判定，
--   无跨阈值边沿闸门；每次锁库/释放/回库/补货只要仍在阈值下都向 t_mq_outbox
--   登记同三元组 ('shop_stock_warning','',skuId)。outbox UK uk_topic_tag_bizkey
--   行投递后永不删除：第二次预警必抛 DuplicateKeyException 回滚**所在的业务事务**
--   （下单锁库失败、取消释放永不回库、售后回库失败、商家补货被拒、对账每轮失败告警）。
--
-- 修复（两步，应用侧 V9 同期代码）：
--   1. 加武装标记 low_stock_alerted：跌破阈值时 CAS 0→1 抢占才发预警；
--      可售恢复到阈值以上 CAS 1→0 重新武装。每轮低库存区间恰好一条预警。
--   2. outbox bizKey 改为 skuId:warningId（每次预警的流水行 id 唯一）。
--
-- 上线回填：发布时已在阈值下的存量 SKU 直接置 1，视为本轮已预警，
-- 避免上线瞬间对全网低库存 SKU 补发风暴；下一次补货越过阈值后自动复位武装。
--
-- 幂等：information_schema 守卫，可重复执行。库：shop_product。
-- =============================================================================

USE shop_product;

DROP PROCEDURE IF EXISTS product_v7_stock_warning_dedup;
DELIMITER //
CREATE PROCEDURE product_v7_stock_warning_dedup()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'shop_product' AND TABLE_NAME = 't_product_sku'
                     AND COLUMN_NAME = 'low_stock_alerted') THEN
        ALTER TABLE t_product_sku
            ADD COLUMN low_stock_alerted TINYINT NOT NULL DEFAULT 0
                COMMENT '低库存预警武装标记：0未发（武装中）1已发；可售恢复阈值以上自动CAS复位'
                AFTER warn_threshold;

        UPDATE t_product_sku
           SET low_stock_alerted = 1
         WHERE deleted = 0
           AND available_stock <= COALESCE(warn_threshold, 10);
    END IF;
END //
DELIMITER ;
CALL product_v7_stock_warning_dedup();
DROP PROCEDURE IF EXISTS product_v7_stock_warning_dedup;
