-- =============================================================================
-- V3__freight_template.sql — 运费模板 + 区域运费规则（GAP_PLAN_TRADE.md §3.2，卡 B7）
--
-- 库：shop_product。建表用 CREATE TABLE IF NOT EXISTS（同 V2 现状风格），
-- 幂等可重复执行。保费不在本表建模（B11/FUNDS 另处理）。
-- =============================================================================

USE shop_product;

-- 运费模板（商家维度，一个店铺可多模板，一个默认模板）
CREATE TABLE IF NOT EXISTS t_product_freight_template (
    id              BIGINT       NOT NULL COMMENT '模板 ID（雪花）',
    merchant_id     BIGINT       NOT NULL COMMENT '商家 ID',
    name            VARCHAR(64)  NOT NULL COMMENT '模板名称',
    charge_type     TINYINT      NOT NULL DEFAULT 1 COMMENT '计费方式：1 按件 2 按重量 3 按体积',
    default_first   INT          NOT NULL DEFAULT 1 COMMENT '默认首件数/重(g)/体积(cm³)单位数',
    default_first_fee   BIGINT  NOT NULL DEFAULT 0 COMMENT '默认首费（分）',
    default_add     INT          NOT NULL DEFAULT 1 COMMENT '默认续件单位数',
    default_add_fee BIGINT       NOT NULL DEFAULT 0 COMMENT '默认续费（分）',
    free_condition_fen  BIGINT  NOT NULL DEFAULT 0 COMMENT '满额包邮门槛（分），0 不包邮',
    is_default      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否店铺默认：0 否 1 是',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0 停用 1 启用',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_merchant_status (merchant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运费模板';

-- 区域运费规则（可配送区域 + 指定区域费率；省/市/区县编码列表 JSON）
CREATE TABLE IF NOT EXISTS t_product_freight_region (
    id              BIGINT       NOT NULL COMMENT '规则 ID（雪花）',
    template_id     BIGINT       NOT NULL COMMENT '运费模板 ID',
    merchant_id     BIGINT       NOT NULL COMMENT '商家 ID（冗余）',
    region_codes    JSON         NOT NULL COMMENT '适用行政区划编码列表（省/市/区，编码以 USER 地址字典为准）',
    first_unit      INT          NOT NULL COMMENT '首件单位数',
    first_fee_fen   BIGINT       NOT NULL COMMENT '首费（分）',
    add_unit        INT          NOT NULL COMMENT '续件单位数',
    add_fee_fen     BIGINT       NOT NULL COMMENT '续费（分）',
    deliverable     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可配送：0 不可配送(拒单) 1 可配送',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_template (template_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='区域运费规则';
