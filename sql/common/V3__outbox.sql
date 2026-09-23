-- =============================================================================
-- V3__outbox.sql — 本地消息表（transactional outbox，P1-1 修复）
--
-- 每个业务库都需要一份（shop_user / shop_product / shop_marketing / shop_order /
-- shop_pay / shop_settlement / shop_aftersale）。幂等可重复执行。
--
-- 语义：
--   status 0 待投递（relay 每 2s 扫描 status=0 AND deliver_at<=NOW）
--   status 1 已投递（CAS，至少一次，消费端按业务单号幂等）
--   status 2 超过重试上限挂起（慢车道在 V5 新增有限次自动重排，超限人工 requeue）
-- =============================================================================

CREATE TABLE IF NOT EXISTS t_mq_outbox (
    id            BIGINT       NOT NULL COMMENT '雪花ID（由 IdGenerator 生成）',
    topic         VARCHAR(128) NOT NULL COMMENT 'RocketMQ topic',
    tag           VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '消息 tag',
    biz_key       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '业务幂等键（订单号/支付号等）',
    body_json     MEDIUMTEXT   NOT NULL COMMENT '事件 JSON（与直发同一序列化口径）',
    deliver_at    DATETIME(3)  NOT NULL COMMENT '最早投递时间（延时消息=创建+延迟，其余=创建时刻）',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '0待投递 1已投递 2挂起',
    retry_count   INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    suspend_count INT          NOT NULL DEFAULT 0 COMMENT '进入挂起累计次数（V5 慢车道自动重排封顶用）',
    last_error    VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '最近一次投递错误摘要',
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status_deliver (status, deliver_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'MQ 本地消息表（outbox）';
