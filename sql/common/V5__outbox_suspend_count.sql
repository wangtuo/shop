-- =============================================================================
-- V5__outbox_suspend_count.sql — outbox 挂起消息「慢车道自动重排队道」
--
-- 背景：V3 中投递超过 max-retry 的事件置 status=2 后只能人工 requeue；broker
-- 较长时间不可用会积压一批本可在恢复后自愈的挂起事件。新增 suspend_count 记录
-- 进入挂起的累计次数，OutboxRelayJob 的慢车道任务据此做「有上限的自动重放行」，
-- 超过上限的真正死信保留 status=2 并由日志告警转人工/对账。
--
-- 每个业务库各执行一次（shop_user / shop_product / shop_marketing / shop_order /
-- shop_pay / shop_settlement / shop_aftersale）。用 DATABASE() 守卫，幂等可重复执行。
-- =============================================================================

DROP PROCEDURE IF EXISTS outbox_v5_add_suspend_count;
DELIMITER //
CREATE PROCEDURE outbox_v5_add_suspend_count()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 't_mq_outbox'
                     AND COLUMN_NAME = 'suspend_count') THEN
        ALTER TABLE t_mq_outbox
            ADD COLUMN suspend_count INT NOT NULL DEFAULT 0
                COMMENT '进入挂起(status=2)累计次数：慢车道自动重排队道据此封顶，超限告警转人工'
                AFTER retry_count;
    END IF;
END //
DELIMITER ;
CALL outbox_v5_add_suspend_count();
DROP PROCEDURE IF EXISTS outbox_v5_add_suspend_count;
