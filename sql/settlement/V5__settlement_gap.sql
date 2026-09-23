-- =============================================================================
-- V5__settlement_gap.sql — settlement 域 W0.5 缺口 DDL（GAP_PLAN_FUNDS.md §3 D2）
--
-- 覆盖：B11 清算单运费险保费；B10 提现单打款渠道流水/查询补偿、保证金流水
-- 缴费/退还链路列；P0-1 t_sett_shortfall_workorder 穿仓缺口工单。
-- 库：shop_settlement；information_schema 守卫，幂等可重复执行。
-- =============================================================================

USE shop_settlement;

DROP PROCEDURE IF EXISTS settlement_v5_add_columns;
DELIMITER //
CREATE PROCEDURE settlement_v5_add_columns()
BEGIN
    -- 1) 清算单增加运费险保费（平台/保险收入，不退）
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_settlement' AND TABLE_NAME='t_sett_clearing'
                     AND COLUMN_NAME='insurance_premium_fen') THEN
        ALTER TABLE t_sett_clearing
            ADD COLUMN insurance_premium_fen BIGINT NOT NULL DEFAULT 0
                COMMENT '运费险保费（分，平台保险收入，退款不退）' AFTER marketing_subsidy_fen;
    END IF;

    -- 2) 提现单增加真实打款渠道流水与查询补偿列
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_settlement' AND TABLE_NAME='t_sett_withdraw'
                     AND COLUMN_NAME='channel_remit_no') THEN
        ALTER TABLE t_sett_withdraw
            ADD COLUMN channel_remit_no VARCHAR(64) NULL COMMENT '渠道代发流水号' AFTER bank_name,
            ADD COLUMN remit_fail_reason VARCHAR(256) NOT NULL DEFAULT '' COMMENT '打款失败原因' AFTER channel_remit_no,
            ADD COLUMN last_query_time DATETIME NULL COMMENT '最近打款查询时间' AFTER remit_fail_reason,
            ADD COLUMN query_count INT NOT NULL DEFAULT 0 COMMENT '打款主动查询次数' AFTER last_query_time,
            ADD UNIQUE KEY uk_channel_remit_no (channel_remit_no);
    END IF;

    -- 3) 保证金流水支持真实缴费/退还链路
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_settlement' AND TABLE_NAME='t_sett_deposit_log'
                     AND COLUMN_NAME='status') THEN
        ALTER TABLE t_sett_deposit_log
            ADD COLUMN status TINYINT NOT NULL DEFAULT 20
                COMMENT '单据状态：10处理中(缴费待支付/退还打款中) 20成功 30失败' AFTER log_type,
            ADD COLUMN pay_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '缴费支付单号（log_type=10）' AFTER status,
            ADD COLUMN channel_remit_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '退还打款渠道流水号（log_type=40）' AFTER pay_no,
            ADD COLUMN last_query_time DATETIME NULL COMMENT '退还打款最近查询时间' AFTER channel_remit_no,
            ADD KEY idx_status (status),
            ADD KEY idx_pay_no (pay_no);
    END IF;
END //
DELIMITER ;
CALL settlement_v5_add_columns();
DROP PROCEDURE IF EXISTS settlement_v5_add_columns;

-- 4) P0-1 穿仓缺口工单表
CREATE TABLE IF NOT EXISTS t_sett_shortfall_workorder (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id        VARCHAR(64)  NOT NULL COMMENT 'REFUND_SHORTFALL 事件ID（幂等键）',
    reverse_no      VARCHAR(32)  NOT NULL COMMENT '冲正流水号',
    refund_no       VARCHAR(32)  NOT NULL COMMENT '退款单号',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '订单号',
    merchant_id     BIGINT       NOT NULL COMMENT '商户ID',
    shortfall_fen   BIGINT       NOT NULL COMMENT '挂起缺口金额（分）',
    clawed_back_fen BIGINT       NOT NULL DEFAULT 0 COMMENT '已自动补扣金额（分）',
    status          TINYINT      NOT NULL DEFAULT 10 COMMENT '10待处理 20已告警 30已追缴结清 40人工核销',
    alert_count     INT          NOT NULL DEFAULT 0 COMMENT '告警次数',
    remark          VARCHAR(512) NOT NULL DEFAULT '' COMMENT '处理备注',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_merchant_status (merchant_id, status),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款穿仓缺口工单（P0-1）';
