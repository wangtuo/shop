-- =====================================================================
-- shop-pay-service 支付域 DDL（schema: shop_pay）
-- design 第六章 支付模块：7 种支付方式 / 状态机 / 退款 / T+1 对账 / 补偿
-- 金额一律 BIGINT 分；表名 t_pay_*；公共字段对齐 shop-common BaseEntity
-- =====================================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS shop_pay DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_pay;

-- ---------------------------------------------------------------------
-- 支付单（design 6.2 / 6.3）
-- status: 10 待支付 20 支付中 30 成功 40 失败 50 已关闭 60 退款中 70 已退款
-- pay_method 取值 com.shop.api.pay.enums.PayMethods（1 微信 2 支付宝 3 余额
--   4 银行卡 5 云闪付 6 花呗 7 白条）
-- pay_scene: 1 普通支付 2 组合支付 3 好友代付（组合/代付明细见 t_pay_channel_flow）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_order`;
CREATE TABLE `t_pay_order` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pay_no`                 VARCHAR(32)     NOT NULL COMMENT '支付单号 P+17位',
  `order_no`               VARCHAR(32)     NOT NULL COMMENT '业务订单号（幂等键）',
  `user_id`                BIGINT          NOT NULL COMMENT '付款归属用户ID（订单用户）',
  `friend_user_id`         BIGINT          DEFAULT NULL COMMENT '好友代付实际付款人用户ID',
  `pay_method`             TINYINT         NOT NULL COMMENT '主支付方式 PayMethods 码值',
  `pay_scene`              TINYINT         NOT NULL DEFAULT 1 COMMENT '支付场景 1普通 2组合 3好友代付',
  `terminal`               TINYINT         DEFAULT NULL COMMENT '发起终端 Terminals 1APP 2H5 3小程序 4PC',
  `amount_fen`             BIGINT          NOT NULL COMMENT '支付金额（分）',
  `refunded_fen`           BIGINT          NOT NULL DEFAULT 0 COMMENT '累计已退款金额（分）',
  `subject`                VARCHAR(256)    DEFAULT NULL COMMENT '订单主题/商品标题',
  `status`                 TINYINT         NOT NULL DEFAULT 10 COMMENT '支付单状态 10/20/30/40/50/60/70',
  `pay_url`                VARCHAR(1024)   DEFAULT NULL COMMENT '渠道收银台/支付链接（mock 参数）',
  `channel_code`           VARCHAR(32)     DEFAULT NULL COMMENT '渠道编码 MOCK_ALIPAY 等',
  `channel_order_no`       VARCHAR(64)     DEFAULT NULL COMMENT '渠道下单流水号',
  `channel_transaction_no` VARCHAR(64)     DEFAULT NULL COMMENT '渠道支付交易流水号（对账主键）',
  `notify_id`              VARCHAR(64)     DEFAULT NULL COMMENT '渠道回调通知ID',
  `expire_time`            DATETIME        DEFAULT NULL COMMENT '支付单超时时间',
  `pay_time`               DATETIME        DEFAULT NULL COMMENT '支付成功时间',
  `close_time`             DATETIME        DEFAULT NULL COMMENT '关闭时间',
  `fail_reason`            VARCHAR(256)    DEFAULT NULL COMMENT '失败原因',
  `version`                INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted`                TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
  `create_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_no` (`pay_no`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status_expire` (`status`, `expire_time`),
  KEY `idx_channel_txn` (`channel_transaction_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付单';

-- ---------------------------------------------------------------------
-- 支付渠道流水（下单流水 / 组合支付拆分流水 / 主动查询记录）
-- 普通支付 1 条；组合支付按支付手段 N 条，各条 amount_fen 之和 = 支付单金额
-- flow_status: 10 待支付 20 处理中 30 成功 40 失败 50 已关闭
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_channel_flow`;
CREATE TABLE `t_pay_channel_flow` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pay_no`                 VARCHAR(32)     NOT NULL COMMENT '支付单号',
  `order_no`               VARCHAR(32)     NOT NULL COMMENT '业务订单号',
  `pay_method`             TINYINT         NOT NULL COMMENT '本行支付手段 PayMethods 码值',
  `channel_code`           VARCHAR(32)     NOT NULL COMMENT '渠道编码',
  `channel_order_no`       VARCHAR(64)     DEFAULT NULL COMMENT '渠道下单流水号',
  `channel_transaction_no` VARCHAR(64)     DEFAULT NULL COMMENT '渠道支付交易流水号',
  `amount_fen`             BIGINT          NOT NULL COMMENT '本行支付金额（分）',
  `pay_url`                VARCHAR(1024)   DEFAULT NULL COMMENT '本行渠道支付参数/链接',
  `flow_status`            TINYINT         NOT NULL DEFAULT 10 COMMENT '流水状态 10/20/30/40/50',
  `paid_fen`               BIGINT          NOT NULL DEFAULT 0 COMMENT '本行累计已退款金额（分）；列名为历史遗留，语义为退款非支付',
  `request_body`           VARCHAR(2048)   DEFAULT NULL COMMENT '渠道下单请求（mock）',
  `response_body`          VARCHAR(2048)   DEFAULT NULL COMMENT '渠道下单响应（mock）',
  `pay_time`               DATETIME        DEFAULT NULL COMMENT '本行成功时间',
  `version`                INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted`                TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_order_no` (`channel_code`, `channel_order_no`),
  KEY `idx_pay_no` (`pay_no`),
  KEY `idx_channel_txn` (`channel_transaction_no`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付渠道流水';

-- ---------------------------------------------------------------------
-- 退款单（design 6.4）
-- status: 10 待退款 20 退款中 30 成功 40 失败 50 已冲正
-- refund_type: 1 全额 2 部分；source: 1 售后 2 价保 3 清算冲正
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_refund`;
CREATE TABLE `t_pay_refund` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no`         VARCHAR(32)     NOT NULL COMMENT '退款单号 R+17位',
  `pay_no`            VARCHAR(32)     NOT NULL COMMENT '原支付单号',
  `order_no`          VARCHAR(32)     NOT NULL COMMENT '原业务订单号',
  `aftersale_no`      VARCHAR(32)     DEFAULT NULL COMMENT '售后单号',
  `user_id`           BIGINT          NOT NULL COMMENT '退款归属用户ID',
  `amount_fen`        BIGINT          NOT NULL COMMENT '本次退款金额（分）',
  `pay_method`        TINYINT         DEFAULT NULL COMMENT '退款主渠道/原支付方式',
  `refund_type`       TINYINT         NOT NULL COMMENT '退款类型 1全额 2部分',
  `source`            TINYINT         NOT NULL COMMENT '退款来源 1售后 2价保 3清算冲正',
  `operator_type`     TINYINT         DEFAULT NULL COMMENT '操作人类型 -1游客 0用户 1商户 2平台',
  `status`            TINYINT         NOT NULL DEFAULT 10 COMMENT '退款状态 10/20/30/40/50',
  `reason`            VARCHAR(256)    DEFAULT NULL COMMENT '退款原因',
  `fail_reason`       VARCHAR(256)    DEFAULT NULL COMMENT '失败原因',
  `retry_count`       INT             NOT NULL DEFAULT 0 COMMENT '渠道退款重试次数',
  `finish_time`       DATETIME        DEFAULT NULL COMMENT '退款完成时间',
  `version`           INT             NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `deleted`           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_pay_no` (`pay_no`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_aftersale_no` (`aftersale_no`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款单';

-- ---------------------------------------------------------------------
-- 退款拆分明细（混合支付按各手段实付占比原路退回，MoneyUtils 最大余数法守恒）
-- status: 10 待退款 20 退款中 30 成功 40 失败
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_refund_split`;
CREATE TABLE `t_pay_refund_split` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no`        VARCHAR(32)     NOT NULL COMMENT '退款单号',
  `pay_no`           VARCHAR(32)     NOT NULL COMMENT '原支付单号',
  `pay_method`       TINYINT         NOT NULL COMMENT '退回支付手段 PayMethods 码值',
  `channel_code`     VARCHAR(32)     NOT NULL COMMENT '退回渠道编码',
  `amount_fen`       BIGINT          NOT NULL COMMENT '本行退款金额（分）',
  `channel_refund_no` VARCHAR(64)    DEFAULT NULL COMMENT '渠道退款流水号',
  `status`           TINYINT         NOT NULL DEFAULT 10 COMMENT '明细状态 10/20/30/40',
  `finish_time`      DATETIME        DEFAULT NULL COMMENT '本行退款完成时间',
  `deleted`          TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_refund_no` (`channel_code`, `channel_refund_no`),
  KEY `idx_refund_no` (`refund_no`),
  KEY `idx_pay_no` (`pay_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款拆分明细';

-- ---------------------------------------------------------------------
-- 渠道回调幂等表（防刷：回调验签 + 通知幂等；PAY_SIGN_ERROR 60002 拒绝）
-- handle_status: 0 已接收待处理 1 处理成功 2 验签失败 3 处理失败
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_notify_log`;
CREATE TABLE `t_pay_notify_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `channel_code`    VARCHAR(32)     NOT NULL COMMENT '渠道编码',
  `notify_id`       VARCHAR(64)     NOT NULL COMMENT '渠道回调通知ID（幂等键）',
  `pay_no`          VARCHAR(32)     DEFAULT NULL COMMENT '支付单号',
  `channel_txn_no`  VARCHAR(64)     DEFAULT NULL COMMENT '渠道交易流水号',
  `notify_type`     TINYINT         NOT NULL DEFAULT 1 COMMENT '通知类型 1支付 2退款',
  `sign_status`     TINYINT         NOT NULL DEFAULT 0 COMMENT '验签结果 0待验 1通过 2失败',
  `handle_status`   TINYINT         NOT NULL DEFAULT 0 COMMENT '处理状态 0待处理 1成功 2验签失败 3失败',
  `notify_body`     TEXT            DEFAULT NULL COMMENT '回调原文',
  `fail_reason`     VARCHAR(256)    DEFAULT NULL COMMENT '失败原因',
  `deleted`         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_channel_notify` (`channel_code`, `notify_id`),
  KEY `idx_pay_no` (`pay_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='渠道回调幂等日志';

-- ---------------------------------------------------------------------
-- T+1 对账批次（design 6.5，@SchedulerLock 日终分页拉取渠道对账单）
-- status: 10 拉取中 20 比对完成 30 差错处理完成
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_recon_batch`;
CREATE TABLE `t_pay_recon_batch` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_no`        VARCHAR(32)     NOT NULL COMMENT '对账批次号 RC+日期+序列',
  `recon_date`      DATE            NOT NULL COMMENT '对账业务日期 T+1',
  `channel_code`    VARCHAR(32)     NOT NULL COMMENT '渠道编码',
  `channel_count`   INT             NOT NULL DEFAULT 0 COMMENT '渠道账单笔数',
  `channel_amount_fen` BIGINT       NOT NULL DEFAULT 0 COMMENT '渠道账单金额合计（分）',
  `local_count`     INT             NOT NULL DEFAULT 0 COMMENT '本地成功笔数',
  `local_amount_fen` BIGINT         NOT NULL DEFAULT 0 COMMENT '本地成功金额合计（分）',
  `long_count`      INT             NOT NULL DEFAULT 0 COMMENT '长款笔数',
  `short_count`     INT             NOT NULL DEFAULT 0 COMMENT '短款笔数',
  `mismatch_count`  INT             NOT NULL DEFAULT 0 COMMENT '金额不符笔数',
  `status`          TINYINT         NOT NULL DEFAULT 10 COMMENT '批次状态 10/20/30',
  `finish_time`     DATETIME        DEFAULT NULL COMMENT '批次完成时间',
  `deleted`         TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_no` (`batch_no`),
  UNIQUE KEY `uk_date_channel` (`recon_date`, `channel_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对账批次';

-- ---------------------------------------------------------------------
-- 对账差错单（长款/短款/金额不符）
-- diff_type: 1 长款(渠道有本地无) 2 短款(本地有渠道无) 3 金额不符
-- status: 10 待处理 20 处理中 30 已处理 40 人工挂账
-- handle_action: 长款补单/短款关单/金额调账
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_recon_diff`;
CREATE TABLE `t_pay_recon_diff` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_no`          VARCHAR(32)     NOT NULL COMMENT '对账批次号',
  `recon_date`        DATE            NOT NULL COMMENT '对账业务日期',
  `channel_code`      VARCHAR(32)     NOT NULL COMMENT '渠道编码',
  `diff_type`         TINYINT         NOT NULL COMMENT '差错类型 1长款 2短款 3金额不符',
  `pay_no`            VARCHAR(32)     DEFAULT NULL COMMENT '本地支付单号（短款/金额不符）',
  `order_no`          VARCHAR(32)     DEFAULT NULL COMMENT '业务订单号',
  `channel_txn_no`    VARCHAR(64)     DEFAULT NULL COMMENT '渠道交易流水号',
  `local_amount_fen`  BIGINT          NOT NULL DEFAULT 0 COMMENT '本地金额（分）',
  `channel_amount_fen` BIGINT         NOT NULL DEFAULT 0 COMMENT '渠道金额（分）',
  `status`            TINYINT         NOT NULL DEFAULT 10 COMMENT '差错状态 10待处理 20处理中 30已处理 40人工挂账',
  `handle_action`     VARCHAR(64)     DEFAULT NULL COMMENT '补偿动作 SUPPLEMENT_ORDER/CLOSE_ORDER/ADJUST_AMOUNT/MANUAL',
  `handle_remark`     VARCHAR(512)    DEFAULT NULL COMMENT '处理备注',
  `retry_count`       INT             NOT NULL DEFAULT 0 COMMENT '补偿重试次数',
  `max_retry`         INT             NOT NULL DEFAULT 5 COMMENT '最大补偿重试次数',
  `handle_time`       DATETIME        DEFAULT NULL COMMENT '处理完成时间',
  `deleted`           TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_txn` (`batch_no`, `channel_code`, `channel_txn_no`),
  KEY `idx_recon_date` (`recon_date`),
  KEY `idx_status` (`status`),
  KEY `idx_pay_no` (`pay_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对账差错单';

-- ---------------------------------------------------------------------
-- MQ 消费幂等表（mq_consume，消费者以 eventId/业务单号幂等）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `t_pay_mq_consume`;
CREATE TABLE `t_pay_mq_consume` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `topic`         VARCHAR(64)     NOT NULL COMMENT 'Topic',
  `event_id`      VARCHAR(64)     NOT NULL COMMENT '事件ID（幂等键）',
  `biz_no`        VARCHAR(64)     DEFAULT NULL COMMENT '业务单号',
  `consumer_group` VARCHAR(64)    NOT NULL COMMENT '消费组',
  `consume_status` TINYINT        NOT NULL DEFAULT 1 COMMENT '消费状态 1成功 2失败',
  `payload`       TEXT            DEFAULT NULL COMMENT '事件体快照',
  `error_msg`     VARCHAR(512)    DEFAULT NULL COMMENT '失败原因',
  `deleted`       TINYINT         NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `create_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_event` (`consumer_group`, `event_id`),
  KEY `idx_biz_no` (`biz_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ消费幂等日志';
