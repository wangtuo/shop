-- V4：平台营销活动 merchant_id 哨兵 0
-- 背景：平台管理员后台创建秒杀/拼团/预售活动时 merchant_id 为空，t_activity.merchant_id
--       NOT NULL 且无默认值导致插入 1364（Field 'merchant_id' doesn't have a default value）。
-- 口径：与 t_coupon.merchant_id（0=平台券）对齐，0=平台活动。幂等：MODIFY 可重复执行。
ALTER TABLE t_activity
    MODIFY COLUMN merchant_id BIGINT NOT NULL DEFAULT 0 COMMENT '商户 ID；0=平台活动';
