-- =============================================================================
-- V5__marketing_gap.sql — marketing 域 W0.5 缺口 DDL（GAP_PLAN_MARKETING.md §3 D1）
--
-- 覆盖：B2 三表审核列、B6 新人礼包券标记、秒杀到点自动结束、
--       P1-2/B4 t_stock_reconcile_log（scope 1秒杀Redis/DB 2预占回补）、
--       B1 t_groupbuy_event_todo、B3 t_lottery_prize_stock / t_bargain_help。
-- 不含 MASTER §2.1 第 4/5 条已作废的 MARKETING D2/D3。
-- 库：shop_marketing；information_schema 守卫，幂等可重复执行。
-- =============================================================================

USE shop_marketing;

DROP PROCEDURE IF EXISTS marketing_v5_gap;
DELIMITER //
CREATE PROCEDURE marketing_v5_gap()
BEGIN
    -- 1) B2 审核流：促销/券/活动三表加审核态与审核留痕
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_promo'
                     AND COLUMN_NAME='audit_status') THEN
        ALTER TABLE t_promo
            ADD COLUMN audit_status TINYINT NOT NULL DEFAULT 2
                COMMENT '审核态：0草稿 1待审核 2通过 3驳回（存量直接置2通过）' AFTER status,
            ADD COLUMN submit_time DATETIME NULL COMMENT '商户提交时间' AFTER audit_status,
            ADD COLUMN audit_user_id BIGINT NULL COMMENT '审核人（平台运营）ID' AFTER submit_time,
            ADD COLUMN audit_time DATETIME NULL COMMENT '审核时间' AFTER audit_user_id,
            ADD COLUMN audit_remark VARCHAR(255) NOT NULL DEFAULT '' COMMENT '驳回原因' AFTER audit_time,
            ADD KEY idx_audit_status (audit_status);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_coupon'
                     AND COLUMN_NAME='audit_status') THEN
        ALTER TABLE t_coupon
            ADD COLUMN audit_status TINYINT NOT NULL DEFAULT 2
                COMMENT '审核态：0草稿 1待审核 2通过 3驳回' AFTER status,
            ADD COLUMN submit_time DATETIME NULL AFTER audit_status,
            ADD COLUMN audit_user_id BIGINT NULL AFTER submit_time,
            ADD COLUMN audit_time DATETIME NULL AFTER audit_user_id,
            ADD COLUMN audit_remark VARCHAR(255) NOT NULL DEFAULT '' AFTER audit_time,
            ADD COLUMN new_user_gift TINYINT NOT NULL DEFAULT 0
                COMMENT 'B6 新人礼包标记：0否 1是（issue_way=3 的券中仅标记券随注册自动发放）' AFTER audit_remark,
            ADD KEY idx_audit_status (audit_status);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_activity'
                     AND COLUMN_NAME='audit_status') THEN
        ALTER TABLE t_activity
            ADD COLUMN audit_status TINYINT NOT NULL DEFAULT 2
                COMMENT '审核态：0草稿 1待审核 2通过 3驳回' AFTER status,
            ADD COLUMN submit_time DATETIME NULL AFTER audit_status,
            ADD COLUMN audit_user_id BIGINT NULL AFTER submit_time,
            ADD COLUMN audit_time DATETIME NULL AFTER audit_user_id,
            ADD COLUMN audit_remark VARCHAR(255) NOT NULL DEFAULT '' AFTER audit_time,
            ADD COLUMN auto_end TINYINT NOT NULL DEFAULT 1
                COMMENT 'B2 到点自动结束：0否 1是（秒杀默认1）' AFTER audit_remark,
            ADD KEY idx_audit_status (audit_status);
    END IF;

    -- 2) B4 秒杀每用户可购件数：t_seckill_order 增件数汇总索引所需列已齐(activity_id,user_id)，
    --    限购 N 件通过 SUM(qty) 条件实现，不加列；为对账自愈加修复动作留痕表
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_stock_reconcile_log') THEN
        CREATE TABLE t_stock_reconcile_log (
            id            BIGINT NOT NULL COMMENT '雪花主键',
            scope         TINYINT NOT NULL COMMENT '对账域：1秒杀Redis/DB 2秒杀预占回补 3普通库存(product域另建)',
            activity_id   BIGINT NULL COMMENT '秒杀活动 ID',
            sku_id        BIGINT NULL,
            deviation     BIGINT NOT NULL DEFAULT 0 COMMENT '偏差量（Redis-DB，带符号）',
            action        TINYINT NOT NULL COMMENT '处置：0仅告警 1重建Redis 2自动停售 3释放预占',
            metric_before VARCHAR(500) NOT NULL DEFAULT '' COMMENT '处置前指标 JSON',
            create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_scope_time (scope, create_time),
            KEY idx_activity_sku (activity_id, sku_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存对账自愈留痕（P1-2/B4）';
    END IF;

    -- 3) B1 成团事件受理幂等/待办（TRADE 接口未就绪期间的落地锚点，防止事件空转丢失）
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_groupbuy_event_todo') THEN
        CREATE TABLE t_groupbuy_event_todo (
            id           BIGINT NOT NULL COMMENT '雪花主键',
            event_id     VARCHAR(64) NOT NULL COMMENT 'GroupbuyEvent.eventId',
            group_no     VARCHAR(64) NOT NULL,
            activity_id  BIGINT NOT NULL,
            order_no     VARCHAR(64) NOT NULL,
            user_id      BIGINT NOT NULL,
            leader_flag  TINYINT NOT NULL DEFAULT 0,
            op_type      TINYINT NOT NULL COMMENT '3成团 4失败',
            handle_status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1已通知订单域 2失败待重试',
            retry_count  INT NOT NULL DEFAULT 0,
            create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_event_id (event_id),
            KEY idx_status (handle_status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拼团事件受理待办（B1，TRADE对接缓冲）';
    END IF;

    -- 4) B3 抽奖奖品库存独立表（权重在 rule_json，发奖库存需独立扣减与留痕；砍价复用 t_bargain_record，无需新表）
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_lottery_prize_stock') THEN
        CREATE TABLE t_lottery_prize_stock (
            id           BIGINT NOT NULL COMMENT '雪花主键',
            activity_id  BIGINT NOT NULL,
            prize_code   VARCHAR(64) NOT NULL,
            prize_name   VARCHAR(128) NOT NULL DEFAULT '',
            prize_type   TINYINT NOT NULL COMMENT '1积分 2优惠券 3谢谢参与',
            coupon_id    BIGINT NULL COMMENT 'prize_type=2 时发放的券模板 ID',
            points       INT NOT NULL DEFAULT 0 COMMENT 'prize_type=1 时积分数量',
            weight       INT NOT NULL DEFAULT 0 COMMENT '中奖权重',
            total_stock  INT NOT NULL DEFAULT 0 COMMENT '库存；0=不限',
            issued_count INT NOT NULL DEFAULT 0 COMMENT '已发数量',
            deleted      TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            UNIQUE KEY uk_activity_prize (activity_id, prize_code, deleted),
            KEY idx_activity (activity_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='抽奖奖品配置与库存（B3）';
    END IF;

    -- 5) B3 砍价帮砍留痕（同一帮砍人对同一记录仅一次）
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_bargain_help') THEN
        CREATE TABLE t_bargain_help (
            id               BIGINT NOT NULL COMMENT '雪花主键',
            record_id        BIGINT NOT NULL COMMENT '砍价记录 ID',
            helper_user_id   BIGINT NOT NULL COMMENT '帮砍用户 ID',
            cut_fen          BIGINT NOT NULL COMMENT '本次砍下金额（分）',
            create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted          TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            UNIQUE KEY uk_record_helper (record_id, helper_user_id, deleted),
            KEY idx_helper (helper_user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='砍价帮砍留痕（B3）';
    END IF;
END //
DELIMITER ;
CALL marketing_v5_gap();
DROP PROCEDURE IF EXISTS marketing_v5_gap;
