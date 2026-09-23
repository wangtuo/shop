-- =====================================================================
-- 订单域 DDL（shop-order-service，schema shop_order，Redis DB=3）
-- design.md 第五章：购物车 / 下单 / 订单状态机 / 发票
-- 金额单位：分（BIGINT）；逻辑删除 deleted；并发敏感表 version 乐观锁
-- MySQL 8.x
-- =====================================================================

CREATE DATABASE IF NOT EXISTS shop_order DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_order;

-- ---------------------------------------------------------------------
-- 购物车（design 5.4）：user_id + sku_id + deleted 唯一；每用户最多 99 个商品条目
-- deleted 语义：0=未删除；逻辑删除时写入该行 id（雪花，全局唯一），
-- 故历史删除行互不相同，删除后可立即重新加购同一 SKU 而不撞唯一键。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_order_cart;
CREATE TABLE t_order_cart (
    id              BIGINT       NOT NULL COMMENT '雪花主键',
    user_id         BIGINT       NOT NULL COMMENT '用户 ID',
    sku_id          BIGINT       NOT NULL COMMENT 'SKU ID',
    spu_id          BIGINT       NOT NULL DEFAULT 0 COMMENT 'SPU ID（快照）',
    merchant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '商户 ID（快照，按店铺分组使用）',
    shop_id         BIGINT       NOT NULL DEFAULT 0 COMMENT '店铺 ID（快照）',
    sku_name        VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'SKU 名称（加购时快照）',
    spec_text       VARCHAR(256) NOT NULL DEFAULT '' COMMENT '规格文本快照，如「颜色:红色;尺码:M」',
    image           VARCHAR(512) NOT NULL DEFAULT '' COMMENT '商品主图快照',
    price_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '加购时单价（分），用于价格变动提示',
    qty             INT          NOT NULL DEFAULT 1 COMMENT '购买数量（正整数）',
    selected        TINYINT      NOT NULL DEFAULT 1 COMMENT '是否勾选结算：0 否 1 是',
    invalid         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否失效：0 有效 1 失效（下架/售罄/删除）',
    invalid_reason  TINYINT      NOT NULL DEFAULT 0 COMMENT '失效原因：0 未失效 1 已下架 2 售罄或库存为 0 3 已删除',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删除；删除时写入该行 id（雪花），保证历史删除行唯一',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sku (user_id, sku_id, deleted),
    KEY idx_user_selected (user_id, selected),
    KEY idx_user_shop (user_id, shop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='购物车条目（用户+SKU 唯一）';

-- ---------------------------------------------------------------------
-- 收藏夹（购物车「移入收藏」目标表）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_order_favorite;
CREATE TABLE t_order_favorite (
    id           BIGINT       NOT NULL COMMENT '雪花主键',
    user_id      BIGINT       NOT NULL COMMENT '用户 ID',
    sku_id       BIGINT       NOT NULL COMMENT 'SKU ID',
    spu_id       BIGINT       NOT NULL DEFAULT 0 COMMENT 'SPU ID（快照）',
    merchant_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '商户 ID（快照）',
    shop_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '店铺 ID（快照）',
    sku_name     VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'SKU 名称快照',
    spec_text    VARCHAR(256) NOT NULL DEFAULT '' COMMENT '规格文本快照',
    image        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '商品主图快照',
    price_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '收藏时单价（分）',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      BIGINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=未删除；删除时写入该行 id（雪花），保证历史删除行唯一',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_sku (user_id, sku_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户收藏夹';

-- ---------------------------------------------------------------------
-- 订单主表（design 5.1.1；订单号规则 CONTRACTS §6）
-- 一单一店铺：跨店铺结算由购物车视图按店铺拆成多单
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_order_order;
CREATE TABLE t_order_order (
    id                     BIGINT       NOT NULL COMMENT '雪花主键',
    order_no               VARCHAR(32)  NOT NULL COMMENT '订单号（18 位：YYMMDD+业务类型2位+用户ID后4位+6位序列；雪花兜底除外）',
    user_id                BIGINT       NOT NULL COMMENT '下单用户 ID',
    user_nickname          VARCHAR(128) NOT NULL DEFAULT '' COMMENT '买家昵称快照',
    user_phone             VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '买家手机号快照',
    merchant_id            BIGINT       NOT NULL COMMENT '商户 ID（一单一店铺）',
    shop_id                BIGINT       NOT NULL COMMENT '店铺 ID',
    order_type             TINYINT      NOT NULL COMMENT '订单类型：1 普通 2 秒杀 3 拼团 4 预售 5 换货',
    status                 TINYINT      NOT NULL DEFAULT 10 COMMENT '订单状态：10 待付款 20 待发货 30 待收货 40 已完成 50 已取消 60 退款中 61 退货退款中 62 换货中 70 已关闭',
    source                 TINYINT      NOT NULL DEFAULT 1 COMMENT '订单来源：1 APP 2 H5 3 小程序 4 PC',
    product_total_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '商品原价总额（分）',
    freight_fen            BIGINT       NOT NULL DEFAULT 0 COMMENT '最终应付运费（分，已扣免邮券）',
    product_discount_fen   BIGINT       NOT NULL DEFAULT 0 COMMENT '商品级优惠（限时折扣/秒杀，分）',
    shop_discount_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '店铺级优惠+店铺券（分）',
    platform_discount_fen  BIGINT       NOT NULL DEFAULT 0 COMMENT '品类券+平台券优惠（分）',
    points_deduct_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '积分抵现金额（分）',
    discount_total_fen     BIGINT       NOT NULL DEFAULT 0 COMMENT '优惠总额（分，含免邮券）',
    pay_fen                BIGINT       NOT NULL DEFAULT 0 COMMENT '实付金额（分）',
    used_points            BIGINT       NOT NULL DEFAULT 0 COMMENT '使用积分个数（100 积分=1 元）',
    user_coupon_id         BIGINT       NULL COMMENT '主用户券 ID（事件契约仅支持单券；多券 id 列表存于价格快照）',
    pay_method             TINYINT      NULL COMMENT '支付方式：1 微信 2 支付宝 3 余额 4 银行卡 5 云闪付 6 花呗 7 白条',
    pay_no                 VARCHAR(32)  NULL COMMENT '支付单号（P+17 位）',
    pay_transaction_no     VARCHAR(64)  NULL COMMENT '支付渠道流水号',
    pay_time               DATETIME     NULL COMMENT '支付成功时间',
    expire_time            DATETIME     NULL COMMENT '支付超时截止时间（扫描任务双保险使用）',
    ship_time              DATETIME     NULL COMMENT '商家发货时间',
    confirm_time           DATETIME     NULL COMMENT '确认收货时间',
    aftersale_deadline     DATETIME     NULL COMMENT '售后期截止时间（确认收货后 15 天）',
    complete_time          DATETIME     NULL COMMENT '订单完结（售后期满/售后终结）时间',
    cancel_time            DATETIME     NULL COMMENT '订单取消时间',
    cancel_type            TINYINT      NULL COMMENT '取消类型：1 用户 2 超时 3 商家',
    logistics_no           VARCHAR(64)  NULL COMMENT '物流单号',
    logistics_company      VARCHAR(64)  NULL COMMENT '物流公司',
    auto_confirm_deadline  DATETIME     NULL COMMENT '自动确认收货截止时间（发货后 10 天）',
    receiver               VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '收货人快照',
    receiver_phone         VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '收货人手机号快照',
    province               VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '省快照',
    city                   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '市快照',
    district               VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '区/县快照',
    detail_address         VARCHAR(256) NOT NULL DEFAULT '' COMMENT '详细地址快照',
    address_id             BIGINT       NULL COMMENT '下单地址 ID（待发货修改地址后仍可追溯）',
    remark                 VARCHAR(256) NOT NULL DEFAULT '' COMMENT '买家备注',
    price_snapshot         MEDIUMTEXT   NULL COMMENT '营销试算价格快照 JSON（支付/售后以此为准）',
    seckill_activity_id    BIGINT       NULL COMMENT '秒杀活动 ID',
    groupbuy_activity_id   BIGINT       NULL COMMENT '拼团活动 ID',
    group_no               VARCHAR(64)  NULL COMMENT '拼团团号',
    presale_activity_id    BIGINT       NULL COMMENT '预售活动 ID',
    presale_final_stage    TINYINT      NULL COMMENT '预售阶段：0 定金 1 尾款',
    pre_aftersale_status   TINYINT      NULL COMMENT '进入售后态前的订单状态（售后终结后恢复用）',
    remind_time            DATETIME     NULL COMMENT '最近一次提醒发货时间（只做标记）',
    version                INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status (user_id, status),
    KEY idx_user_time (user_id, create_time),
    KEY idx_merchant_status (merchant_id, status),
    KEY idx_status_expire (status, expire_time),
    KEY idx_status_confirm (status, auto_confirm_deadline),
    KEY idx_status_aftersale (status, aftersale_deadline),
    KEY idx_pay_no (pay_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='订单主表';

-- ---------------------------------------------------------------------
-- 订单明细（design 5.1.1 OrderItem；金额为最大余数法分摊后口径）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_order_item;
CREATE TABLE t_order_item (
    id                    BIGINT       NOT NULL COMMENT '雪花主键（即 OrderItemDTO.orderItemId）',
    order_no              VARCHAR(32)  NOT NULL COMMENT '所属订单号',
    user_id               BIGINT       NOT NULL COMMENT '下单用户 ID（限购历史查询冗余）',
    sku_id                BIGINT       NOT NULL COMMENT 'SKU ID',
    spu_id                BIGINT       NOT NULL COMMENT 'SPU ID',
    merchant_id           BIGINT       NOT NULL COMMENT '商户 ID',
    shop_id               BIGINT       NOT NULL COMMENT '店铺 ID',
    category3_id          BIGINT       NOT NULL DEFAULT 0 COMMENT '三级类目 ID',
    sku_name              VARCHAR(256) NOT NULL DEFAULT '' COMMENT 'SKU 名称快照',
    spec_text             VARCHAR(256) NOT NULL DEFAULT '' COMMENT '规格文本快照',
    image                 VARCHAR(512) NOT NULL DEFAULT '' COMMENT '商品主图快照',
    price_fen             BIGINT       NOT NULL DEFAULT 0 COMMENT '成交单价（分，秒杀/拼团为活动价）',
    qty                   INT          NOT NULL COMMENT '购买数量',
    item_total_fen        BIGINT       NOT NULL DEFAULT 0 COMMENT '单价×数量小计（分）',
    discount_alloc_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '商品/店铺/平台优惠分摊合计（分）',
    points_alloc_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '积分抵现分摊（分）',
    freight_alloc_fen     BIGINT       NOT NULL DEFAULT 0 COMMENT '运费分摊（分）',
    paid_fen              BIGINT       NOT NULL DEFAULT 0 COMMENT '明细实付金额（分，售后退款基数）',
    aftersale_status      TINYINT      NOT NULL DEFAULT 0 COMMENT '明细售后状态：0 无 1 申请中 2 退款中 3 已退款 4 退货中 5 换货中 6 已完成',
    refunded_fen          BIGINT       NOT NULL DEFAULT 0 COMMENT '累计退款金额（分）',
    aftersale_no          VARCHAR(32)  NULL COMMENT '当前关联售后单号',
    stock_type            TINYINT      NOT NULL DEFAULT 1 COMMENT '库存类型：1 普通 2 预售 3 秒杀 4 拼团',
    activity_id           BIGINT       NULL COMMENT '营销/库存活动 ID（秒杀/拼团/预售）',
    seckill_activity_id   BIGINT       NULL COMMENT '秒杀活动 ID（事件组装冗余）',
    group_no              VARCHAR(64)  NULL COMMENT '拼团团号（事件组装冗余）',
    presale_activity_id   BIGINT       NULL COMMENT '预售活动 ID（事件组装冗余）',
    version               INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 已删除',
    PRIMARY KEY (id),
    KEY idx_order_no (order_no),
    KEY idx_user_sku (user_id, sku_id),
    KEY idx_aftersale_no (aftersale_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='订单明细';

-- ---------------------------------------------------------------------
-- 发票（design 5.5）：订单完成后开具；退款全额自动冲红
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_order_invoice;
CREATE TABLE t_order_invoice (
    id              BIGINT       NOT NULL COMMENT '雪花主键',
    order_no        VARCHAR(32)  NOT NULL COMMENT '所属订单号',
    user_id         BIGINT       NOT NULL COMMENT '用户 ID',
    merchant_id     BIGINT       NOT NULL COMMENT '商户 ID',
    invoice_type    TINYINT      NOT NULL DEFAULT 0 COMMENT '发票类型：0 不开 1 电子普通发票 2 增值税专用发票',
    content_scope   TINYINT      NOT NULL DEFAULT 1 COMMENT '发票内容：1 商品明细 2 商品类别',
    title_type      VARCHAR(16)  NOT NULL DEFAULT 'PERSONAL' COMMENT '抬头类型：PERSONAL 个人 / COMPANY 企业',
    company_name    VARCHAR(256) NOT NULL DEFAULT '' COMMENT '公司名称（企业抬头）',
    tax_no          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '纳税人识别号（企业抬头）',
    email           VARCHAR(128) NOT NULL DEFAULT '' COMMENT '电子发票接收邮箱',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '开具状态：0 待开具 1 已开具 2 已冲红',
    invoice_no      VARCHAR(64)  NULL COMMENT '发票号码（模拟生成）',
    pdf_url         VARCHAR(512) NULL COMMENT '电子发票 PDF 地址（模拟生成）',
    issue_time      DATETIME     NULL COMMENT '开具时间',
    red_flush_time  DATETIME     NULL COMMENT '冲红时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 1 已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='订单发票';

-- ---------------------------------------------------------------------
-- MQ 消费流水（event_id 唯一，重复直接 ACK；消费与流水插入同事务）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_order_mq_consume;
CREATE TABLE t_order_mq_consume (
    id           BIGINT       NOT NULL COMMENT '雪花主键',
    event_id     VARCHAR(64)  NOT NULL COMMENT '事件唯一 ID（幂等键）',
    topic        VARCHAR(128) NOT NULL COMMENT 'MQ Topic',
    biz_no       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '业务单号（订单号/支付单号/售后单号）',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '处理状态：0 处理中 1 成功',
    remark       VARCHAR(256) NOT NULL DEFAULT '' COMMENT '备注',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_biz_no (biz_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='MQ 消费幂等流水';
