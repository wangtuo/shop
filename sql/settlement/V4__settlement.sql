-- =====================================================================
-- V4__settlement.sql — shop-settlement-service P1-1 / P1-10 修复 DDL
-- 幂等可重复执行。
--
-- 1) t_mq_outbox：transactional outbox 本地消息表（与 sql/common/V3__outbox.sql 同构，
--    shop_settlement 库内必须存在；OutboxRelayJob 每 2s 投递，至少一次，消费端按 bizNo 幂等）。
-- 2) t_sett_clearing_reverse 增加三档瀑布字段：
--    from_available_fen 商户部分取自可提现余额（P1-10 新增的中间档）
--    shortfall_fen     三档合计仍不足、挂起待追讨的缺口金额
--    status            1 已全额扣回 2 部分扣回（缺口挂起，不无限重试）
-- =====================================================================
USE shop_settlement;

CREATE TABLE IF NOT EXISTS t_mq_outbox (
    id            BIGINT       NOT NULL COMMENT '雪花ID（由 IdGenerator 生成）',
    topic         VARCHAR(128) NOT NULL COMMENT 'RocketMQ topic',
    tag           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '消息 tag',
    biz_key       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '业务幂等键（订单号/支付号等）',
    body_json     MEDIUMTEXT   NOT NULL COMMENT '事件 JSON（与直发同一序列化口径）',
    deliver_at    DATETIME(3)  NOT NULL COMMENT '最早投递时间（延时消息=创建+延迟，其余=创建时刻）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待投递 1已投递 2挂起',
    retry_count   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    last_error    VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '最近一次投递错误摘要',
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status_deliver (status, deliver_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'MQ 本地消息表（outbox）';

-- ---------------------------------------------------------------------
-- 冲正明细三档瀑布字段（information_schema 守卫，可重复执行）
-- ---------------------------------------------------------------------
DROP PROCEDURE IF EXISTS settlement_v3_add_columns;
DELIMITER //
CREATE PROCEDURE settlement_v3_add_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'shop_settlement'
                     AND TABLE_NAME = 't_sett_clearing_reverse'
                     AND COLUMN_NAME = 'from_available_fen') THEN
        ALTER TABLE t_sett_clearing_reverse
            ADD COLUMN from_available_fen BIGINT NOT NULL DEFAULT 0
                COMMENT '商户部分取自可提现余额的金额（分）' AFTER from_pending_fen;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'shop_settlement'
                     AND TABLE_NAME = 't_sett_clearing_reverse'
                     AND COLUMN_NAME = 'shortfall_fen') THEN
        ALTER TABLE t_sett_clearing_reverse
            ADD COLUMN shortfall_fen BIGINT NOT NULL DEFAULT 0
                COMMENT '三档合计仍不足的挂起追讨缺口（分）' AFTER from_deposit_fen;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = 'shop_settlement'
                     AND TABLE_NAME = 't_sett_clearing_reverse'
                     AND COLUMN_NAME = 'status') THEN
        ALTER TABLE t_sett_clearing_reverse
            ADD COLUMN status TINYINT NOT NULL DEFAULT 1
                COMMENT '冲正状态：1已全额扣回 2部分扣回（缺口挂起待追讨）' AFTER shortfall_fen,
            ADD KEY idx_status (status);
    END IF;
END //
DELIMITER ;
CALL settlement_v3_add_columns();
DROP PROCEDURE IF EXISTS settlement_v3_add_columns;
