-- =============================================================================
-- V4__catalog_stock.sql — 类目多挂载/属性主数据 + 预售库存与库存流水扩展
--                         （GAP_PLAN_TRADE.md §3.3 + §4.5，MASTER §2.1 裁决 5）
-- 库：shop_product。无 Flyway；建表 CREATE TABLE IF NOT EXISTS，加列/加索引
-- 一律 DROP PROCEDURE + information_schema 守卫；COLUMN 与 STATISTICS 守卫拆分，
-- 幂等可重复执行。
-- =============================================================================

USE shop_product;

-- 1) 虚拟类目多二级挂载：SPU 与类目多对多（主挂载仍由 t_product_spu.category3_id 表达）
CREATE TABLE IF NOT EXISTS t_product_spu_category (
    id            BIGINT   NOT NULL,
    spu_id        BIGINT   NOT NULL COMMENT 'SPU ID',
    category_id   BIGINT   NOT NULL COMMENT '挂载类目 ID（通常为二级虚拟类目，一级也允许）',
    category_level TINYINT NOT NULL COMMENT '挂载类目层级冗余：1/2/3',
    mount_type    TINYINT  NOT NULL DEFAULT 1 COMMENT '挂载类型：1 实体归属 2 虚拟挂载',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_spu_category (spu_id, category_id),
    KEY idx_category (category_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SPU-类目多挂载关系（虚拟类目）';

-- 2) 规格/属性主数据（类目属性模板的可治理版本；attrsJson 继续做商品快照）
CREATE TABLE IF NOT EXISTS t_product_attr_key (
    id            BIGINT      NOT NULL,
    category_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '所属类目 ID，0=全局通用属性',
    name          VARCHAR(64) NOT NULL COMMENT '属性名（颜色/尺码/材质…）',
    attr_type     TINYINT      NOT NULL COMMENT '属性类型：1 关键属性 2 销售规格(SKU) 3 普通属性(SPU)',
    value_type    TINYINT      NOT NULL DEFAULT 1 COMMENT '值类型：1 枚举 2 数值 3 文本',
    value_options JSON         DEFAULT NULL COMMENT '枚举可选值列表',
    unit          VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '数值单位',
    required_flag TINYINT      NOT NULL DEFAULT 0 COMMENT '是否必填',
    sort          INT          NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 1,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (category_id, name, deleted),
    KEY idx_category_type (category_id, attr_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规格/属性主数据';

-- 3) B5：预售库存数量列（现状四仓无预售数量，ProductSku.java:70-88）
DROP PROCEDURE IF EXISTS p_product_add_presale_stock;
DELIMITER //
CREATE PROCEDURE p_product_add_presale_stock()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_product_sku'
          AND COLUMN_NAME = 'presale_stock') THEN
        ALTER TABLE t_product_sku
          ADD COLUMN presale_stock BIGINT NOT NULL DEFAULT 0 COMMENT '预售库存数量（定金支付后扣减池）' AFTER occupied_stock;
    END IF;
END //
DELIMITER ;
CALL p_product_add_presale_stock();
DROP PROCEDURE IF EXISTS p_product_add_presale_stock;

-- 4) B13/B5：流水追加关联单号；状态注释扩到 4 已出账 / 5 预售回补（COLUMN 守卫）
DROP PROCEDURE IF EXISTS p_product_stock_log_add_column;
DELIMITER //
CREATE PROCEDURE p_product_stock_log_add_column()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_product_stock_log'
          AND COLUMN_NAME = 'ref_order_no') THEN
        ALTER TABLE t_product_stock_log
          ADD COLUMN ref_order_no VARCHAR(32) NULL COMMENT '关联单号（尾款流水回指定金单号）' AFTER order_no,
          MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
            COMMENT '流水状态：0 锁定中 1 已扣减(入占用) 2 已释放 3 已回库 4 已出账(占用仓出账) 5 预售回补(尾款违约)';
    END IF;
END //
DELIMITER ;
CALL p_product_stock_log_add_column();
DROP PROCEDURE IF EXISTS p_product_stock_log_add_column;

-- 4b) idx_status_time 独立 STATISTICS 守卫（与加列拆分，兼容中间态；MySQL 8 INPLACE 在线 DDL）
DROP PROCEDURE IF EXISTS p_product_stock_log_add_index;
DELIMITER //
CREATE PROCEDURE p_product_stock_log_add_index()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_product_stock_log'
          AND INDEX_NAME = 'idx_status_time') THEN
        ALTER TABLE t_product_stock_log
          ADD KEY idx_status_time (status, create_time);
    END IF;
END //
DELIMITER ;
CALL p_product_stock_log_add_index();
DROP PROCEDURE IF EXISTS p_product_stock_log_add_index;

-- 5) P1-1/B5：普通库存对账留痕（issue_type 3=预售支付后未扣，MASTER §2.1 裁决 5 命名）
CREATE TABLE IF NOT EXISTS t_product_stock_reconcile_log (
    id           BIGINT       NOT NULL COMMENT '雪花主键',
    order_no     VARCHAR(32)  NULL,
    sku_id       BIGINT       NOT NULL,
    log_id       BIGINT       NULL COMMENT '悬挂的 t_product_stock_log.id',
    issue_type   TINYINT      NOT NULL COMMENT '问题类型：1终态订单残留LOCKED 2无有效订单LOCKED 3预售支付后未扣',
    action       TINYINT      NOT NULL COMMENT '处置：0告警 1自动释放 2补扣',
    detail       VARCHAR(500) NOT NULL DEFAULT '',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_issue_time (issue_type, create_time),
    KEY idx_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='普通库存对账留痕（P1-1/B5）';
