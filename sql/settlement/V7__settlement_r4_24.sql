-- =============================================================================
-- V7__settlement_r4_24.sql — settlement 域 R4-24：MQ 消费幂等唯一键加消费组维度
-- （版本号说明：V6 被 sql/common/V6__outbox_biz_key_uk.sql 占用且由 apply-sql.sh
--   整体执行一次，故域脚本从 V7 起。）
--
-- 背景（生产事故实证，2026-09-20 E2E 12 门复跑，SettlementE2ETest 保证金用例）：
--   同一 topic shop_order_paid 被两个独立消费组订阅——
--     cg_sett_paid        （ClearingService 清算，scene=4 仅登记后直接 ACK）
--     cg_sett_deposit_pay （DepositPaySettlementService 保证金到账入账）
--   两组共用 t_sett_mq_consume，而唯一键仅 uk_event_id(event_id)：先落库的一组
--   使另一组 INSERT IGNORE 静默返回 0 → 业务分支被幂等判定跳过、消息照常 ACK。
--   当 cg_sett_paid 抢先（启动追赶期高概率）时，scene=4 保证金永远不入账：
--   无消费流水、无错误日志、broker 统计已 ACK——静默资金丢失。
--
-- 修复：幂等粒度 = (event_id, consumer_group)。扇出到多个消费组的同一事件
--   必须各自独立幂等；组内重投仍由同一行挡住。R4-24 对账兜底 Job 见
--   DepositPayRecoveryService（status=10 缴费单超时回查支付域补账）。
--
-- 库：shop_settlement；information_schema 守卫，幂等可重复执行。
-- =============================================================================

USE shop_settlement;

DROP PROCEDURE IF EXISTS settlement_v7_mq_consume_uk;
DELIMITER //
CREATE PROCEDURE settlement_v7_mq_consume_uk()
BEGIN
    -- 1) 删除 R4-24 前的单列唯一键 uk_event_id（普通索引 idx_topic_group 保留）
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = 'shop_settlement'
                 AND TABLE_NAME = 't_sett_mq_consume'
                 AND INDEX_NAME = 'uk_event_id'
                 AND NON_UNIQUE = 0) THEN
        ALTER TABLE t_sett_mq_consume DROP INDEX uk_event_id;
    END IF;

    -- 2) 新建 (event_id, consumer_group) 复合唯一键：同事件扇出多组各自登记，
    --    组内重投/并发仍被唯一键挡住。
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'shop_settlement'
                     AND TABLE_NAME = 't_sett_mq_consume'
                     AND INDEX_NAME = 'uk_event_group'
                     AND NON_UNIQUE = 0) THEN
        ALTER TABLE t_sett_mq_consume
            ADD UNIQUE KEY uk_event_group (event_id, consumer_group);
    END IF;
END //
DELIMITER ;
CALL settlement_v7_mq_consume_uk();
DROP PROCEDURE IF EXISTS settlement_v7_mq_consume_uk;
