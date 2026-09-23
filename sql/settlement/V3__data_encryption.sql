-- =============================================================================
-- V3__data_encryption.sql — 收款账号/姓名 AES-GCM 密文落库列宽扩容（M-1）
--
-- 密文格式 enc:v1: + Base64(IV12 ‖ 明文 ‖ TAG16)：
--   128 字符账号 → 密文约 215 字符；64 字符 utf8mb4 姓名最坏约 387 字符，统一 512。
-- 幂等可重复执行；历史明文行由 DataCipher 兼容读取（无 enc:v1: 前缀原样返回），
-- 明文→密文的数据迁移另行执行。
-- =============================================================================

ALTER TABLE t_sett_withdraw
    MODIFY COLUMN channel_account VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款账号（AES-GCM密文 enc:v1:）',
    MODIFY COLUMN account_name    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款人姓名（AES-GCM密文 enc:v1:）';

ALTER TABLE t_sett_withdraw_auto_config
    MODIFY COLUMN channel_account VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款账号（AES-GCM密文 enc:v1:）',
    MODIFY COLUMN account_name    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款人姓名（AES-GCM密文 enc:v1:）';
