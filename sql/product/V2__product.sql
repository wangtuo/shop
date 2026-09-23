-- =====================================================================
-- 商品域 DDL（shop-product-service，schema shop_product，Redis DB=1）
-- 对应 design.md 3.1~3.5、CONTRACTS.md §4/§5、API_CONTRACTS.md §2
-- MySQL 8.x，utf8mb4
-- =====================================================================

CREATE DATABASE IF NOT EXISTS shop_product DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_product;

-- ---------------------------------------------------------------------
-- 类目树（三级：pid 层级 + level + sort）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_category (
    id                 BIGINT       NOT NULL COMMENT '类目 ID（雪花）',
    pid                BIGINT       NOT NULL DEFAULT 0 COMMENT '父类目 ID，0 表示一级类目',
    level              TINYINT      NOT NULL COMMENT '层级：1 一级 2 二级 3 三级',
    name               VARCHAR(64)  NOT NULL COMMENT '类目名称',
    icon               VARCHAR(512) DEFAULT NULL COMMENT '类目图标 URL',
    sort               INT          NOT NULL DEFAULT 0 COMMENT '同级排序，升序',
    status             TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 停用 1 启用',
    attr_template_json JSON         DEFAULT NULL COMMENT '类目属性模板：{keyAttrs:[关键属性],saleAttrs:[销售属性],attrs:[非关键属性]}',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 否 1 是',
    PRIMARY KEY (id),
    KEY idx_pid_sort (pid, sort, deleted),
    KEY idx_level (level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品三级类目树';

-- ---------------------------------------------------------------------
-- 品牌
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_brand (
    id          BIGINT       NOT NULL COMMENT '品牌 ID（雪花）',
    name        VARCHAR(128) NOT NULL COMMENT '品牌名称',
    logo        VARCHAR(512) DEFAULT NULL COMMENT '品牌 LOGO URL',
    initial     VARCHAR(8)   DEFAULT NULL COMMENT '品牌首字母（A-Z）',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序，升序',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 停用 1 启用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删除；删除时写入该行 id（雪花），配合 uk(name,deleted) 支持同名品牌删除后重建',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name, deleted),
    KEY idx_initial_sort (initial, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品品牌';

-- ---------------------------------------------------------------------
-- SPU
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_spu (
    id                   BIGINT       NOT NULL COMMENT 'SPU ID（雪花）',
    merchant_id          BIGINT       NOT NULL COMMENT '所属商家 ID',
    shop_id              BIGINT       NOT NULL COMMENT '所属店铺 ID',
    name                 VARCHAR(120) NOT NULL COMMENT '商品名称',
    brand_id             BIGINT       NOT NULL COMMENT '品牌 ID',
    category3_id         BIGINT       NOT NULL COMMENT '三级类目 ID',
    main_image           VARCHAR(512) DEFAULT NULL COMMENT '主图 URL',
    images_json          JSON         DEFAULT NULL COMMENT 'SPU 轮播图集 URL 数组',
    detail_json          JSON         DEFAULT NULL COMMENT '商品详情（富文本/详情图结构）',
    attrs_json           JSON         DEFAULT NULL COMMENT 'SPU 属性快照（关键/非关键属性键值）',
    status               TINYINT      NOT NULL DEFAULT 0 COMMENT '商品状态：0 草稿 1 待审核 2 审核拒绝 3 已上架 4 已下架 5 售罄 6 违规下架 7 已删除',
    audit_remark         VARCHAR(200) DEFAULT NULL COMMENT '最近一次审核备注/违规原因',
    auditor_id           BIGINT       DEFAULT NULL COMMENT '审核人（平台运营）ID',
    audit_time           DATETIME     DEFAULT NULL COMMENT '审核时间',
    on_sale_time         DATETIME     DEFAULT NULL COMMENT '最近上架时间',
    sales                BIGINT       NOT NULL DEFAULT 0 COMMENT '累计销量',
    good_comment_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '好评数（综合星级 4、5 星）',
    total_comment_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '总评价数',
    good_rate            DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '好评率=好评数/总评价数',
    version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 否 1 是',
    PRIMARY KEY (id),
    KEY idx_merchant_status (merchant_id, status, deleted),
    KEY idx_shop (shop_id),
    KEY idx_category_status (category3_id, status, deleted),
    KEY idx_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品 SPU';

-- ---------------------------------------------------------------------
-- SKU（五价 + 库存三栏 + 残次仓 + 预警阈值 + 规格/条码/重量体积）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_sku (
    id                   BIGINT       NOT NULL COMMENT 'SKU ID（雪花）',
    spu_id               BIGINT       NOT NULL COMMENT '所属 SPU ID',
    merchant_id          BIGINT       NOT NULL COMMENT '所属商家 ID（冗余归属鉴权）',
    shop_id              BIGINT       NOT NULL COMMENT '所属店铺 ID（冗余）',
    sku_code             VARCHAR(64)  NOT NULL COMMENT 'SKU 编码（商家维度唯一）',
    sku_name             VARCHAR(180) NOT NULL COMMENT 'SKU 名称（冗余 SPU 名）',
    spec_text            VARCHAR(255) NOT NULL DEFAULT '' COMMENT '规格组合文本，如 颜色:红色;尺码:M',
    image                VARCHAR(512) DEFAULT NULL COMMENT 'SKU 主图 URL',
    barcode              VARCHAR(32)  DEFAULT NULL COMMENT '商品条码（69 码）',
    weight_gram          INT          NOT NULL DEFAULT 0 COMMENT '重量（克），用于运费计算',
    volume_cc            INT          NOT NULL DEFAULT 0 COMMENT '体积（立方厘米），用于运费计算',
    category3_id         BIGINT       NOT NULL COMMENT '三级类目 ID（冗余）',
    market_price_fen     BIGINT       NOT NULL DEFAULT 0 COMMENT '原价/吊牌价（分）',
    sale_price_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '销售价（分），日常售价',
    member_price_fen     BIGINT       DEFAULT NULL COMMENT '会员价（分），空表示无会员价',
    promotion_price_fen  BIGINT       DEFAULT NULL COMMENT '促销价（分），空表示无促销',
    seckill_price_fen    BIGINT       DEFAULT NULL COMMENT '秒杀价（分），空表示无秒杀',
    cost_price_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '成本价（分）',
    available_stock      BIGINT       NOT NULL DEFAULT 0 COMMENT '可售库存',
    locked_stock         BIGINT       NOT NULL DEFAULT 0 COMMENT '锁定库存（下单未支付，TCC-try）',
    occupied_stock       BIGINT       NOT NULL DEFAULT 0 COMMENT '占用库存（已支付待发货，TCC-confirm）',
    defect_stock         BIGINT       NOT NULL DEFAULT 0 COMMENT '残次库存（质量问题退货入库，不可售）',
    warn_threshold       BIGINT       NOT NULL DEFAULT 10 COMMENT '库存预警阈值，默认 10',
    presale_flag         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否预售：0 否 1 是',
    stock_type           TINYINT      NOT NULL DEFAULT 1 COMMENT '库存类型：1 普通 2 预售 3 秒杀 4 拼团',
    status               TINYINT      NOT NULL DEFAULT 0 COMMENT '商品状态（随 SPU：0-7）',
    version              INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted              BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删除；删除时写入该行 id（雪花），配合 uk(sku_code,deleted) 支持同编码 SKU 删除后重建',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_code (sku_code, deleted),
    KEY idx_spu (spu_id, deleted),
    KEY idx_merchant (merchant_id),
    KEY idx_status_available (status, available_stock)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品 SKU 与库存';

-- ---------------------------------------------------------------------
-- 库存单据流水：order_no + sku + type 唯一约束保证 TCC 幂等
-- status: 0 锁定中 1 已扣减 2 已释放 3 已回库
-- type:   1 普通 2 预售 3 秒杀 4 拼团
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_stock_log (
    id            BIGINT      NOT NULL COMMENT '流水 ID（雪花）',
    order_no      VARCHAR(32) NOT NULL COMMENT '业务订单号（18 位，幂等键）',
    sku_id        BIGINT      NOT NULL COMMENT 'SKU ID',
    spu_id        BIGINT      NOT NULL DEFAULT 0 COMMENT 'SPU ID（冗余）',
    merchant_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '商家 ID（冗余）',
    type          TINYINT     NOT NULL DEFAULT 1 COMMENT '库存类型：1 普通 2 预售 3 秒杀 4 拼团',
    qty           INT         NOT NULL COMMENT '操作数量（正整数）',
    status        TINYINT     NOT NULL DEFAULT 0 COMMENT '流水状态：0 锁定中 1 已扣减 2 已释放 3 已回库',
    return_reason TINYINT     DEFAULT NULL COMMENT '回库原因：1 买家责任 2 质量问题 3 换货',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（锁定时间）',
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间（最近 TCC 状态变更）',
    deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 否 1 是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_sku_type (order_no, sku_id, type),
    KEY idx_sku (sku_id),
    KEY idx_order_status (order_no, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='库存 TCC 单据流水';

-- ---------------------------------------------------------------------
-- 库存预警记录（STOCK_WARNING 事件落表）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_stock_warning (
    id          BIGINT   NOT NULL COMMENT '预警 ID（雪花）',
    sku_id      BIGINT   NOT NULL COMMENT 'SKU ID',
    spu_id      BIGINT   NOT NULL COMMENT 'SPU ID',
    merchant_id BIGINT   NOT NULL COMMENT '商家 ID',
    available   BIGINT   NOT NULL COMMENT '触发时可售库存',
    threshold   BIGINT   NOT NULL COMMENT '预警阈值',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预警时间',
    PRIMARY KEY (id),
    KEY idx_sku_time (sku_id, create_time),
    KEY idx_merchant_time (merchant_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='库存预警记录';

-- ---------------------------------------------------------------------
-- 商品评价（主评 + 一次追评 + 一次商家回复）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_comment (
    id                  BIGINT       NOT NULL COMMENT '评价 ID（雪花）',
    comment_no          VARCHAR(32)  NOT NULL COMMENT '评价单号（CM+雪花，业务幂等展示号）',
    order_no            VARCHAR(32)  NOT NULL COMMENT '业务订单号',
    user_id             BIGINT       NOT NULL COMMENT '评价用户 ID',
    merchant_id         BIGINT       NOT NULL COMMENT '商家 ID（归属鉴权）',
    spu_id              BIGINT       NOT NULL COMMENT 'SPU ID',
    sku_id              BIGINT       NOT NULL COMMENT 'SKU ID',
    quality_star        TINYINT      NOT NULL COMMENT '商品质量星级：1-5',
    logistics_star      TINYINT      NOT NULL COMMENT '物流服务星级：1-5',
    service_star        TINYINT      NOT NULL COMMENT '服务态度星级：1-5',
    content             VARCHAR(500) NOT NULL COMMENT '评价文字（10-500 字，敏感词过滤后）',
    images_json         JSON         DEFAULT NULL COMMENT '评价图片 URL 数组（最多 9 张）',
    video_url           VARCHAR(512) DEFAULT NULL COMMENT '评价视频 URL（最多 1 个）',
    video_duration_sec  INT          NOT NULL DEFAULT 0 COMMENT '评价视频时长（秒，<=30）',
    append_content      VARCHAR(500) DEFAULT NULL COMMENT '追评文字（10-500 字）',
    append_time         DATETIME     DEFAULT NULL COMMENT '追评时间',
    reply_content       VARCHAR(300) DEFAULT NULL COMMENT '商家回复（每条仅一次）',
    reply_time          DATETIME     DEFAULT NULL COMMENT '商家回复时间',
    status              TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 正常 2 平台屏蔽',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（主评时间）',
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 否 1 是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_comment_no (comment_no),
    UNIQUE KEY uk_order_sku (order_no, sku_id),
    KEY idx_spu_status (spu_id, status, deleted),
    KEY idx_merchant (merchant_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品评价';

-- ---------------------------------------------------------------------
-- MQ 消费流水（event_id 幂等；消费处理与流水插入同一事务）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_product_mq_consume (
    id          BIGINT       NOT NULL COMMENT '流水 ID（雪花）',
    event_id    VARCHAR(64)  NOT NULL COMMENT '事件唯一 ID（幂等键）',
    topic       VARCHAR(64)  NOT NULL COMMENT 'MQ Topic',
    biz_no      VARCHAR(64)  DEFAULT NULL COMMENT '业务单号（订单号/售后单号）',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '处理状态：1 成功',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='商品域 MQ 消费流水';
