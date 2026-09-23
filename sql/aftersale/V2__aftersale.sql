-- =====================================================================
-- shop-aftersale-service 售后域 DDL（design.md 第 8 章；CONTRACTS.md §4/§5）
-- 金额一律 BIGINT 存分；表名 t_aftersale_*；逻辑删除 deleted；并发表带 version。
-- =====================================================================
CREATE DATABASE IF NOT EXISTS shop_aftersale DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_aftersale;

-- ---------------------------------------------------------------------
-- 1. 售后单主表（五类售后共用：仅退款/退货退款/换货/补发/价保）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_order;
CREATE TABLE t_aftersale_order (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    aftersale_no            VARCHAR(32)  NOT NULL COMMENT '售后单号 AS+yyyyMMdd+10位序列',
    order_no                VARCHAR(32)  NOT NULL COMMENT '业务订单号',
    user_id                 BIGINT       NOT NULL COMMENT '买家用户ID',
    merchant_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '商户ID',
    type                    TINYINT      NOT NULL COMMENT '售后类型 1仅退款 2退货退款 3换货 4补发货 5价保',
    status                  INT          NOT NULL COMMENT '状态 10待审核 20待买家退货 30商家收货中 40退款中 41待换货发货 42换货已发货 43换货待收货 50已完成 55已拒绝 80平台介入中 90已撤销',
    reason                  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '申请原因',
    responsibility_side     TINYINT      NOT NULL DEFAULT 1 COMMENT '运费责任方 1商家 2买家 3运费险',
    apply_time              DATETIME     NOT NULL COMMENT '用户申请时间',
    -- 申请时限快照（来自订单窗口：发货/收货时间、收货后15天截止、质保截止）
    shipped_time            DATETIME     NULL COMMENT '订单发货时间',
    confirm_time            DATETIME     NULL COMMENT '订单确认收货时间',
    free_aftersale_deadline DATETIME     NULL COMMENT '收货后15天售后期截止时间',
    warranty_deadline       DATETIME     NULL COMMENT '质保期截止时间（按类目）',
    -- 商家审核超时矩阵（design 8.5）
    audit_deadline          DATETIME     NULL COMMENT '商家审核截止（申请+2天）',
    audit_time              DATETIME     NULL COMMENT '商家审核时间',
    receive_deadline        DATETIME     NULL COMMENT '商家确认收货截止（用户寄回+3天）',
    merchant_receive_time   DATETIME     NULL COMMENT '商家确认收货/拒收时间',
    exchange_ship_deadline  DATETIME     NULL COMMENT '换货发货截止（商家收货+5天）',
    -- 拒绝/撤销/介入
    reject_reason           VARCHAR(512) NOT NULL DEFAULT '' COMMENT '商家/平台拒绝原因',
    reject_time             DATETIME     NULL COMMENT '拒绝时间',
    resubmit_times          INT          NOT NULL DEFAULT 0 COMMENT '拒绝后修改重提次数',
    cancel_time             DATETIME     NULL COMMENT '用户撤销时间',
    intervene_time          DATETIME     NULL COMMENT '申请平台介入时间',
    -- 用户寄回物流
    return_logistics_no     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户寄回物流单号',
    return_company          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户寄回物流公司',
    return_ship_time        DATETIME     NULL COMMENT '用户寄回时间',
    -- 换货 / 补发商家发货物流
    exchange_sku_id         BIGINT       NULL COMMENT '换货目标SKU ID（同SKU换货时等于原SKU）',
    exchange_logistics_no   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '换货/补发商家发出物流单号',
    exchange_company        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '换货/补发物流公司',
    exchange_ship_time      DATETIME     NULL COMMENT '换货/补发发货时间',
    exchange_receive_time   DATETIME     NULL COMMENT '换货用户签收时间',
    -- 金额（分）：可退余额 = 实付（含分摊运费）- 已退；券不退；积分按比例退
    refund_fen              BIGINT       NOT NULL DEFAULT 0 COMMENT '本次退款总金额（分；换货/补发为0）',
    freight_refund_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '退货运费补偿金额（分）',
    points_refund           INT          NOT NULL DEFAULT 0 COMMENT '按比例退还积分数量',
    -- 退款链路
    refund_no               VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '支付域退款单号 R+17位',
    refund_type             TINYINT      NOT NULL DEFAULT 2 COMMENT '退款类型 1全额 2部分',
    refund_time             DATETIME     NULL COMMENT '退款成功时间（REFUND_SUCCESS 回写）',
    -- 价保快照（type=5）
    original_price_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '价保：购买时实付单价（分）',
    current_price_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '价保：当前普通售价（分，排除秒杀/拼团活动价）',
    finish_time             DATETIME     NULL COMMENT '完成时间',
    version                 INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    create_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                 TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_aftersale_no (aftersale_no),
    KEY idx_order_no (order_no),
    KEY idx_user_id (user_id),
    KEY idx_merchant_status (merchant_id, status),
    KEY idx_status_deadline (status, audit_deadline),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='售后单主表';

-- ---------------------------------------------------------------------
-- 2. 售后明细行（按订单明细拆行；同一明细累计退款 <= 实付，同时仅一笔进行中）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_item;
CREATE TABLE t_aftersale_item (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    aftersale_no      VARCHAR(32) NOT NULL COMMENT '售后单号',
    order_item_id     BIGINT      NOT NULL COMMENT '订单明细ID',
    sku_id            BIGINT      NOT NULL COMMENT 'SKU ID',
    spu_id            BIGINT      NOT NULL DEFAULT 0 COMMENT 'SPU ID',
    product_name      VARCHAR(255) NOT NULL DEFAULT '' COMMENT '商品名称快照',
    sku_spec          VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'SKU规格快照',
    qty               INT         NOT NULL COMMENT '本次售后数量',
    paid_fen          BIGINT      NOT NULL DEFAULT 0 COMMENT '该行实付金额快照（分，含分摊运费）',
    refund_fen        BIGINT      NOT NULL DEFAULT 0 COMMENT '该行本次退款金额（分）',
    create_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_aftersale_item (aftersale_no, order_item_id),
    KEY idx_order_item (order_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='售后单明细行';

-- ---------------------------------------------------------------------
-- 3. 售后可申请窗口（消费 ORDER_SHIPPED / ORDER_CONFIRMED 落库；申请时限判定）
--    发货前/收货前/收货后15天/质保期
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_window;
CREATE TABLE t_aftersale_window (
    id                      BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no                VARCHAR(32) NOT NULL COMMENT '订单号',
    user_id                 BIGINT      NOT NULL COMMENT '买家用户ID',
    merchant_id             BIGINT      NOT NULL DEFAULT 0 COMMENT '商户ID',
    order_status            INT         NOT NULL COMMENT '订单状态 20待发货 30待收货 40已完成 等',
    order_type              TINYINT     NOT NULL DEFAULT 1 COMMENT '订单类型 1普通 2秒杀 3拼团 4预售 5换货',
    shipped_time            DATETIME    NULL COMMENT '发货时间（ORDER_SHIPPED）',
    auto_confirm_deadline   DATETIME    NULL COMMENT '自动确认收货截止（收货前仅退款窗口）',
    confirm_time            DATETIME    NULL COMMENT '确认收货时间（ORDER_CONFIRMED）',
    free_aftersale_deadline DATETIME    NULL COMMENT '收货后15天售后期截止',
    warranty_days           INT         NOT NULL DEFAULT 15 COMMENT '质保天数（按类目，默认15天）',
    warranty_deadline       DATETIME    NULL COMMENT '质保期截止时间',
    product_pay_fen         BIGINT      NOT NULL DEFAULT 0 COMMENT '订单商品实付总额（分）',
    freight_fen             BIGINT      NOT NULL DEFAULT 0 COMMENT '订单运费（分）',
    used_points_fen         BIGINT      NOT NULL DEFAULT 0 COMMENT '积分抵扣金额（分）',
    used_points             INT         NOT NULL DEFAULT 0 COMMENT '使用积分数量',
    has_freight_insurance   TINYINT     NOT NULL DEFAULT 0 COMMENT '是否购运费险 0否 1是',
    create_time             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                 TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='售后可申请窗口（订单事件投影）';

-- ---------------------------------------------------------------------
-- 4. 订单明细售后投影（实付/已退/进行中售后，支撑 8.3.2 可退余额守恒）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_order_item_ref;
CREATE TABLE t_aftersale_order_item_ref (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no        VARCHAR(32) NOT NULL COMMENT '订单号',
    order_item_id   BIGINT      NOT NULL COMMENT '订单明细ID',
    user_id         BIGINT      NOT NULL COMMENT '买家用户ID',
    sku_id          BIGINT      NOT NULL COMMENT 'SKU ID',
    spu_id          BIGINT      NOT NULL DEFAULT 0 COMMENT 'SPU ID',
    qty             INT         NOT NULL DEFAULT 0 COMMENT '购买数量',
    paid_fen        BIGINT      NOT NULL DEFAULT 0 COMMENT '该明细实付金额（含分摊运费，分）',
    refunded_fen    BIGINT      NOT NULL DEFAULT 0 COMMENT '累计已退金额（分）',
    active_no       VARCHAR(32) NOT NULL DEFAULT '' COMMENT '进行中售后单号（同一明细同时仅一笔）',
    warranty_days   INT         NOT NULL DEFAULT 15 COMMENT '质保天数（按类目）',
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item (order_item_id),
    KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单明细售后余额投影';

-- ---------------------------------------------------------------------
-- 5. 退款单（调用 PayClient.refund 前落单；REFUND_SUCCESS 回写；幂等）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_refund;
CREATE TABLE t_aftersale_refund (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    refund_no     VARCHAR(32) NOT NULL COMMENT '退款单号 R+17位',
    aftersale_no  VARCHAR(32) NOT NULL COMMENT '售后单号',
    order_no      VARCHAR(32) NOT NULL COMMENT '订单号',
    user_id       BIGINT      NOT NULL COMMENT '买家用户ID',
    amount_fen    BIGINT      NOT NULL COMMENT '退款金额（分）',
    refund_type   TINYINT     NOT NULL DEFAULT 2 COMMENT '1全额 2部分',
    pay_method    TINYINT     NOT NULL DEFAULT 0 COMMENT '支付方式（回写）',
    status        TINYINT     NOT NULL DEFAULT 10 COMMENT '退款状态 10待退款 20退款中 30成功 40失败',
    refund_time   DATETIME    NULL COMMENT '退款成功时间',
    fail_reason   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '退款失败原因',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_no (refund_no),
    UNIQUE KEY uk_aftersale_no (aftersale_no),
    KEY idx_order_no (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='售后退款单';

-- ---------------------------------------------------------------------
-- 6. 平台介入单（8.7：3天举证、5工作日裁决，终局）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_dispute;
CREATE TABLE t_aftersale_dispute (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    aftersale_no       VARCHAR(32)  NOT NULL COMMENT '售后单号',
    order_no           VARCHAR(32)  NOT NULL COMMENT '订单号',
    user_id            BIGINT       NOT NULL COMMENT '买家用户ID',
    merchant_id        BIGINT       NOT NULL DEFAULT 0 COMMENT '商户ID',
    status             TINYINT      NOT NULL DEFAULT 10 COMMENT '10举证中 20待裁决 30已裁决',
    apply_time         DATETIME     NOT NULL COMMENT '申请介入时间',
    evidence_deadline  DATETIME     NOT NULL COMMENT '举证截止（申请+3天）',
    arbitrate_deadline DATETIME     NOT NULL COMMENT '裁决截止（申请+3天+5工作日）',
    result             TINYINT      NOT NULL DEFAULT 0 COMMENT '仲裁结果 0未裁决 1商家胜诉 2买家胜诉 3部分支持',
    award_fen          BIGINT       NOT NULL DEFAULT 0 COMMENT '部分支持/买家胜诉裁定退款金额（分）',
    arbitrate_remark   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '裁决说明',
    arbitrate_time     DATETIME     NULL COMMENT '裁决时间',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_aftersale_no (aftersale_no),
    KEY idx_status_evidence (status, evidence_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台介入单';

-- ---------------------------------------------------------------------
-- 7. 介入举证凭证（双方凭证表）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_evidence;
CREATE TABLE t_aftersale_evidence (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    aftersale_no  VARCHAR(32)  NOT NULL COMMENT '售后单号',
    side          TINYINT      NOT NULL COMMENT '举证方 1买家 2商家',
    user_id       BIGINT       NOT NULL COMMENT '操作人ID',
    evidence_type TINYINT      NOT NULL DEFAULT 1 COMMENT '凭证类型 1图片 2视频 3文字说明',
    content       VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '文字说明',
    media_urls    VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '图片/视频URL，逗号分隔',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    KEY idx_aftersale_side (aftersale_no, side)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='平台介入举证凭证';

-- ---------------------------------------------------------------------
-- 8. 运费险理赔（8.6：每单一次、最高25元、退款成功后72h理赔）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_insurance;
CREATE TABLE t_aftersale_insurance (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no        VARCHAR(32)  NOT NULL COMMENT '订单号（每单仅理赔一次）',
    aftersale_no    VARCHAR(32)  NOT NULL COMMENT '触发理赔的售后单号',
    user_id         BIGINT       NOT NULL COMMENT '买家用户ID',
    premium_fen     BIGINT       NOT NULL DEFAULT 0 COMMENT '保费（分，0.5-5元）',
    claim_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '实际理赔金额（分，<=2500）',
    status          TINYINT      NOT NULL DEFAULT 10 COMMENT '10待理赔 20已理赔 30已失效',
    refund_time     DATETIME     NOT NULL COMMENT '退款成功时间（72h起算点）',
    claim_deadline  DATETIME     NOT NULL COMMENT '理赔截止（退款成功+72小时）',
    claim_time      DATETIME     NULL COMMENT '实际理赔到账时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_status_deadline (status, claim_deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退货运费险理赔';

-- ---------------------------------------------------------------------
-- 9. 价保申请记录（8.8：单单一次、7天/大促30天、排除秒杀拼团活动价）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_price_protect;
CREATE TABLE t_aftersale_price_protect (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no         VARCHAR(32)  NOT NULL COMMENT '订单号（每单仅一次价保）',
    aftersale_no     VARCHAR(32)  NOT NULL COMMENT '价保售后单号',
    order_item_id    BIGINT       NOT NULL COMMENT '订单明细ID',
    sku_id           BIGINT       NOT NULL COMMENT 'SKU ID',
    original_price_fen BIGINT     NOT NULL COMMENT '购买时实付单价（分）',
    current_price_fen  BIGINT     NOT NULL COMMENT '当前普通售价（分）',
    diff_fen         BIGINT       NOT NULL COMMENT '差价（分）',
    big_promotion    TINYINT      NOT NULL DEFAULT 0 COMMENT '是否大促价保期(30天) 0否 1是',
    status           TINYINT      NOT NULL DEFAULT 10 COMMENT '10试算 20已申请 30已补差 40已失效',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_aftersale_no (aftersale_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='价保申请记录';

-- ---------------------------------------------------------------------
-- 10. 售后状态流转日志
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_status_log;
CREATE TABLE t_aftersale_status_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    aftersale_no  VARCHAR(32)  NOT NULL COMMENT '售后单号',
    old_status    INT          NULL COMMENT '变更前状态',
    new_status    INT          NOT NULL COMMENT '变更后状态',
    operator_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '操作人ID（0系统）',
    operator_role TINYINT      NOT NULL DEFAULT 0 COMMENT '操作方 1用户 2商家 3平台 4系统',
    remark        VARCHAR(512) NOT NULL DEFAULT '' COMMENT '备注',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (id),
    KEY idx_aftersale_no (aftersale_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='售后状态流转日志';

-- ---------------------------------------------------------------------
-- 11. MQ 消费流水（每个消费者共用；event_id 唯一去重，同事务插入）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_aftersale_mq_consume;
CREATE TABLE t_aftersale_mq_consume (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id    VARCHAR(64)  NOT NULL COMMENT '事件ID（幂等去重）',
    topic       VARCHAR(64)  NOT NULL COMMENT 'Topic',
    biz_no      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '业务单号',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1已消费',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_biz_no (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ消费流水';
