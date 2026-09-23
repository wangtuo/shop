-- =============================================================================
-- V3__user_gap.sql — user 域 W0.5 缺口 DDL（GAP_PLAN_USER.md §3，卡 B6-b）
--
-- 仅新增 1 张表：t_user_share_log（用户分享行为流水，request_no 客户端幂等）。
-- outbox 已由 sql/common/V3__outbox.sql 覆盖 shop_user，本文件不重建。
-- 幂等可重复执行：DROP PROCEDURE + information_schema.TABLES 守卫 + CALL。
-- =============================================================================

USE shop_user;

DROP PROCEDURE IF EXISTS p_user_v3_create_share_log;
DELIMITER //
CREATE PROCEDURE p_user_v3_create_share_log()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 't_user_share_log') THEN
        CREATE TABLE t_user_share_log (
            id            BIGINT       NOT NULL,
            user_id       BIGINT       NOT NULL,
            request_no    VARCHAR(64)  NOT NULL COMMENT '客户端幂等号',
            target_type   TINYINT      NOT NULL COMMENT '分享目标类型：1商品 2活动 3其他',
            target_id     VARCHAR(64)           DEFAULT NULL COMMENT '目标ID（可空）',
            points_earned BIGINT       NOT NULL DEFAULT 0 COMMENT '本次实际入账积分（日限 clamp 后可能为0）',
            share_time    DATETIME     NOT NULL,
            create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY uk_request_no (request_no),
            KEY idx_user_time (user_id, share_time)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户分享行为流水';
    END IF;
END //
DELIMITER ;
CALL p_user_v3_create_share_log();
DROP PROCEDURE IF EXISTS p_user_v3_create_share_log;
