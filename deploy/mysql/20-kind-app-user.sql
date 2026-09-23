-- =============================================================================
-- 20-kind-app-user.sql — kind HA 验收专用应用账号（幂等，可重复执行）
--
-- 背景：业务工作负载在 kind 中以 prod profile 启动，框架 fail-fast（R-Z2）
-- 拒绝空/root/长度<8 的数据库口令。因此 kind 不复用 root/root，而是创建
-- 最小权限的应用账号 shop_app（仅七个 shop_* 业务库的 DDL/DML 权限），
-- 与 deploy/kubernetes/kind/05-kind-secret.yaml 的 mysql-username/password 对应。
-- root 仅留给本地运维与 deploy/apply-sql.sh 迁移链路。
--
-- 本文件经 10-apply-sql.sh 的 initdb 挂载在 MySQL 首次初始化时执行，
-- 也由 final-acceptance.sh MIDDLEWARE 阶段对存量实例幂等补执行。
-- 注意：CREATE USER IF NOT EXISTS 不会改既有账号口令；重建账号请先 DROP。
-- =============================================================================
CREATE USER IF NOT EXISTS 'shop_app'@'%' IDENTIFIED BY 'K1nd-ShopApp-2026!x9';
GRANT ALL PRIVILEGES ON shop_user.*       TO 'shop_app'@'%';
GRANT ALL PRIVILEGES ON shop_product.*    TO 'shop_app'@'%';
GRANT ALL PRIVILEGES ON shop_marketing.*  TO 'shop_app'@'%';
GRANT ALL PRIVILEGES ON shop_order.*      TO 'shop_app'@'%';
GRANT ALL PRIVILEGES ON shop_pay.*        TO 'shop_app'@'%';
GRANT ALL PRIVILEGES ON shop_settlement.* TO 'shop_app'@'%';
GRANT ALL PRIVILEGES ON shop_aftersale.*  TO 'shop_app'@'%';
FLUSH PRIVILEGES;
