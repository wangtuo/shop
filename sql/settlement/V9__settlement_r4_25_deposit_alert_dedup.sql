-- =============================================================================
-- V9__settlement_r4_25_deposit_alert_dedup.sql — R4-25 保证金 DEPOSIT_ALERT 去重
-- （settlement 域；版本号沿用 V8 之后。V6 被 common 七库脚本占用。）
--
-- 背景：
--   DepositService.publishAlert 是四条预警路径（罚款跌破阈值 FINE / 退款瀑布扣赔
--   CLAW / 清退无收款账户人工挂起 HANG / 渠道打款失败 FAIL）的唯一物理发布点，
--   旧 outbox bizKey 一律用裸 merchantId，而 t_mq_outbox 的 UK uk_topic_tag_bizkey
--   行投递后永不删除（common/V6）：商户终生第一条预警之后，四条路径全部以
--   DuplicateKeyException 回滚所在的**资金/清退事务**——
--     · 罚款永远无法再落账（同事务 log30/平台43 一起回滚）；
--     · 清退挂起每日 03:00 扫描必失败（alertManualHang 无任何闸门，每日重发）；
--     · 退款冲正 MQ 消费事务回滚 → 毒丸进 DLQ；RemitQueryJob 60s 无限回滚循环，
--       且按 id ASC 形成队头阻塞。
--
-- 修复（三层同改，应用侧同期代码）：
--   1. outbox bizKey 改为实例级：FINE:{fineLogNo} / CLAW:{refundNo} /
--      HANG:{refundLogNo} / FAIL:{refundLogNo}；前缀与存量裸 merchantId 键天然
--      隔离，无需回填；事件体 bizNo 仍为 merchantId（无消费者，载荷口径不变），
--      另加 alertType/refNo 供运营区分；
--   2. 清退人工挂起加持久边沿闸门 hang_alerted：每个 log40 退还单只挂起告警一次，
--      CAS 0→1 获胜才登记 outbox，次日重扫静默跳过；
--   3. publishAlert 捕获 DuplicateKeyException 降级为 error 日志，预警碰撞永不
--      回滚资金主事务。
--
-- 幂等：information_schema 守卫，可重复执行。库：shop_settlement。
-- =============================================================================

USE shop_settlement;

DROP PROCEDURE IF EXISTS settlement_v9_deposit_alert_dedup;
DELIMITER //
CREATE PROCEDURE settlement_v9_deposit_alert_dedup()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'shop_settlement'
                     AND TABLE_NAME = 't_sett_deposit_log'
                     AND COLUMN_NAME = 'hang_alerted') THEN
        ALTER TABLE t_sett_deposit_log
            ADD COLUMN hang_alerted TINYINT NOT NULL DEFAULT 0
                COMMENT 'R4-25 清退人工挂起预警闸门：0未告警 1已告警（每笔log40仅一次，CAS置位）'
                AFTER last_query_time;
    END IF;
END //
DELIMITER ;
CALL settlement_v9_deposit_alert_dedup();
DROP PROCEDURE IF EXISTS settlement_v9_deposit_alert_dedup;
