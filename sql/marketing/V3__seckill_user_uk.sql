-- =============================================================================
-- V3__seckill_user_uk.sql — 秒杀「每人限参与一次」并发强约束（P2-7 修复）
--
-- 背景：t_seckill_order 原有 idx_activity_user(activity_id,user_id) 仅普通索引，
-- “每人限 1 件”靠下单前 select count 校验（TOCTOU），两个并发待支付单可同时通过。
-- 加唯一键后，并发第二个事务在 INSERT 处撞 UK 回滚，应用层按「已参与」友好报错
-- （SeckillService#handleInsertDuplicate），并同步回补已预占的 Redis 库存（P1-5）。
--
-- 与仓库其他 UK 风格一致（见 sql/order/V2__order.sql uk_user_sku）：
-- 唯一键含软删除列 deleted，软删后可再次参与。
-- uk_order_no(order_no) 保留：一个 orderNo 对应一条秒杀订单记录。
--
-- 上线前需确认无重复数据（本次上线时本地 shop_marketing 查询为 0 行）：
--   SELECT activity_id, user_id, COUNT(*) c
--   FROM t_seckill_order WHERE deleted = 0
--   GROUP BY activity_id, user_id HAVING c > 1;
-- 若存在重复，需先人工保留最早一条、软删其余，再执行本变更。
-- =============================================================================

ALTER TABLE t_seckill_order
    DROP INDEX idx_activity_user,
    ADD UNIQUE KEY uk_activity_user (activity_id, user_id, deleted);
