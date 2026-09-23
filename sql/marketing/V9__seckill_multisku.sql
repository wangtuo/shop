-- =============================================================================
-- V9__seckill_multisku.sql — R4-25 秒杀多 SKU 订单模型修复（marketing 域）
--
-- 背景：
--   P1-5 下单链路支持一单多个秒杀 SKU（多 key Lua 原子批量预占、逐 SKU DB 锁定、
--   逐 SKU 发 LOCK 事件），但持久层仍是「每订单一行 + 每用户每场一行」：
--     a) t_seckill_order.uk_order_no 使第二个 SKU 行无法插入；
--     b) V3 加的 uk_activity_user(activity_id,user_id,deleted) 同样拒绝第二行；
--     c) 即使绕过上面两关，逐 SKU 的 LOCK 事件以同 (topic,tag=1,bizKey=orderNo)
--        第二次登记 t_mq_outbox，撞 uk_topic_tag_bizkey 回滚整事务。
--   即多 SKU 秒杀单 100% 落不了单（单测因 mock mapper/outbox 全部假绿）。
--   同时 uk_activity_user 与 C24 可配置 perUserBuyLimit>1（允许同一用户同场次多单
--   累计 N 件）直接矛盾：第二单在 INSERT 处被硬拒。
--
-- 修复：
--   1. t_seckill_order 改为每 (order_no, sku_id) 一行：删 uk_order_no / uk_activity_user，
--      加 uk_order_sku(order_no, sku_id)；
--   2. 新增 t_seckill_user_buy 每用户每场次原子计数行：应用侧以条件更新
--      「total_qty + ? <= limit」行锁占件，替代硬 UK，天然支持可配置限购与并发防超；
--      取消/超时释放时回减计数（口径同原 SUM(status IN 0,1)）。
--
-- 幂等：全部 information_schema / CREATE TABLE IF NOT EXISTS / INSERT IGNORE 守卫，可重复执行。
-- 库：shop_marketing。
-- =============================================================================

USE shop_marketing;

DROP PROCEDURE IF EXISTS marketing_v9_seckill_multisku;
DELIMITER //
CREATE PROCEDURE marketing_v9_seckill_multisku()
BEGIN
    -- 1) 每用户每场次有效件数计数（原子占件/释放回减）
    CREATE TABLE IF NOT EXISTS t_seckill_user_buy (
        id          BIGINT       NOT NULL COMMENT '主键（雪花）',
        activity_id BIGINT       NOT NULL COMMENT '秒杀活动 ID',
        user_id     BIGINT       NOT NULL COMMENT '用户 ID',
        total_qty   INT          NOT NULL DEFAULT 0
            COMMENT '本场次仍有效（0已锁定/1已扣减）的累计件数；订单取消/超时释放时回减',
        create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
        update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
        deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
        PRIMARY KEY (id),
        UNIQUE KEY uk_activity_user (activity_id, user_id, deleted)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
      COMMENT='秒杀每用户每场累计占件计数（可配置限购的并发强约束）';

    -- 存量回填（V9 上线前 t_seckill_order 每订单只有一行）：每组取最小订单行 id 作计数行 id
    INSERT IGNORE INTO t_seckill_user_buy (id, activity_id, user_id, total_qty, create_time, update_time, deleted)
    SELECT MIN(o.id), o.activity_id, o.user_id, COALESCE(SUM(o.qty), 0),
           MIN(o.create_time), MAX(o.update_time), 0
      FROM t_seckill_order o
     WHERE o.deleted = 0 AND o.status IN (0, 1)
     GROUP BY o.activity_id, o.user_id;

    -- 2) t_seckill_order：一订单可含多 SKU → 每 (order_no, sku_id) 一行
    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = 'shop_marketing' AND TABLE_NAME = 't_seckill_order'
                 AND INDEX_NAME = 'uk_order_no') THEN
        ALTER TABLE t_seckill_order DROP INDEX uk_order_no;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
               WHERE TABLE_SCHEMA = 'shop_marketing' AND TABLE_NAME = 't_seckill_order'
                 AND INDEX_NAME = 'uk_activity_user') THEN
        ALTER TABLE t_seckill_order DROP INDEX uk_activity_user;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = 'shop_marketing' AND TABLE_NAME = 't_seckill_order'
                     AND INDEX_NAME = 'uk_order_sku') THEN
        ALTER TABLE t_seckill_order ADD UNIQUE KEY uk_order_sku (order_no, sku_id);
    END IF;
END //
DELIMITER ;
CALL marketing_v9_seckill_multisku();
DROP PROCEDURE IF EXISTS marketing_v9_seckill_multisku;
