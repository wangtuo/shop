-- =====================================================================
-- 清算域 DDL（design.md 第七章 7.1~7.6）
-- 金额一律 BIGINT 存「分」；状态/等级码值见 CONTRACTS.md §4 与 shop-api settlement enums。
-- MySQL 8.0，utf8mb4。
-- =====================================================================
CREATE DATABASE IF NOT EXISTS shop_settlement DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_settlement;

-- ---------------------------------------------------------------------
-- 商户表：等级（0S/1A/2B/3C）、类目默认佣金率、保证金、状态
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_merchant (
  id                      BIGINT       NOT NULL COMMENT '主键（商户ID，与用户域商户一致）',
  merchant_name           VARCHAR(128) NOT NULL DEFAULT '' COMMENT '商户名称（快照）',
  merchant_level          TINYINT      NOT NULL DEFAULT 3 COMMENT '商户等级：0 S(T+1) 1 A(T+7) 2 B(T+15) 3 C(T+30)',
  category_id             BIGINT       NOT NULL DEFAULT 0 COMMENT '主营三级类目ID',
  category_name           VARCHAR(128) NOT NULL DEFAULT '' COMMENT '主营类目名称',
  commission_rate_bps     INT          NOT NULL DEFAULT 1000 COMMENT '类目默认佣金率（万分比，10%=1000，一般500-1500）',
  deposit_balance_fen     BIGINT       NOT NULL DEFAULT 0 COMMENT '保证金余额（分）',
  deposit_required_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '应缴保证金（分，按类目100000-5000000）',
  deposit_alerted         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已发低于50%预警：0否 1是',
  status                  TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0 禁用 1 正常 2 清退中 3 已清退',
  resign_time             DATETIME     NULL COMMENT '清退登记时间（90天无售后纠纷可退保证金）',
  version                 INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted                 TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  KEY idx_status_level (status, merchant_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='清算商户表（等级/类目佣金率/保证金）';

-- ---------------------------------------------------------------------
-- 资金账户：角色 1平台 2商户 3用户 4营销；可用/冻结/待结算余额，行锁条件更新
-- owner_id：平台/营销为 0，商户为 merchantId，用户为 userId
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_account (
  id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  owner_id            BIGINT      NOT NULL COMMENT '账户归属ID（平台/营销为0，商户为商户ID，用户为用户ID）',
  role_type           TINYINT     NOT NULL COMMENT '账户角色：1平台收入 2商户结算 3用户余额 4营销补贴',
  available_fen       BIGINT      NOT NULL DEFAULT 0 COMMENT '可用余额（分；商户即可提现余额）',
  frozen_fen          BIGINT      NOT NULL DEFAULT 0 COMMENT '冻结余额（分：提现审核/打款中）',
  pending_settle_fen  BIGINT      NOT NULL DEFAULT 0 COMMENT '待结算余额（分：已收货未到结算周期）',
  version             INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_owner_role (owner_id, role_type),
  KEY idx_role (role_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='清算资金账户表（可用/冻结/待结算）';

-- ---------------------------------------------------------------------
-- 账户流水：biz_no + change_type 唯一保证业务幂等
-- change_type 见 settlement.enums.FlowChangeTypes
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_account_flow (
  id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  flow_no             VARCHAR(32) NOT NULL COMMENT '流水号（F+序列）',
  owner_id            BIGINT      NOT NULL COMMENT '账户归属ID',
  role_type           TINYINT     NOT NULL COMMENT '账户角色：1平台 2商户 3用户 4营销',
  biz_no              VARCHAR(40) NOT NULL COMMENT '业务单号（清算单号/结算单号/提现单号/退款单号/保证金流水号）',
  change_type         TINYINT     NOT NULL COMMENT '变动类型：10清算入待结算 11待结算转可提现 12佣金入账 13技服费入账 14通道费入账 15补贴出账 20提现冻结 21提现出款 22提现退回 23提现手续费 30退款扣待结算 31退款扣保证金 32佣金冲正 33补贴冲正 40保证金缴纳 41保证金退还 42保证金扣款',
  available_change    BIGINT      NOT NULL DEFAULT 0 COMMENT '可用余额变动（分，正入负出）',
  frozen_change       BIGINT      NOT NULL DEFAULT 0 COMMENT '冻结余额变动（分，正入负出）',
  pending_change      BIGINT      NOT NULL DEFAULT 0 COMMENT '待结算余额变动（分，正入负出）',
  available_after     BIGINT      NOT NULL DEFAULT 0 COMMENT '变动后可用余额（分）',
  frozen_after        BIGINT      NOT NULL DEFAULT 0 COMMENT '变动后冻结余额（分）',
  pending_after       BIGINT      NOT NULL DEFAULT 0 COMMENT '变动后待结算余额（分）',
  remark              VARCHAR(256) NOT NULL DEFAULT '' COMMENT '备注',
  create_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_biz_change (biz_no, change_type),
  UNIQUE KEY uk_flow_no (flow_no),
  KEY idx_owner_role (owner_id, role_type),
  KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='账户流水表（biz_no+change_type幂等）';

-- ---------------------------------------------------------------------
-- 清算单：CL+序列，orderNo 唯一；阶段 10/20/30/40；7.2.2 全量金额字段
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_clearing (
  id                            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  clearing_no                   VARCHAR(32)  NOT NULL COMMENT '清算单号（CL前缀）',
  order_no                      VARCHAR(32)  NOT NULL COMMENT '业务订单号',
  pay_no                        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '支付单号',
  merchant_id                   BIGINT       NOT NULL COMMENT '商户ID',
  user_id                       BIGINT       NOT NULL DEFAULT 0 COMMENT '下单用户ID',
  stage                         TINYINT      NOT NULL DEFAULT 10 COMMENT '清算阶段：10待清算 20待结算 30已结算可提现 40已冲正',
  product_amount_fen            BIGINT       NOT NULL DEFAULT 0 COMMENT '商品金额（分，优惠前）',
  freight_fen                   BIGINT       NOT NULL DEFAULT 0 COMMENT '运费（分）',
  merchant_bear_discount_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '商户承担优惠（店铺券/商户满减，分）',
  platform_bear_discount_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '平台承担优惠（平台券/积分抵现，分）',
  pay_amount_fen                BIGINT       NOT NULL DEFAULT 0 COMMENT '用户实付金额（分，支付事件占位金额）',
  merchant_receivable_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '商户应收货款（分，已扣佣金/通道费、含运费）',
  platform_commission_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '平台佣金（分）',
  tech_fee_fen                  BIGINT       NOT NULL DEFAULT 0 COMMENT '技术服务费（分，50分/笔）',
  channel_fee_fen               BIGINT       NOT NULL DEFAULT 0 COMMENT '支付通道费（分，商品额×60bps，商户承担，退款不退）',
  marketing_subsidy_fen         BIGINT       NOT NULL DEFAULT 0 COMMENT '营销补贴（分，=平台承担优惠）',
  commission_rate_bps           INT          NOT NULL DEFAULT 0 COMMENT '佣金费率（万分比）',
  reversed_merchant_fen         BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已冲正商户货款（分）',
  reversed_commission_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已冲正平台佣金（分）',
  reversed_subsidy_fen          BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已冲正营销补贴（分）',
  refunded_fen                  BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已退款金额（分）',
  merchant_statement_id         BIGINT       NULL COMMENT '当前归属结算单ID',
  confirmed_time                DATETIME     NULL COMMENT '确认收货时间（T0）',
  due_date                      DATE         NULL COMMENT '应结算日期（按等级 T+1/7/15/30）',
  settle_time                   DATETIME     NULL COMMENT '实际结算（转可提现）时间',
  version                       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time                   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time                   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted                       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_clearing_no (clearing_no),
  KEY idx_merchant_stage_due (merchant_id, stage, due_date),
  KEY idx_stage_due (stage, due_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='清算单表（分账明细/阶段/应结算日）';

-- ---------------------------------------------------------------------
-- 清算冲正明细：refundNo 唯一幂等（design 7.5）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_clearing_reverse (
  id                        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  reverse_no                VARCHAR(32)  NOT NULL COMMENT '冲正流水号（RV前缀）',
  refund_no                 VARCHAR(32)  NOT NULL COMMENT '退款单号（R前缀），幂等键',
  order_no                  VARCHAR(32)  NOT NULL COMMENT '原订单号',
  clearing_no               VARCHAR(32)  NOT NULL COMMENT '原清算单号',
  merchant_id               BIGINT       NOT NULL COMMENT '商户ID',
  refund_type               TINYINT      NOT NULL COMMENT '退款类型：1全额 2部分',
  refund_fen                BIGINT       NOT NULL COMMENT '本次退款金额（分）',
  refund_ratio_bps          INT          NOT NULL COMMENT '退款占订单实付比例（万分比）',
  reverse_merchant_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '本次冲正商户货款（分）',
  reverse_commission_fen    BIGINT       NOT NULL DEFAULT 0 COMMENT '本次冲正平台佣金（分，按比例）',
  reverse_subsidy_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '本次冲正营销补贴（分，按比例，回营销账户）',
  from_pending_fen          BIGINT       NOT NULL DEFAULT 0 COMMENT '商户部分取自待结算的金额（分）',
  from_deposit_fen          BIGINT       NOT NULL DEFAULT 0 COMMENT '商户部分取自保保证金的金额（分）',
  full_reversed             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否全额冲正：0否（清算单保持30）1是（清算单转40）',
  create_time               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted                  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_no (refund_no),
  KEY idx_clearing_no (clearing_no),
  KEY idx_merchant_id (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='清算冲正明细表（退款瀑布扣回，refundNo幂等）';

-- ---------------------------------------------------------------------
-- 结算单：ST+序列，按商户+周期汇总；阶段 20待结算 30已结算
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_statement (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  statement_no        VARCHAR(32)  NOT NULL COMMENT '结算单号（ST前缀）',
  merchant_id         BIGINT       NOT NULL COMMENT '商户ID',
  merchant_level      TINYINT      NOT NULL COMMENT '出账时商户等级：0S 1A 2B 3C',
  period_date         DATE         NOT NULL COMMENT '结算周期日期（应结算日，按日归集）',
  total_fen           BIGINT       NOT NULL DEFAULT 0 COMMENT '本单累计货款总额（分）',
  settled_fen         BIGINT       NOT NULL DEFAULT 0 COMMENT '已转可提现金额（分）',
  freezing_fen        BIGINT       NOT NULL DEFAULT 0 COMMENT '冻结中（待结算）金额（分）',
  clearing_count      INT          NOT NULL DEFAULT 0 COMMENT '关联清算单笔数',
  stage               TINYINT      NOT NULL DEFAULT 20 COMMENT '状态：20待结算 30已结算可提现',
  settle_time         DATETIME     NULL COMMENT '结算完成时间',
  version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_statement_no (statement_no),
  UNIQUE KEY uk_merchant_period (merchant_id, period_date),
  KEY idx_stage (stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户结算单表（按商户+应结算日汇总）';

-- ---------------------------------------------------------------------
-- 提现单：WD+序列；状态10申请 20审核 30成功 40失败 50拒绝；渠道 1银行卡 2支付宝
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_withdraw (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  withdraw_no         VARCHAR(32)  NOT NULL COMMENT '提现单号（WD前缀）',
  merchant_id         BIGINT       NOT NULL COMMENT '商户ID',
  amount_fen          BIGINT       NOT NULL COMMENT '提现金额（分）',
  fee_fen             BIGINT       NOT NULL DEFAULT 0 COMMENT '提现手续费（分）',
  channel             TINYINT      NOT NULL COMMENT '提现渠道：1银行卡 2支付宝',
  channel_account     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '收款账号（银行卡号/支付宝账号，脱敏存储）',
  account_name        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '收款人姓名',
  bank_name           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '开户行（银行卡渠道）',
  status              TINYINT      NOT NULL DEFAULT 10 COMMENT '状态：10申请 20审核中 30成功 40失败 50已拒绝',
  apply_date          DATE         NOT NULL COMMENT '申请日期（日限额/免费次数统计口径）',
  free_of_charge      TINYINT      NOT NULL DEFAULT 1 COMMENT '本笔是否免手续费：0否 1是（每月前3笔）',
  auto_withdraw       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否自动提现生成：0否 1是',
  fail_reason         VARCHAR(256) NOT NULL DEFAULT '' COMMENT '失败/拒绝原因',
  audit_time          DATETIME     NULL COMMENT '审核时间',
  remit_time          DATETIME     NULL COMMENT '打款完成时间',
  version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_withdraw_no (withdraw_no),
  KEY idx_merchant_applydate (merchant_id, apply_date),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户提现单表';

-- ---------------------------------------------------------------------
-- 提现月次数统计（每自然月前3笔免费）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_withdraw_daily_count (
  id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  merchant_id         BIGINT      NOT NULL COMMENT '商户ID',
  stat_month          CHAR(7)     NOT NULL COMMENT '统计自然月（yyyy-MM）',
  apply_count         INT         NOT NULL DEFAULT 0 COMMENT '当月已申请笔数（不含拒绝/失败退回？——统计发起笔数）',
  charged_count       INT         NOT NULL DEFAULT 0 COMMENT '当月已收手续费笔数',
  daily_amount_fen    BIGINT      NOT NULL DEFAULT 0 COMMENT '当日累计申请金额（分，配合stat_date使用）',
  stat_date           DATE        NOT NULL COMMENT '统计日期（单日50万上限口径）',
  create_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_month_date (merchant_id, stat_month, stat_date),
  KEY idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='提现次数/单日额度统计表（月免费3笔、日50万上限）';

-- ---------------------------------------------------------------------
-- 自动提现配置：0关闭 1每日 2每周
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_withdraw_auto_config (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  merchant_id         BIGINT       NOT NULL COMMENT '商户ID',
  enabled             TINYINT      NOT NULL DEFAULT 0 COMMENT '是否开启：0关闭 1开启',
  frequency           TINYINT      NOT NULL DEFAULT 1 COMMENT '频率：1每日 2每周',
  weekday             TINYINT      NULL COMMENT '每周提现星期：1周一~7周日（frequency=2时有效）',
  channel             TINYINT      NOT NULL DEFAULT 1 COMMENT '提现渠道：1银行卡 2支付宝',
  channel_account     VARCHAR(128) NOT NULL DEFAULT '' COMMENT '收款账号',
  account_name        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '收款人姓名',
  bank_name           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '开户行',
  last_run_date       DATE         NULL COMMENT '最近一次自动生成申请单日期（防重）',
  version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_id (merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户自动提现配置表（每日/每周）';

-- ---------------------------------------------------------------------
-- 保证金流水：缴费/扣赔/退还
-- log_type: 10 入驻缴费 20 退款扣赔 30 违规罚款 40 清退退还
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_deposit_log (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  log_no              VARCHAR(32)  NOT NULL COMMENT '保证金流水号（DP前缀）',
  merchant_id         BIGINT       NOT NULL COMMENT '商户ID',
  log_type            TINYINT      NOT NULL COMMENT '类型：10缴费 20退款扣赔 30违规罚款 40清退退还',
  amount_fen          BIGINT       NOT NULL COMMENT '发生金额（分，缴纳为正、扣减为负、退还为负）',
  balance_after_fen   BIGINT       NOT NULL COMMENT '变动后保证金余额（分）',
  biz_no              VARCHAR(40)  NOT NULL DEFAULT '' COMMENT '关联业务单号（退款单号等）',
  remark              VARCHAR(256) NOT NULL DEFAULT '' COMMENT '备注',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_log_no (log_no),
  KEY idx_merchant_id (merchant_id),
  KEY idx_biz_no (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='保证金流水表（缴费/扣赔/退还）';

-- ---------------------------------------------------------------------
-- MQ 消费幂等流水：event_id 唯一，与业务处理同事务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_sett_mq_consume (
  id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  event_id            VARCHAR(64)  NOT NULL COMMENT '事件唯一ID（幂等键）',
  topic               VARCHAR(64)  NOT NULL COMMENT 'MQ Topic',
  consumer_group      VARCHAR(64)  NOT NULL COMMENT '消费组',
  biz_no              VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '业务主键（订单号/退款单号）',
  status              TINYINT      NOT NULL DEFAULT 1 COMMENT '处理状态：1成功 0失败',
  create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_topic_group (topic, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ消费幂等流水表';
