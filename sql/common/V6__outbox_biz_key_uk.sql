-- =============================================================================
-- V6__outbox_biz_key_uk.sql — outbox 加 UK(topic, biz_key)（GAP_PLAN_PLATFORM §3.1，Z5）
--
-- t_mq_outbox 由 sql/common/V3__outbox.sql 在每个业务库各部署一份，本文件同样对
-- 7 个业务库逐段执行：shop_user / shop_product / shop_marketing / shop_order /
-- shop_pay / shop_settlement / shop_aftersale。
--
-- 口径（PLATFORM §3.1 方案 A，按现网数据定稿）：UK(topic, tag, biz_key)。
-- 实测各业务库均存在「同 topic + 同 biz_key、不同 tag」的合法多行事件
-- （如 shop_seckill_event tag=1/3、shop_pay_result tag=check/result、
-- shop_aftersale_changed 多个 aftersale 状态），裸 UK(topic,biz_key) 会误杀，
-- 故取三元组；配合 relay 至少一次投递，消费端按业务单号幂等。
-- 建索引前先把存量空 biz_key 回填为 legacy:<id>，避免历史空键撞唯一约束。
-- MySQL 8 下 ADD UNIQUE KEY 走 INPLACE/在线 DDL，允许并发 DML。
-- information_schema.STATISTICS 守卫，幂等可重复执行。
-- 执行顺序：V3 → V5 → V6。
-- =============================================================================

-- ---------------- shop_user ----------------
USE shop_user;
UPDATE t_mq_outbox SET biz_key = CONCAT('legacy:', id) WHERE biz_key = '' OR biz_key IS NULL;
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
DELIMITER //
CREATE PROCEDURE common_v6_outbox_uk()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_tag_bizkey') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_bizkey') THEN
            ALTER TABLE t_mq_outbox DROP INDEX uk_topic_bizkey;
        END IF;
        ALTER TABLE t_mq_outbox ADD UNIQUE KEY uk_topic_tag_bizkey (topic, tag, biz_key);
    END IF;
END //
DELIMITER ;
CALL common_v6_outbox_uk();
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;

-- ---------------- shop_product ----------------
USE shop_product;
UPDATE t_mq_outbox SET biz_key = CONCAT('legacy:', id) WHERE biz_key = '' OR biz_key IS NULL;
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
DELIMITER //
CREATE PROCEDURE common_v6_outbox_uk()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_tag_bizkey') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_bizkey') THEN
            ALTER TABLE t_mq_outbox DROP INDEX uk_topic_bizkey;
        END IF;
        ALTER TABLE t_mq_outbox ADD UNIQUE KEY uk_topic_tag_bizkey (topic, tag, biz_key);
    END IF;
END //
DELIMITER ;
CALL common_v6_outbox_uk();
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;

-- ---------------- shop_marketing ----------------
USE shop_marketing;
UPDATE t_mq_outbox SET biz_key = CONCAT('legacy:', id) WHERE biz_key = '' OR biz_key IS NULL;
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
DELIMITER //
CREATE PROCEDURE common_v6_outbox_uk()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_tag_bizkey') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_bizkey') THEN
            ALTER TABLE t_mq_outbox DROP INDEX uk_topic_bizkey;
        END IF;
        ALTER TABLE t_mq_outbox ADD UNIQUE KEY uk_topic_tag_bizkey (topic, tag, biz_key);
    END IF;
END //
DELIMITER ;
CALL common_v6_outbox_uk();
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;

-- ---------------- shop_order ----------------
USE shop_order;
UPDATE t_mq_outbox SET biz_key = CONCAT('legacy:', id) WHERE biz_key = '' OR biz_key IS NULL;
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
DELIMITER //
CREATE PROCEDURE common_v6_outbox_uk()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_tag_bizkey') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_bizkey') THEN
            ALTER TABLE t_mq_outbox DROP INDEX uk_topic_bizkey;
        END IF;
        ALTER TABLE t_mq_outbox ADD UNIQUE KEY uk_topic_tag_bizkey (topic, tag, biz_key);
    END IF;
END //
DELIMITER ;
CALL common_v6_outbox_uk();
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;

-- ---------------- shop_pay ----------------
USE shop_pay;
UPDATE t_mq_outbox SET biz_key = CONCAT('legacy:', id) WHERE biz_key = '' OR biz_key IS NULL;
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
DELIMITER //
CREATE PROCEDURE common_v6_outbox_uk()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_tag_bizkey') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_bizkey') THEN
            ALTER TABLE t_mq_outbox DROP INDEX uk_topic_bizkey;
        END IF;
        ALTER TABLE t_mq_outbox ADD UNIQUE KEY uk_topic_tag_bizkey (topic, tag, biz_key);
    END IF;
END //
DELIMITER ;
CALL common_v6_outbox_uk();
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;

-- ---------------- shop_settlement ----------------
USE shop_settlement;
UPDATE t_mq_outbox SET biz_key = CONCAT('legacy:', id) WHERE biz_key = '' OR biz_key IS NULL;
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
DELIMITER //
CREATE PROCEDURE common_v6_outbox_uk()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_tag_bizkey') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_bizkey') THEN
            ALTER TABLE t_mq_outbox DROP INDEX uk_topic_bizkey;
        END IF;
        ALTER TABLE t_mq_outbox ADD UNIQUE KEY uk_topic_tag_bizkey (topic, tag, biz_key);
    END IF;
END //
DELIMITER ;
CALL common_v6_outbox_uk();
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;

-- ---------------- shop_aftersale ----------------
USE shop_aftersale;
UPDATE t_mq_outbox SET biz_key = CONCAT('legacy:', id) WHERE biz_key = '' OR biz_key IS NULL;
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
DELIMITER //
CREATE PROCEDURE common_v6_outbox_uk()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_tag_bizkey') THEN
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_mq_outbox'
                     AND INDEX_NAME = 'uk_topic_bizkey') THEN
            ALTER TABLE t_mq_outbox DROP INDEX uk_topic_bizkey;
        END IF;
        ALTER TABLE t_mq_outbox ADD UNIQUE KEY uk_topic_tag_bizkey (topic, tag, biz_key);
    END IF;
END //
DELIMITER ;
CALL common_v6_outbox_uk();
DROP PROCEDURE IF EXISTS common_v6_outbox_uk;
