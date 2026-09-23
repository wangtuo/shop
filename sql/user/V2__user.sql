-- =====================================================================
-- 用户域 DDL（shop-user-service，schema shop_user）
-- 规则来源：design.md 第二章 2.1~2.3、CONTRACTS.md §2、WAVE2_BRIEF.md
-- MySQL 8.0，金额 BIGINT 存分，积分数 BIGINT 存个
-- =====================================================================

CREATE DATABASE IF NOT EXISTS shop_user DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE shop_user;

-- ---------------------------------------------------------------------
-- 用户表（注册/登录/会员状态）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id               BIGINT       NOT NULL COMMENT '用户ID（雪花ID）',
    username         VARCHAR(64)  NOT NULL COMMENT '登录用户名（唯一）',
    phone            VARCHAR(32)  NOT NULL COMMENT '手机号（唯一登录账号）',
    password         VARCHAR(100) NOT NULL COMMENT 'BCrypt 加盐哈希密码',
    nickname         VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    avatar           VARCHAR(512)          DEFAULT NULL COMMENT '头像URL',
    user_type        TINYINT      NOT NULL DEFAULT 0 COMMENT '用户类型：-1游客 0普通 1商户 2平台运营',
    merchant_id      BIGINT                DEFAULT NULL COMMENT '商户ID（user_type=1 时有值）',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '账户状态：0正常 1冻结 2注销',
    growth           BIGINT       NOT NULL DEFAULT 0 COMMENT '成长值（消费1元=1/评价+10/晒单+20/连签7天+50）',
    level            TINYINT      NOT NULL DEFAULT 0 COMMENT '会员等级：0 L0 新会员 … 4 L4 钻石',
    last_sign_date   DATE                  DEFAULT NULL COMMENT '最近一次签到日期（连续签到计算用）',
    continuous_days  INT          NOT NULL DEFAULT 0 COMMENT '当前连续签到天数（中断重置）',
    version          INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone),
    KEY idx_level (level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户表';

-- ---------------------------------------------------------------------
-- 用户账户表：每用户 3 行（1余额/2赠金/3积分）
-- 积分账户：balance=可用积分，frozen=冻结积分（下单预抵）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_account;
CREATE TABLE t_user_account (
    id            BIGINT   NOT NULL COMMENT '账户ID（雪花ID）',
    user_id       BIGINT   NOT NULL COMMENT '用户ID',
    account_type  TINYINT  NOT NULL COMMENT '账户类型：1余额 2赠金 3积分',
    balance       BIGINT   NOT NULL DEFAULT 0 COMMENT '可用余额（余额/赠金单位：分；积分账户单位：积分个）',
    frozen        BIGINT   NOT NULL DEFAULT 0 COMMENT '冻结额（积分下单预抵；余额/赠金当前恒为0）',
    version       INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_type (user_id, account_type),
    KEY idx_user_id (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户资金/积分账户表';

-- ---------------------------------------------------------------------
-- 账户流水表：余额/赠金借贷 + 积分获取/消耗/冻结/释放/退回/过期
-- 幂等：biz_no + change_type 唯一（同一订单可冻结、可实扣，change_type 不同）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_account_flow;
CREATE TABLE t_user_account_flow (
    id             BIGINT      NOT NULL COMMENT '流水ID（雪花ID）',
    user_id        BIGINT      NOT NULL COMMENT '用户ID',
    account_type   TINYINT     NOT NULL COMMENT '账户类型：1余额 2赠金 3积分',
    change_type    TINYINT     NOT NULL COMMENT '变动类型：资金账户 1贷(入账) 2借(扣减)；积分 1获取 2消耗 3冻结 4释放 5退回 6过期',
    amount         BIGINT      NOT NULL COMMENT '变动数量（分或积分个，恒为非负）',
    balance_after  BIGINT      NOT NULL COMMENT '变动后可用余额/可用积分',
    biz_no         VARCHAR(64) NOT NULL COMMENT '业务单号（订单号/支付单号/退款单号/签到流水等），幂等键',
    scene          TINYINT              DEFAULT NULL COMMENT '业务场景：积分见 PointsScene，成长见 GrowthScene',
    remark         VARCHAR(255)         DEFAULT NULL COMMENT '备注',
    status         TINYINT     NOT NULL DEFAULT 1 COMMENT '流水状态：0处理中 1成功',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_change (biz_no, change_type),
    KEY idx_user_type_time (user_id, account_type, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户账户流水表';

-- ---------------------------------------------------------------------
-- 积分冻结记录表（下单 TCC：lock -> deduct/release，与订单一一对应）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_points_freeze;
CREATE TABLE t_user_points_freeze (
    id           BIGINT      NOT NULL COMMENT '冻结记录ID（雪花ID）',
    user_id      BIGINT      NOT NULL COMMENT '用户ID',
    order_no     VARCHAR(64) NOT NULL COMMENT '订单号（幂等键）',
    points       BIGINT      NOT NULL COMMENT '冻结积分个数（100积分=100分=1元）',
    deduct_fen   BIGINT      NOT NULL COMMENT '积分抵现金额（分），单笔封顶订单金额50%由订单域控制',
    scene        TINYINT     NOT NULL DEFAULT 1 COMMENT '积分场景，见 PointsScene（下单抵现为消费场景）',
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '冻结状态：0锁定中 1已实扣 2已释放',
    version      INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user_status (user_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='积分冻结记录表';

-- ---------------------------------------------------------------------
-- 积分获取批次表（365 天有效期，消耗按 FIFO 冲减最早到期批次）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_points_grant;
CREATE TABLE t_user_points_grant (
    id               BIGINT      NOT NULL COMMENT '积分批次ID（雪花ID）',
    user_id          BIGINT      NOT NULL COMMENT '用户ID',
    biz_no           VARCHAR(64) NOT NULL COMMENT '获取业务单号（订单号/签到流水/退款单号等），幂等键',
    scene            TINYINT     NOT NULL COMMENT '获取场景，见 PointsScene',
    points_total     BIGINT      NOT NULL COMMENT '本批次获取积分总数',
    points_remaining BIGINT      NOT NULL COMMENT '本批次剩余可用积分（FIFO 冲减）',
    grant_time       DATETIME    NOT NULL COMMENT '获取时间',
    expire_time      DATETIME    NOT NULL COMMENT '过期时间（获取时间 + 365 天）',
    status           TINYINT     NOT NULL DEFAULT 0 COMMENT '批次状态：0可用 1已过期清零',
    version          INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_user_expire (user_id, status, expire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='积分获取批次表（365天有效期/FIFO）';

-- ---------------------------------------------------------------------
-- 每日积分获取汇总表（评价100/天、分享20/天、签到50/天上限）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_points_daily;
CREATE TABLE t_user_points_daily (
    id             BIGINT     NOT NULL COMMENT '汇总ID（雪花ID）',
    user_id        BIGINT     NOT NULL COMMENT '用户ID',
    stat_date      DATE       NOT NULL COMMENT '统计日期',
    scene          TINYINT    NOT NULL COMMENT '获取场景，见 PointsScene（1消费 2签到 3评价 4分享）',
    earned_points  BIGINT     NOT NULL DEFAULT 0 COMMENT '当日该场景已获取积分合计',
    create_time    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT    NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date_scene (user_id, stat_date, scene)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='每日积分获取汇总表';

-- ---------------------------------------------------------------------
-- 签到记录表（每人每天一条；连签中断重置；第7天额外+50成长值）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_sign_in;
CREATE TABLE t_user_sign_in (
    id                BIGINT   NOT NULL COMMENT '签到记录ID（雪花ID）',
    user_id           BIGINT   NOT NULL COMMENT '用户ID',
    sign_date         DATE     NOT NULL COMMENT '签到日期',
    continuous_days   INT      NOT NULL COMMENT '本次为连续签到第几天（中断后从1重新计）',
    points_earned     BIGINT   NOT NULL COMMENT '本次签到获得积分（第1天5…第7天50，之后每天50）',
    growth_earned     INT      NOT NULL DEFAULT 0 COMMENT '本次签到获得成长值（连续第7/14/21…天 +50，其余0）',
    create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (user_id, sign_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户签到记录表';

-- ---------------------------------------------------------------------
-- 成长值流水表（biz_no 幂等：消费按订单号、评价按评价单、签到按周流水）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_growth_flow;
CREATE TABLE t_user_growth_flow (
    id            BIGINT      NOT NULL COMMENT '成长值流水ID（雪花ID）',
    user_id       BIGINT      NOT NULL COMMENT '用户ID',
    biz_no        VARCHAR(64) NOT NULL COMMENT '业务单号（订单号/评价单号/签到流水），幂等键',
    scene         TINYINT     NOT NULL COMMENT '成长场景：1消费 2评价 3晒单 4连签7天',
    growth        INT         NOT NULL COMMENT '本次增加成长值（正数）',
    growth_after  BIGINT      NOT NULL COMMENT '变动后成长值',
    level_after   TINYINT     NOT NULL COMMENT '变动后会员等级',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='成长值流水表';

-- ---------------------------------------------------------------------
-- 年末成长值折算记录表（每年每用户仅执行一次：按80%折算，保底当前等级）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_growth_discount;
CREATE TABLE t_user_growth_discount (
    id            BIGINT   NOT NULL COMMENT '折算记录ID（雪花ID）',
    user_id       BIGINT   NOT NULL COMMENT '用户ID',
    year          INT      NOT NULL COMMENT '折算年份（12月31日执行）',
    before_growth BIGINT   NOT NULL COMMENT '折算前成长值',
    after_growth  BIGINT   NOT NULL COMMENT '折算后成长值（80%向下取整，保底当前等级下限）',
    before_level  TINYINT  NOT NULL COMMENT '折算前等级',
    after_level   TINYINT  NOT NULL COMMENT '折算后等级（不允许降级）',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_year (user_id, year)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='年末成长值折算记录表';

-- ---------------------------------------------------------------------
-- 收货地址表（每用户最多20条，仅1个默认地址）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_address;
CREATE TABLE t_user_address (
    id              BIGINT      NOT NULL COMMENT '地址ID（雪花ID）',
    user_id         BIGINT      NOT NULL COMMENT '所属用户ID',
    receiver        VARCHAR(64) NOT NULL COMMENT '收货人姓名',
    phone           VARCHAR(32) NOT NULL COMMENT '收货人手机号',
    province        VARCHAR(32) NOT NULL COMMENT '省',
    city            VARCHAR(32) NOT NULL COMMENT '市',
    district        VARCHAR(32) NOT NULL COMMENT '区/县',
    detail_address  VARCHAR(256) NOT NULL COMMENT '详细地址',
    zip_code        VARCHAR(16)          DEFAULT NULL COMMENT '邮政编码',
    tag             VARCHAR(16)          DEFAULT NULL COMMENT '标签：家/公司/学校/其他',
    is_default      TINYINT     NOT NULL DEFAULT 0 COMMENT '是否默认地址：0否 1是（每用户至多1个）',
    version         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    KEY idx_user_default (user_id, is_default)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='收货地址表';

-- ---------------------------------------------------------------------
-- MQ 消费流水表（每消费者以 event_id 幂等；处理与流水插入同一事务）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS t_user_mq_consume;
CREATE TABLE t_user_mq_consume (
    id              BIGINT      NOT NULL COMMENT '流水ID（雪花ID）',
    event_id        VARCHAR(64) NOT NULL COMMENT '事件唯一ID（幂等键）',
    topic           VARCHAR(64) NOT NULL COMMENT 'MQ Topic',
    consumer_group  VARCHAR(64) NOT NULL COMMENT '消费者组（cg_user_xxx）',
    biz_no          VARCHAR(64)          DEFAULT NULL COMMENT '业务单号（订单号/退款单号等）',
    status          TINYINT     NOT NULL DEFAULT 1 COMMENT '处理状态：0处理中 1成功',
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_topic_group (topic, consumer_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='MQ消费幂等流水表';
