SET NAMES utf8mb4;
CREATE DATABASE IF NOT EXISTS shop_marketing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_marketing;

-- ============================================================================
-- 营销域 DDL（design.md 第 4 章：促销 / 优惠券 / 秒杀 / 拼团 / 预售 / 砍价 / 抽奖）
-- 约定：金额 BIGINT 存分；id 雪花；deleted 逻辑删除；并发敏感表带 version
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 一、促销（ActivityTypes 1~5：满减/满折/满赠/第N件/限时折扣）
-- ----------------------------------------------------------------------------

-- 促销活动主表
CREATE TABLE t_promo (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    merchant_id     BIGINT       NOT NULL COMMENT '商户 ID',
    shop_id         BIGINT       NULL     COMMENT '店铺 ID；空表示平台/跨店促销',
    name            VARCHAR(128) NOT NULL COMMENT '促销名称',
    type            TINYINT      NOT NULL COMMENT '类型：1满减 2满折 3满赠 4第N件 5限时折扣（ActivityTypes）',
    scope_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '作用范围：1全部商品 2指定SKU 3指定SPU 4指定三级类目',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0下架 1上架',
    start_time      DATETIME     NOT NULL COMMENT '生效开始时间',
    end_time        DATETIME     NOT NULL COMMENT '生效结束时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    remark          VARCHAR(255) NULL     COMMENT '备注',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    KEY idx_shop_time (shop_id, status, start_time, end_time),
    KEY idx_merchant (merchant_id),
    KEY idx_type_status (type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='促销活动主表（满减/满折/满赠/第N件/限时折扣）';

-- 促销规则档位表（满减多级、满折门槛、满赠赠品、第N件参数）
CREATE TABLE t_promo_level (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    promo_id        BIGINT       NOT NULL COMMENT '所属促销 ID',
    threshold_fen   BIGINT       NOT NULL DEFAULT 0 COMMENT '门槛金额（分）：满X元/触发金额',
    reduce_fen      BIGINT       NOT NULL DEFAULT 0 COMMENT '减免金额（分）：满减/直降金额',
    discount_bp     INT          NOT NULL DEFAULT 1000 COMMENT '折扣基点：950=9.5折，1000=不折（满折/限时折扣/第N件折扣）',
    nth_index       INT          NOT NULL DEFAULT 0 COMMENT '第 N 件：件次序号（2=第2件，3=第3件），0表示不适用',
    gift_sku_id     BIGINT       NULL     COMMENT '赠品 SKU ID（满赠）',
    gift_qty        INT          NOT NULL DEFAULT 0 COMMENT '赠品数量（满赠）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    KEY idx_promo (promo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='促销规则档位表';

-- 促销作用目标表（指定 SKU/SPU/类目）
CREATE TABLE t_promo_target (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    promo_id        BIGINT       NOT NULL COMMENT '所属促销 ID',
    target_type     TINYINT      NOT NULL COMMENT '目标类型：1SKU 2SPU 3三级类目',
    target_id       BIGINT       NOT NULL COMMENT '目标 ID（skuId/spuId/category3Id）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_promo_target (promo_id, target_type, target_id),
    KEY idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='促销作用目标表';

-- ----------------------------------------------------------------------------
-- 二、优惠券（CouponTypes 1~6；生命周期 design 4.3；五种发放方式 design 4.3.1）
-- ----------------------------------------------------------------------------

-- 优惠券模板表
CREATE TABLE t_coupon (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    merchant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '商户 ID；0=平台券',
    shop_id         BIGINT       NULL     COMMENT '店铺 ID；空=平台/跨店券',
    name            VARCHAR(128) NOT NULL COMMENT '券名称',
    type            TINYINT      NOT NULL COMMENT '类型：1满减券 2折扣券 3无门槛券 4免邮券 5品类券 6店铺券（CouponTypes）',
    scope_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '适用范围：1全场 2指定SKU 3指定SPU 4指定三级类目 5指定店铺',
    face_value_fen  BIGINT       NOT NULL DEFAULT 0 COMMENT '面额（分）：满减券/无门槛券直减金额',
    threshold_fen   BIGINT       NOT NULL DEFAULT 0 COMMENT '使用门槛（分）：0=无门槛',
    discount_bp     INT          NOT NULL DEFAULT 1000 COMMENT '折扣基点：850=8.5折（折扣券）',
    max_discount_fen BIGINT      NULL     COMMENT '折扣券封顶优惠（分），空=不封顶',
    total_count     INT          NOT NULL DEFAULT 0 COMMENT '发行总量；0=不限量',
    received_count  INT          NOT NULL DEFAULT 0 COMMENT '已领取数量',
    per_user_limit  INT          NOT NULL DEFAULT 1 COMMENT '每人限领张数（系统补偿/积分兑换可配 0=不限）',
    issue_way       TINYINT      NOT NULL DEFAULT 1 COMMENT '默认发放方式：1主动领取 2活动发放 3新人礼包 4系统补偿 5积分兑换',
    valid_type      TINYINT      NOT NULL DEFAULT 1 COMMENT '有效期类型：1固定时间段 2领取后N天有效',
    valid_days      INT          NOT NULL DEFAULT 0 COMMENT '领取后有效天数（valid_type=2）',
    valid_start_time DATETIME    NULL     COMMENT '固定有效期开始（valid_type=1）',
    valid_end_time  DATETIME     NULL     COMMENT '固定有效期结束（valid_type=1）',
    receive_start_time DATETIME  NOT NULL COMMENT '领取开始时间',
    receive_end_time DATETIME    NOT NULL COMMENT '领取结束时间',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0下架 1上架 2作废撤回',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    KEY idx_shop_status (shop_id, status),
    KEY idx_merchant (merchant_id),
    KEY idx_receive_time (receive_start_time, receive_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券模板表';

-- 券适用目标表（品类券/单品券/店铺券作用域）
CREATE TABLE t_coupon_target (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    coupon_id       BIGINT       NOT NULL COMMENT '券模板 ID',
    target_type     TINYINT      NOT NULL COMMENT '目标类型：1SKU 2SPU 3三级类目 4店铺',
    target_id       BIGINT       NOT NULL COMMENT '目标 ID',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_target (coupon_id, target_type, target_id),
    KEY idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券作用目标表';

-- 用户券表（券生命周期：0未使用 4锁定 1已使用 2已过期 3已作废；4 为库表中间态）
CREATE TABLE t_user_coupon (
    id              BIGINT       NOT NULL COMMENT '主键（用户券记录 ID，试算/锁定上送此 ID）',
    user_id         BIGINT       NOT NULL COMMENT '持券用户 ID',
    coupon_id       BIGINT       NOT NULL COMMENT '券模板 ID',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0未使用 1已使用 2已过期 3已作废 4已锁定（预核销中间态）',
    issue_way       TINYINT      NOT NULL COMMENT '发放方式：1主动领取 2活动发放 3新人礼包 4系统补偿 5积分兑换',
    request_no      VARCHAR(64)  NOT NULL COMMENT '发放幂等流水号（@Idempotent key/业务请求号）',
    order_no        VARCHAR(64)  NULL     COMMENT '锁定/使用的订单号',
    valid_start_time DATETIME    NOT NULL COMMENT '本券有效期开始',
    valid_end_time  DATETIME     NOT NULL COMMENT '本券有效期结束',
    lock_time       DATETIME     NULL     COMMENT '锁定时间',
    used_time       DATETIME     NULL     COMMENT '核销时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_issue_request (user_id, coupon_id, issue_way, request_no),
    KEY idx_user_status (user_id, status),
    KEY idx_user_coupon (user_id, coupon_id),
    KEY idx_order (order_no),
    KEY idx_expire (status, valid_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表';

-- ----------------------------------------------------------------------------
-- 三、营销活动（ActivityTypes 10~14：秒杀/拼团/预售/砍价/抽奖）
-- ----------------------------------------------------------------------------

-- 活动主表（秒杀/拼团/预售/砍价/抽奖公共字段，差异化参数存 rule_json）
CREATE TABLE t_activity (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    merchant_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '商户 ID；0=平台活动',
    shop_id         BIGINT       NULL     COMMENT '店铺 ID',
    name            VARCHAR(128) NOT NULL COMMENT '活动名称',
    type            TINYINT      NOT NULL COMMENT '类型：10秒杀 11拼团 12预售 13砍价 14抽奖（ActivityTypes）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0下架/未开始 1进行中/上架 2已结束 3已取消',
    start_time      DATETIME     NOT NULL COMMENT '活动开始时间',
    end_time        DATETIME     NOT NULL COMMENT '活动结束时间',
    rule_json       TEXT         NULL     COMMENT '活动规则 JSON：拼团人数/团长优惠、预售膨胀、砍价底价、抽奖概率等',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    KEY idx_type_status_time (type, status, start_time, end_time),
    KEY idx_merchant (merchant_id),
    KEY idx_shop (shop_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营销活动主表（秒杀/拼团/预售/砍价/抽奖）';

-- 秒杀 SKU 库存表（DB 库存与 Redis 原子库存对账）
CREATE TABLE t_seckill_sku (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    activity_id     BIGINT       NOT NULL COMMENT '秒杀活动 ID',
    sku_id          BIGINT       NOT NULL COMMENT 'SKU ID',
    seckill_price_fen BIGINT     NOT NULL COMMENT '秒杀价（分）',
    total_stock     INT          NOT NULL COMMENT '秒杀总库存',
    locked_stock    INT          NOT NULL DEFAULT 0 COMMENT '已锁定（待支付）库存',
    sold_stock      INT          NOT NULL DEFAULT 0 COMMENT '已售出库存',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_sku (activity_id, sku_id),
    KEY idx_sku (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀 SKU 库存表';

-- 秒杀订单记录表（锁定/扣减/释放流水，order_no 幂等）
CREATE TABLE t_seckill_order (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    activity_id     BIGINT       NOT NULL COMMENT '秒杀活动 ID',
    sku_id          BIGINT       NOT NULL COMMENT 'SKU ID',
    user_id         BIGINT       NOT NULL COMMENT '用户 ID',
    order_no        VARCHAR(64)  NOT NULL COMMENT '订单号（幂等键）',
    qty            INT          NOT NULL COMMENT '秒杀数量',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0已锁定 1已扣减 2已释放',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_activity_user (activity_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单记录表';

-- 拼团实例表（团）
CREATE TABLE t_groupbuy (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    group_no        VARCHAR(64)  NOT NULL COMMENT '团号（开团/参团/成团/失败事件共用）',
    activity_id     BIGINT       NOT NULL COMMENT '拼团活动 ID',
    leader_user_id  BIGINT       NOT NULL COMMENT '团长用户 ID',
    required_people TINYINT      NOT NULL COMMENT '成团人数：2/3/5/10',
    joined_count    INT          NOT NULL DEFAULT 1 COMMENT '已参团人数（含团长）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0进行中 1成团 2失败',
    expire_time     DATETIME     NOT NULL COMMENT '成团截止时间（开团+24h）',
    success_time    DATETIME     NULL     COMMENT '成团时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_no (group_no),
    KEY idx_activity_status (activity_id, status),
    KEY idx_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拼团团实例表';

-- 拼团成员表（每人每活动限 1 次）
CREATE TABLE t_groupbuy_member (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    group_no        VARCHAR(64)  NOT NULL COMMENT '团号',
    activity_id     BIGINT       NOT NULL COMMENT '拼团活动 ID',
    user_id         BIGINT       NOT NULL COMMENT '成员用户 ID',
    order_no        VARCHAR(64)  NOT NULL COMMENT '参团订单号',
    leader_flag     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否团长：1是 0否',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0参团中 1已成团 2已退出(订单取消)',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_user (activity_id, user_id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_group (group_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拼团成员表';

-- 预售订单表（定金 + 尾款 + 定金膨胀）
CREATE TABLE t_presale_order (
    id                  BIGINT       NOT NULL COMMENT '主键（雪花）',
    activity_id         BIGINT       NOT NULL COMMENT '预售活动 ID',
    user_id             BIGINT       NOT NULL COMMENT '用户 ID',
    order_no            VARCHAR(64)  NOT NULL COMMENT '订单号（幂等键）',
    deposit_fen         BIGINT       NOT NULL COMMENT '定金金额（分）',
    inflate_deduct_fen  BIGINT       NOT NULL DEFAULT 0 COMMENT '定金膨胀抵扣金额（分，如定金50抵100则为100）',
    final_pay_fen       BIGINT       NOT NULL DEFAULT 0 COMMENT '尾款应付金额（分，膨胀后、券前）',
    final_start_time    DATETIME     NOT NULL COMMENT '尾款支付开始时间',
    final_end_time      DATETIME     NOT NULL COMMENT '尾款支付截止时间（通常 3 天）',
    status              TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0定金已付 1尾款已付 2已取消(尾款超时,定金不退)',
    version             INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted             TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_activity_user (activity_id, user_id),
    KEY idx_final_end (status, final_end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预售订单表';

-- 砍价记录表
CREATE TABLE t_bargain_record (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    activity_id     BIGINT       NOT NULL COMMENT '砍价活动 ID',
    sku_id          BIGINT       NOT NULL COMMENT 'SKU ID',
    user_id         BIGINT       NOT NULL COMMENT '发起用户 ID',
    order_no        VARCHAR(64)  NULL     COMMENT '底价成交订单号',
    origin_price_fen BIGINT      NOT NULL COMMENT '原价（分）',
    floor_price_fen BIGINT       NOT NULL COMMENT '底价（分）',
    current_price_fen BIGINT     NOT NULL COMMENT '当前价（分）',
    help_count      INT          NOT NULL DEFAULT 0 COMMENT '已帮砍次数',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0砍价中 1已成交 2已失效',
    expire_time     DATETIME     NOT NULL COMMENT '砍价截止时间',
    version         INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_activity (user_id, activity_id),
    KEY idx_activity (activity_id),
    KEY idx_expire (status, expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='砍价记录表';

-- 抽奖记录表
CREATE TABLE t_lottery_record (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    activity_id     BIGINT       NOT NULL COMMENT '抽奖活动 ID',
    user_id         BIGINT       NOT NULL COMMENT '用户 ID',
    cost_points     INT          NOT NULL DEFAULT 0 COMMENT '消耗积分',
    prize_code      VARCHAR(64)  NULL     COMMENT '中奖奖品编码；空=未中奖',
    prize_name      VARCHAR(128) NULL     COMMENT '中奖奖品名称',
    order_no        VARCHAR(64)  NULL     COMMENT '关联订单号（支付参与场景）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    KEY idx_activity_user (activity_id, user_id),
    KEY idx_order (order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='抽奖记录表';

-- ----------------------------------------------------------------------------
-- 四、营销资源锁定记录（对内 4 接口的 orderNo 幂等锚点）
-- ----------------------------------------------------------------------------
CREATE TABLE t_marketing_lock (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    order_no        VARCHAR(64)  NOT NULL COMMENT '订单号（锁定/确认/释放幂等键）',
    user_id         BIGINT       NOT NULL COMMENT '用户 ID',
    order_type      TINYINT      NOT NULL COMMENT '订单类型：1普通 2秒杀 3拼团 4预售 5换货',
    activity_id     BIGINT       NULL     COMMENT '营销活动 ID（秒杀/拼团/预售）',
    user_coupon_ids VARCHAR(255) NOT NULL DEFAULT '' COMMENT '预核销用户券 ID 列表，逗号分隔',
    snapshot_json   MEDIUMTEXT   NULL     COMMENT '试算价格快照 JSON',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0已锁定 1已确认 2已释放',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营销资源锁定记录表（TCC 锚点）';

-- ----------------------------------------------------------------------------
-- 五、MQ 消费流水（eventId 唯一，同事务插入保证至少一次+幂等）
-- ----------------------------------------------------------------------------
CREATE TABLE t_marketing_mq_consume (
    id              BIGINT       NOT NULL COMMENT '主键（雪花）',
    event_id        VARCHAR(64)  NOT NULL COMMENT '事件 ID（幂等键）',
    topic           VARCHAR(128) NOT NULL COMMENT 'Topic',
    biz_no          VARCHAR(64)  NULL     COMMENT '业务单号（orderNo 等）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '处理状态：0成功 1失败待重试',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0否 1是',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_biz (biz_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='营销域 MQ 消费流水表';
