# 资金域缺口修复架构规划（GAP_PLAN_FUNDS）

> 角色：资金域缺口修复架构规划员。本文件为**只读勘察后的规划产物**，不含任何 Java 改动；
> 业务代理不得自行修改 shop-api / shop-common，所有契约变更统一汇总在 §2，由契约负责人一次性落地。
> 覆盖缺口：**B8、B9、B10、B11、MQ P0-1、MQ P2-1、MQ P2-4、MQ P2-5、韧性 R-B6、契约 API-F**，共 10 张任务卡。
> 勘察基线：AUDIT_FEATURES.md §10.2、AUDIT_MQ_CONSISTENCY.md、FIXES_D/E/F/G.md、design.md §6/§7/§8.6、
> pay/settlement/aftersale/order 现状代码、deploy/rocketmq/create-topics.sh、sql/ 全量版本。

---

## 0. 勘察基线与审计勘误（先读）

| 编号 | 审计/旧报告口径 | 当前代码事实 | 对规划的影响 |
|---|---|---|---|
| P0-1 | topic `shop_refund_shortfall` 未预建、无消费组 | `deploy/rocketmq/create-topics.sh:21` 已含 topic、`:39` 已含消费组 **`cg_sett_shortfall`**；`MqTopics.REFUND_SHORTFALL`（shop-common :59）已存在；`ClearingReverseService.publishShortfall`（:215）已发 outbox。**但 Java 侧零消费者、无工单落库、无 outbox status=2 扫表兜底** | P0-1 卡只规划"消费者+工单+每日扫表（+追缴）"，**不重复建 topic/group** |
| B9 | SplitEngine.java:68 计平台收入但未从商户应收扣减，每单虚增 50 分 | **已修复**。`SplitEngine.java:77` 为 `merchantReceivable = base - commission - channelFee - TECH_FEE_FEN + freight`；:68 现在是佣金计算行，旧行号失效；类注释 20-28 行明确资金守恒口径；`SplitEngineTest` 5 用例含双向守恒断言；`SettleClearingExecutor` 技服费以 `TECH_FEE_INCOME` 平台入账 | B9 仅出**回归测试卡**（防回退 + 端到端资金平衡断言） |
| P2-4 | ReconcileServiceImpl 单方法大事务循环 | 现状未修：`ReconcileServiceImpl.runReconcile`(:62) 与 `retryPendingDiffs`(:168) 仍是方法级单一大 `@Transactional`，`handleOne` 循环内逐条处理 | 按卡 P2-4 修 |
| P2-5 | 外部渠道退款 HTTP 包在 DB 事务内 | 现状未修：`RefundServiceImpl.executeRefund`(:141) 整体 `@Transactional`，`doRefundOne`(:193) 在事务内调 `channelRouter.refund(...)`（HTTP）与 `userClient.creditBalance(...)`（Feign） | 按卡 P2-5 修，并与 B8 的回调/查询终态收敛共用同一个收敛入口 |
| B8 | RefundStateMachine 死代码、无退款回调端点、无退款查询补偿 | 属实：`RefundStateMachine` 无任何调用方；`ChannelNotifyController` 只有 `POST /notify/pay/{channel}`；退款 Job 仅有支付侧 `PayTimeoutJob`，无退款查询 Job | 按卡 B8 修 |
| B10 | 保证金缴费/退还原路缺失、罚款空置、90 天无纠纷无数据源 | 属实：`DepositService.payDepositWeb`(:75) 仅 `changeDeposit`+写日志；`scanAndRefundResigned`(:154) 仅置零+日志不打款（注释自陈无纠纷数据源）；`DepositLogTypes.FINE=30` 零调用；`WithdrawService.remitOne` mock 打款、`SettWithdraw` 无渠道打款流水号列 | 按卡 B10 修 |
| B11 | 运费险购买侧缺失、hasFreightInsurance 恒 0、不收保费 | 属实：`AftersaleMqServiceImpl` :94/:128 恒写 0（updateShipped/updateConfirmed 亦不透传）；`buildInsurance`(:238) `premiumFen=0L`、claimFen 恒 2500；订单建单/试算/事件/清算均无保费字段；理赔链路（72h 延时 + claimInsurance）本身可用 | 按卡 B11 修 |

已落地、本规划**不得重复**的整改：pay/aftersale/settlement/marketing/user/product 的 outbox 迁移（FIXES_D/E/F/G）、
支付多尝试墓碑槽位（sql/pay/V4）、清算三档瀑布与穿仓 status=2（sql/settlement/V4）、逐清算单 REQUIRES_NEW 结算（FIXES_E P1-4）。

---

## 1. 任务卡总览与依赖图

```
                         ┌──────── B11 运费险购买侧（契约 C4/C5/C6/C7）
                         │
 P2-5 退款三段式 ────────┼────── B8 渠道双轨 + 退款回调/查询（契约 C1/C2/C3）
   (RefundServiceImpl)   │        │
         ▲               │        │ 真实渠道 SPI 骨架
         │               │        ▼
         └── 同收敛入口 ──┘   （B10 打款侧 SPI 复用 B8 的渠道密钥/路由骨架风格）
                                  │
 B10 保证金真实资金链路（C1/C8/C9 + DDL-D2）◄──── P0-1 工单/告警
   缴费支付单 / 罚款 / 清退打款 / 90 天纠纷查询           │
                                  │                    │
 P0-1 穿仓消费者+工单+日扫+自动补扣 ──┴── 自动补扣挂在 B10 的"保证金到账确认"钩子
 P2-4 对账逐差异 REQUIRES_NEW（独立，无依赖）
 B9 回归测试卡（独立，无依赖）

 P2-1 售后延时消息 eventId/消费流水（与 B11 同属 shop-aftersale-service，共用
      t_aftersale_mq_consume 与保险事件链路，两卡合并评审、不得重复改同一发送点）
 R-B6 售后 Feign 空体失败语义硬化（依赖 P2-1 的"消费流水与业务同事务回滚"才能
      MQ 重试闭合；退款对接点随 P2-5 三段式一起验证）
 API-F 售后/支付入参契约硬化（纯入参校验/越权守卫，独立无依赖；⑦shop-api 内部
      命令注解随 §2 契约包 C11 统一合入）
```

- P2-5 必须先于（或与）B8 同批评审：B8 的回调/查询终态与 P2-5 的同步终态必须共用同一 CAS 收敛方法，否则双路径竞争会重复发 REFUND_SUCCESS。
- B11 的契约（C4-C7）只增字段、不改语义，可与其他卡并行；清算侧保费入账依赖订单事件透传先合入。
- P0-1 的消费者/工单/日扫不依赖 B10；其中"新缴保证金自动补扣挂起缺口"的钩子依赖 B10 的缴费到账确认点。
- B10 的保证金缴费复用支付单链路，依赖契约 C1（payScene）先合入。

---

## 2. 全局契约变更统一清单（shop-api / shop-common，业务代理不得自行改）

> 均为**向后兼容新增**：新字段一律包装类型 + 默认空值语义，旧消费者不改即不感知。

| 编号 | 模块/文件 | 变更 |
|---|---|---|
| C1 | `shop-api` `com.shop.api.pay.dto.CreatePaymentCommand` | 新增 `Integer payScene`（1 普通商品 2 组合 3 好友代付 4 保证金缴费，null 按 1 处理）；新增常量类 `com.shop.api.pay.enums.PayScenes`。支付单 `t_pay_order.pay_scene` 列已存在，仅扩码值语义，无 DDL |
| C2 | `shop-api` `com.shop.api.pay.event.PaymentSucceededEvent` | 新增 `Integer payScene`（支付域 completeSuccess 发事件时从支付单回填）。**所有 ORDER_PAID 消费组必须加场景守卫**：商品场景(1/2/3)才走原逻辑；scene=4 仅保证金缴费消费者处理。已知消费方：settlement `PaymentSucceededListener`（CG `cg_sett_paid`）、order 域支付成功 Listener（实施时在 shop-order-service `mq/listener` 下定位 ORDER_PAID 消费者）、营销/其他域如有同 topic 消费者一并排查 |
| C3 | `shop-api` `com.shop.api.pay.dto.RefundDTO` | 新增 `String channelRefundNo`、`Integer queryStatus`（供 B10/运营侧观察渠道受理号；非必须，若与现有字段重复可降级为 pay 服务内部 DTO，契约评审定） |
| C4 | `shop-api` `com.shop.api.marketing.dto.PriceCalcResult` | 新增 `Long insurancePremiumFen`（运费险保费，分，未购险为 0/null）；明确 `payFen = 商品应付合计 + insurancePremiumFen`（价格恒等式注释补充保费项） |
| C5 | `shop-api` `com.shop.api.order.dto.OrderDTO` | 新增 `Long insurancePremiumFen`、`Integer hasFreightInsurance`（0/1） |
| C6 | `shop-api` `com.shop.api.order.event.OrderConfirmedEvent` | 新增 `Integer hasFreightInsurance`、`Long insurancePremiumFen` |
| C7 | `shop-api` `com.shop.api.order.event.OrderShippedEvent` | 新增 `Integer hasFreightInsurance`（发货时售后窗口即建单，必须在此携带；保费可选随 C5 回查，事件本身加 premium 更好评审定，规划按带 `insurancePremiumFen` 实施） |
| C8 | `shop-api` **新增** `com.shop.api.aftersale.client.AftersaleClient`（FeignClient，path `/inner/aftersale`） | 方法：`@GetMapping("/disputes/exists") Result<MerchantDisputeDTO> existsOpenDispute(@RequestParam Long merchantId, @RequestParam LocalDateTime since)`；新增 DTO `MerchantDisputeDTO{Boolean exists; Long openCount; Long recentFinishedCount}`。判据：该商户在 since 之后存在**未终结**售后/介入单（t_aftersale_order.status ∉ {50,55,90} 或 t_aftersale_dispute.status≠30）即 exists=true。aftersale 侧新增 `InnerAftersaleController` 实现 |
| C9 | `shop-api` `com.shop.api.settlement.dto.ClearingBreakdown` | 新增 `Long insurancePremiumFen`（清算快照透传保费，`ClearingRegisteredEvent` 自动携带） |
| C10 | shop-common `com.shop.common.constant.MqTopics` | **无需新增 topic**：B8 回调为 HTTP 直连；P0-1 复用已有 `REFUND_SHORTFALL="shop_refund_shortfall"`。仅在常量类补一句 scene 码值无关。若 P0-1 工单需要运营后台站内信，新增 topic 需先改本类——本期不引入 |
| C11 | shop-api `com.shop.api.user.dto` 三个内部命令（API-F ⑦，W0 契约清单） | `PointsRefundCommand`：补 `@NotNull userId`、`@NotBlank bizNo`、`@NotNull @Min(1) Long points`（当前零注解）；`AmountCommand`：amountFen 加 `@Min(1)`、remark 加 `@Size(max=256)`；`PointsLockCommand`：points 加 `@Min(1)`、deductFen 加 `@Min(1)`。注解生效还需各 inner 控制器方法参数加 `@Valid`（user 服务侧，随 C11 一并评审，业务代理不单独改 shop-api） |

支付域 SPI（`PayChannelClient` / `ChannelRouter` / `ChannelSecretProvider`）在 shop-pay-service 内部，不属于 shop-api；
B10 的打款 SPI（`RemitChannelClient`）落在 shop-settlement-service 内部，也不需要 shop-api 变更。

---

## 3. DDL 总清单（无 Flyway，information_schema 守卫、可重复执行；续编版本号）

> 现有版本：pay 为 V2、V4（**续编 V5**）；settlement 为 V2、V3、V4（**续编 V5**）；
> aftersale 仅 V2（**续编 V3**）；order 仅 V2（**续编 V3**）。

### D1. `sql/pay/V5__pay_refund_callback.sql`（shop_pay 库）

```sql
USE shop_pay;

-- 1) 退款单增加主动查询补偿所需列
DROP PROCEDURE IF EXISTS pay_v5_add_refund_columns;
DELIMITER //
CREATE PROCEDURE pay_v5_add_refund_columns()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_pay' AND TABLE_NAME='t_pay_refund'
                     AND COLUMN_NAME='last_query_time') THEN
        ALTER TABLE t_pay_refund
            ADD COLUMN last_query_time DATETIME NULL COMMENT '最近一次主动查询渠道时间' AFTER retry_count,
            ADD COLUMN query_count INT NOT NULL DEFAULT 0 COMMENT '主动查询次数' AFTER last_query_time,
            ADD KEY idx_status_query (status, last_query_time);
    END IF;

    -- 2) 渠道回调幂等表复用为退款回调（notify_type=2 已在 V2 注释中定义），补 refund_no 列
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_pay' AND TABLE_NAME='t_pay_notify_log'
                     AND COLUMN_NAME='refund_no') THEN
        ALTER TABLE t_pay_notify_log
            ADD COLUMN refund_no VARCHAR(32) NULL COMMENT '退款单号（notify_type=2 时填写）' AFTER pay_no,
            ADD KEY idx_refund_no (refund_no);
    END IF;
END //
DELIMITER ;
CALL pay_v5_add_refund_columns();
DROP PROCEDURE IF EXISTS pay_v5_add_refund_columns();
-- t_pay_order.pay_scene 注释扩展为 1普通 2组合 3代付 4保证金缴费（列已存在，无需 ALTER）
```

### D2. `sql/settlement/V5__funds_gap.sql`（shop_settlement 库）

```sql
USE shop_settlement;

DROP PROCEDURE IF EXISTS settlement_v5_add_columns;
DELIMITER //
CREATE PROCEDURE settlement_v5_add_columns()
BEGIN
    -- 1) 清算单增加运费险保费（平台/保险收入，不退）
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_settlement' AND TABLE_NAME='t_sett_clearing'
                     AND COLUMN_NAME='insurance_premium_fen') THEN
        ALTER TABLE t_sett_clearing
            ADD COLUMN insurance_premium_fen BIGINT NOT NULL DEFAULT 0
                COMMENT '运费险保费（分，平台保险收入，退款不退）' AFTER marketing_subsidy_fen;
    END IF;

    -- 2) 提现单增加真实打款渠道流水与查询补偿列
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_settlement' AND TABLE_NAME='t_sett_withdraw'
                     AND COLUMN_NAME='channel_remit_no') THEN
        ALTER TABLE t_sett_withdraw
            ADD COLUMN channel_remit_no VARCHAR(64) NULL COMMENT '渠道代发流水号' AFTER bank_name,
            ADD COLUMN remit_fail_reason VARCHAR(256) NOT NULL DEFAULT '' COMMENT '打款失败原因' AFTER channel_remit_no,
            ADD COLUMN last_query_time DATETIME NULL COMMENT '最近打款查询时间' AFTER remit_fail_reason,
            ADD COLUMN query_count INT NOT NULL DEFAULT 0 COMMENT '打款主动查询次数' AFTER last_query_time,
            ADD UNIQUE KEY uk_channel_remit_no (channel_remit_no);
    END IF;

    -- 3) 保证金流水支持真实缴费/退还链路
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_settlement' AND TABLE_NAME='t_sett_deposit_log'
                     AND COLUMN_NAME='status') THEN
        ALTER TABLE t_sett_deposit_log
            ADD COLUMN status TINYINT NOT NULL DEFAULT 20
                COMMENT '单据状态：10处理中(缴费待支付/退还打款中) 20成功 30失败' AFTER log_type,
            ADD COLUMN pay_no VARCHAR(32) NOT NULL DEFAULT '' COMMENT '缴费支付单号（log_type=10）' AFTER status,
            ADD COLUMN channel_remit_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '退还打款渠道流水号（log_type=40）' AFTER pay_no,
            ADD COLUMN last_query_time DATETIME NULL COMMENT '退还打款最近查询时间' AFTER channel_remit_no,
            ADD KEY idx_status (status),
            ADD KEY idx_pay_no (pay_no);
    END IF;
END //
DELIMITER ;
CALL settlement_v5_add_columns();
DROP PROCEDURE IF EXISTS settlement_v5_add_columns();

-- 4) P0-1 穿仓缺口工单表
CREATE TABLE IF NOT EXISTS t_sett_shortfall_workorder (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    event_id        VARCHAR(64)  NOT NULL COMMENT 'REFUND_SHORTFALL 事件ID（幂等键）',
    reverse_no      VARCHAR(32)  NOT NULL COMMENT '冲正流水号',
    refund_no       VARCHAR(32)  NOT NULL COMMENT '退款单号',
    order_no        VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '订单号',
    merchant_id     BIGINT       NOT NULL COMMENT '商户ID',
    shortfall_fen   BIGINT       NOT NULL COMMENT '挂起缺口金额（分）',
    clawed_back_fen BIGINT       NOT NULL DEFAULT 0 COMMENT '已自动补扣金额（分）',
    status          TINYINT      NOT NULL DEFAULT 10 COMMENT '10待处理 20已告警 30已追缴结清 40人工核销',
    alert_count     INT          NOT NULL DEFAULT 0 COMMENT '告警次数',
    remark          VARCHAR(512) NOT NULL DEFAULT '' COMMENT '处理备注',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_merchant_status (merchant_id, status),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款穿仓缺口工单（P0-1）';
```

### D3. `sql/order/V3__order_freight_insurance.sql`（shop_order 库）

```sql
USE shop_order;
DROP PROCEDURE IF EXISTS order_v3_add_insurance;
DELIMITER //
CREATE PROCEDURE order_v3_add_insurance()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_order' AND TABLE_NAME='t_order_order'
                     AND COLUMN_NAME='insurance_premium_fen') THEN
        ALTER TABLE t_order_order
            ADD COLUMN insurance_premium_fen BIGINT NOT NULL DEFAULT 0
                COMMENT '运费险保费（分，已含在 pay_fen 内）' AFTER pay_fen,
            ADD COLUMN has_freight_insurance TINYINT NOT NULL DEFAULT 0
                COMMENT '是否购运费险 0否 1是' AFTER insurance_premium_fen;
    END IF;
END //
DELIMITER ;
CALL order_v3_add_insurance();
DROP PROCEDURE IF EXISTS order_v3_add_insurance;
```

### D4. `sql/aftersale/V3__insurance_premium.sql`（shop_aftersale 库）

```sql
USE shop_aftersale;
DROP PROCEDURE IF EXISTS aftersale_v3_add_premium;
DELIMITER //
CREATE PROCEDURE aftersale_v3_add_premium()
BEGIN
    -- 窗口表已有 has_freight_insurance（V2），补保费快照，理赔单调阅
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_aftersale' AND TABLE_NAME='t_aftersale_window'
                     AND COLUMN_NAME='premium_fen') THEN
        ALTER TABLE t_aftersale_window
            ADD COLUMN premium_fen BIGINT NOT NULL DEFAULT 0 COMMENT '运费险保费快照（分）'
                AFTER has_freight_insurance;
    END IF;
END //
DELIMITER ;
CALL aftersale_v3_add_premium();
DROP PROCEDURE IF EXISTS aftersale_v3_add_premium;
```

---

## 4. 任务卡

### 卡 B8：支付/退款/打款渠道生产级骨架（mock 双轨）+ 退款异步回调 + 退款查询补偿（激活 RefundStateMachine）

**现状证据**

- `channel/PayChannelClient`（SPI：supports/createOrder/query/refund/downloadBill）仅有单一实现 `MockPayChannelClient`（@Component，supports 所有非 BALANCE 渠道，对账单读 classpath `recon/mock-bill-{channel}-{date}.json`）；`ChannelRouter` 为 List 注入 `findFirst`；`ChannelSecretProvider` 已具备六渠道 MOCK_* 常量、`SHOP_PAY_CHANNEL_<CHANNEL>_SECRET` 环境覆盖、prod `validate()` fail-fast 与双开关豁免；`ChannelLimits` 映射 MOCK_* 渠道码。
- `ChannelNotifyController` 仅 `POST /notify/pay/{channel}`；**无退款回调端点**；`t_pay_notify_log.notify_type` 已预留 1支付/2退款，但无 refund_no 列。
- `RefundStateMachine`（WAIT(10)→PROCESSING(20)/FAIL(40)；PROCESSING→SUCCESS(30)/FAIL；FAIL→WAIT/PROCESSING/REVERSED(50)；SUCCESS→REVERSED）**零调用方**；`RefundMapper` 已有 markProcessing/markSuccess(IN 10,20)/markFail/reopen/markReversed 全套 CAS。
- 退款终态只在 `RefundServiceImpl.executeRefund`(:141) 同步路径产生，渠道受理中（`ChannelRefundResult.status=10`）目前无后续推进手段（该卡与 P2-5 共同重构，见卡 P2-5 的边界切分）。

**改动模块与精确文件清单（shop-pay-service，除注明外均为新增/修改）**

新增（真实渠道适配器，包 `com.shop.pay.channel.adapter.real`）：
- `WechatPayChannelClient`、`AlipayPayChannelClient`、`BankPayChannelClient`、`UqrPayChannelClient`、`HuabeiPayChannelClient`、`BaitiaoPayChannelClient`（6 个，均 `@Component` + `@ConditionalOnProperty("shop.pay.real-channels-enabled")` + `@Order(0)`）。
- `real/RealChannelProperties`（@ConfigurationProperties("shop.pay.channel.real")：每渠道 endpoint/connectTimeout/readTimeout/商户号/证书路径占位，密钥**只经 `ChannelSecretProvider`**，不得落配置明文）。
- `real/RealChannelHttpClient`（统一 HTTP 封装：签名、超时、重试、请求/响应脱敏日志）、`real/RealChannelSigner`（按渠道签名，HMAC/RSA 留接线点）、`real/RealResponseParser`（下单/查询/退款/对账四类响应解析与错误码映射）。
- 六渠道真实对账单下载在各自 adapter 的 `downloadBill(String channel, LocalDate date)` 内按渠道账单协议实现骨架（CSV/JSON 解析为 `ChannelBillRecord`）。

修改：
- `channel/MockPayChannelClient`：加 `@ConditionalOnProperty(value="shop.pay.mock-channels-enabled", havingValue="true", matchIfMissing=true)` + `@Order(100)`，supports 保持匹配 MOCK_*，保证 real bean 存在时优先、不存在时 mock 兜底。
- `channel/PayChannelClient`：SPI 新增退款查询 `ChannelRefundQueryResult queryRefund(ChannelRefundQueryRequest req)`（入参 channelCode/channelOrderNo/channelTxnNo/channelRefundNo/refundNo；出参复用受理码 10/20/30 + channelRefundNo + failReason）。Mock 实现按 `shop.pay.mock.query-success` 返回成功/处理中。
- `channel/ChannelRouter`：补 `queryRefund(...)` 委托；余额退款不经过 SPI 的既有边界不变。
- `feature/payment/controller/ChannelNotifyController`：新增 `POST /notify/refund/{channel}`（或新增并列 `RefundNotifyController`，建议同控制器加方法，路径风格一致）。
- `feature/payment/dto/`：新增 `RefundNotifyRequest{notifyId, refundNo, channelRefundNo, amountFen, status(SUCCESS/FAIL), failReason, sign, finishTime}` 与内部参数对象 `RefundNotifyParams`（风格对齐 `ChannelNotifyParams`）。
- `feature/refund/service/RefundService(+Impl)`：新增 `RefundDTO handleRefundNotify(RefundNotifyParams params)` 与 `void convergeFromQuery(Long refundId)`；所有迁移点调用 `RefundStateMachine.assertTransition`（正式接活状态机）。
- `feature/refund/support/RefundConvergeService`（新增，**B8 与 P2-5 共用的唯一终态收敛漏斗**，见"核心步骤 4"）。
- `feature/refund/mapper/RefundMapper.java` + `resources/mapper/RefundMapper.xml`：新增 `RefundOrder selectProcessingForQuery(@Param("before") LocalDateTime, @Param("limit") int)`、`int touchQuery(@Param("id") Long, @Param("now") LocalDateTime)`（last_query_time=now、query_count+1，CAS 防多节点重复查）。
- `feature/refund/job/RefundQueryJob.java`（新增）：`@Scheduled(fixedDelayString="shop.pay.refund.query-delay-ms:30000")` + `@SchedulerLock(name="pay:refund-query", lockAtMostFor="PT2M", lockAtLeastFor="PT5S")`；扫描 status=20 且（last_query_time 为空且创建超 60s，或 last_query_time 早于 30s）的退款单，每批 ≤50，逐单调 `RefundConvergeService.converge(refundId, QUERY)`；余额退款不查渠道（由同步路径终结）。
- `feature/payment/support/SignVerifier`：抽出按字段集验签的内部方法供退款回调复用（退款签名字段集：notifyId/refundNo/channelRefundNo/amountFen/status），验签失败仍返回 60002。

**核心实现步骤（类名/方法签名/topic/状态机）**

1. 双轨启动：prod 配置 `shop.pay.mock-channels-enabled=false` + `shop.pay.real-channels-enabled=true` 时仅 real bean 生效，`ChannelSecretProvider.validate()` 已有的 prod fail-fast 保证密钥非内置默认；缺失任一真实渠道密钥直接启动失败（fail-fast，不允许静默回退 mock）。real adapter 未完成真实联调的方法体必须抛 `BizException(DEPENDENCY_FAIL, "真实渠道<X>能力未联调: ...")`，**禁止返回伪成功**。
2. 退款回调端点：`ChannelNotifyController → RefundServiceImpl.handleRefundNotify` 顺序固定为 ①验签（失败写 notify_log sign_status=2、handle_status=2，返回 60002）②`t_pay_notify_log` UK(channel_code,notify_id) INSERT 幂等（notify_type=2、refund_no 落列），重复通知直接返回当前单成功 ACK ③金额与退款单 amount_fen 一致校验 ④调 `RefundConvergeService.converge(refundId, NOTIFY)`。
3. 主动查询：`RefundQueryJob` 仅捞 status=20；`touchQuery` CAS 成功者才真正调 `channelRouter.queryRefund`；结果 10 跳过、20/30 进入 converge。查询本身**无事务、无 DB 写在渠道调用内**（只在收敛阶段开短事务）。
4. 收敛漏斗（状态机接活点）：`RefundConvergeService.converge(Long refundId, Trigger t)`：
   - 短事务读单 + `stateMachine.assertTransition(status, target)`；
   - 成功：`refundMapper.markSuccess(refundNo, 20)`（SQL 已是 IN(10,20)→30 CAS）→ **CAS 获胜方**才执行 split 终态回写（`channelFlowMapper.addPaidFen`/split markSuccess）、`markRefunded` 支付单累计、`outboxPublisher.publish(MqTopics.REFUND_SUCCESS, "refund", event, refundNo)`（tag 沿用 FIXES_D 的 "refund"）；失败：`markFail`（retry_count+1，40）。回调、查询 Job、P2-5 同步三段式**全部只经此方法**，保证 REFUND_SUCCESS 只发一次。
   - REVERSED(50) 迁移保留给清算冲正/运营冲正调用（本期仅接线状态机，不新增冲正入口）。
5. 对账单：recon `ReconcileJob/ReconcileServiceImpl` 不改动调用方（仍 `downloadBill(channel,date)`），real adapter 替换数据来源即可；mock classpath 账单继续作为本地/CI 环境数据源。

**契约变更**：C2（PaymentSucceededEvent.payScene 虽主要服务 B10，但由支付域发事件改动一并在本卡合入）、C3（评审定）。SPI 变更全部在 pay 服务内部。

**DDL**：D1（pay V5）。

**单测用例清单**
- Mock/6 个 real adapter 的 `supports` 矩阵：开关四种组合下 `ChannelRouter` 选中正确 bean；real bean 缺密钥时启动 fail-fast；real adapter 未联调方法抛 DEPENDENCY_FAIL 且不伪成功。
- `RefundConvergeServiceTest`：20→30 首次成功发一次 outbox；并发两次 converge（回调+查询同时到达）仅 CAS 获胜方发 REFUND_SUCCESS；30 态重复 converge 零副作用；20→40 markFail retry_count 递增；非法迁移（50→30）抛异常。
- `handleRefundNotify`：错误签名→60002 且不写业务状态；同 notifyId 重放→第二次 ACK 且无重复 outbox；金额不符拒绝；notify_type=2 行字段正确。
- `RefundQueryJobTest`：仅捞 20 单；touchQuery CAS 失败者不调渠道；查询返回 10 不动单；20/30 收敛；ShedLock 单节点执行。
- 退款查询 SPI 的 mock 行为受 `shop.pay.mock.query-success` 切换。

**E2E 验收点（shop-e2e，HTTP 黑盒，建议新增 `PayRefundCallbackE2ETest`，沿用 ApiClient/Poller/World）**
1. mock 渠道下单→回调支付成功→发起退款（售后或直接 inner）→退款 20 后，模拟渠道 POST `/notify/refund/MOCK_ALIPAY`（带正确 HMAC）→轮询退款单 30、售后单完成、清算冲正完成、余额到账。
2. 同一回调原文重放 3 次，全部 200，REFUND_SUCCESS 事件仅一条（下游只入账一次）。
3. 不发回调，打开 mock query-success，RefundQueryJob 在两个调度周期内把 20 单推进到 30。
4. 错误签名回调返回 60002，退款单仍 20。

**环境残留声明（真实渠道）**
- 6 个 real adapter 为**生产级骨架**：配置绑定、密钥读取（`ChannelSecretProvider` + 环境变量/KMS 注入）、HTTP/签名/解析/错误码/超时/日志脱敏的代码结构完整；但真实 endpoint、证书、签名算法细节、账单字段映射、webhook 地址在渠道商户平台注册、沙箱/生产真实联调均属环境残留——本地 kind 不可接入真实渠道；CI/E2E 永远跑 mock 双轨。
- KMS 接线点：`ChannelSecretProvider` 现支持环境变量覆盖；真实 KMS（KMS SDK / Vault）取密在密钥提供者侧留 `SecretFetcher` 接口（新增），其真实实现为环境残留。

**风险与回归面（对账与幂等）**
- 资金风险核心是"退款终态重复/丢失"：唯一收敛漏斗 + markSuccess CAS + outbox 同事务 + 下游消费 UK(event_id) 四层保证不重；查询 Job + 回调双通道保证不丢；T+1 recon 仍为最终兜底（渠道有本地无/状态不符进差错单）。
- 对账单回归：real adapter 账单解析必须跑通现有 `ReconcileServiceImpl` 三差异（长款补单/短款挂账/金额调账），先用与 mock 同构的样本做单测，真实脱敏录制样本随联调补齐。
- 回归面：PaymentServiceImpl 下单/回调/查询、refund 全部现有测试、FIXES_D 的 8 个 outbox 发送点、FIXES_G 墓碑槽位。

---

### 卡 B9：技服费资金守恒——事实核查结论为"已修复"，仅补防回退回归测试

**事实核查结论（详 §5）**：审计点不成立。`SplitEngine.java:77` 已从商户应收扣减技服费
（`base - commission - channelFee - TECH_FEE_FEN + freight`），审计所指 :68 行号已失效；
`SplitEngineTest` 已含资金守恒恒等式断言；`SettleClearingExecutor` 技服费平台入账与扣减口径一致，无虚记收入。

**改动模块与精确文件清单（仅测试，零生产代码改动）**
- `shop-settlement-service/src/test/.../engine/SplitEngineTest.java`（增补用例，不改既有 5 用例）。
- `shop-settlement-service/src/test/.../statement/SettleClearingExecutorTest.java`（新增/补充断言：每笔记账金额之和与 SplitResult 守恒；该类 FIXES_E 已存在，加用例即可）。
- shop-e2e `SettlementE2ETest.java`：补一条端到端资金平衡断言（若现有用例已覆盖支付→收货→结算，则加金额等式轮询断言，不新增测试类）。

**核心实现步骤（仅测试）**
1. 新增 SplitEngine 用例：①技服费 50 分从商户应收扣减的直接断言（多组 product/freight/优惠组合下 `merchantReceivable = base - commission - channelFee - 50 + freight`）；②守恒恒等式对**全部输入组合**成立（参数化：零运费/零优惠/满减大于佣金/极小金额 83 分通道费取 0 时商户应收 ≥0 的边界）；③退款净额场景：SettleClearingExecutor 以 `reversed*` 净额入账后，平台三笔收入（佣金净 + 技服费全额 + 通道费全额）+ 商户货款净额 + 营销出账净额，与清算单原始分账金额守恒。
2. E2E：支付 100.00 元订单（复用 World/DataFactory 既有下单），完成确认收货与日终结算后，断言平台账户 TECH_FEE_INCOME 流水恰为 50 分/单、商户可提现 = 应收净额，且"用户实付+营销补贴 = 商户入账+佣金+通道费+技服费"全链路成立。

**契约变更**：无。**DDL**：无。

**单测/E2E 用例清单**：见上（参数化守恒、净额守恒、E2E 全链路等式）。

**环境残留声明**：无（纯测试，mock 环境可完成）。

**风险与回归面**：零生产风险；意义在于把审计口径钉成可执行断言，防止后续调整公式（如 design 7.2.2 文字漏写"-技术服务费"误导）回退为虚记收入。若未来技服费规则变更（不再固定 50 分/单），必须同步改恒等式与 design。

---

### 卡 B10：保证金真实资金链路（缴费走支付单 / 清退退还与提现走打款 SPI / 罚款落账 / 90 天无纠纷取数）

**现状证据**
- `DepositService.payDepositWeb`(:75)：`merchantMapper.changeDeposit` 直接加余额 + 写 DP 日志，**无支付单、无渠道入金**；`DepositPayRequest{amountFen, clientToken}` 无支付方式字段。
- `scanAndRefundResigned`(:154，`DepositRefundJob` cron `0 0 3 * * ?`，ShedLock)：注释自陈"当前域无售后纠纷数据来源，以观察期满作为无纠纷的实现口径"；退还仅 `changeDeposit(-balance)` 置零 + log_type=40 日志，**不打款**。
- `DepositLogTypes`：PAY=10 / REFUND_COMPENSATE=20 / FINE=30（**零调用**）/ RESIGN_REFUND=40。
- `WithdrawService.remitOne`：20→30 CAS + 解冻 + 手续费平台收入 + WITHDRAW_RESULT outbox，**mock 打款、无渠道流水号**；`t_sett_withdraw` 无渠道打款列；`WithdrawRemitJob` cron `0 0 9-22 * * ?`。
- `MerchantMapper.changeDeposit / changeDepositPartial / selectForUpdate` 已具备行锁原子变动；`FlowChangeTypes` 已到 42。
- shop-api **无 aftersale client 包**（仅有 enums/dto/event），90 天纠纷判定无跨域查询契约。

**改动模块与精确文件清单**

shop-settlement-service（新增包 `com.shop.settlement.remit`）：
- `remit/RemitChannelClient`（SPI：`boolean supports(Integer channel)`、`RemitResult remit(RemitRequest req)`、`RemitQueryResult query(RemitQueryRequest req)`；RemitRequest 含 withdrawNo/depositLogNo/merchantId/channel/account/accountName/bankName/amountFen，渠道幂等键=业务单号）。
- `remit/MockRemitChannelClient`（@Component + `@ConditionalOnProperty("shop.settle.remit.mock-enabled", matchIfMissing=true)`，本地/CI 默认成功，提供按配置延迟受理的开关供查询补偿联调）。
- `remit/adapter/real/`：`BankRemitClient`、`AlipayRemitClient`（真实代发骨架，密钥/证书接线风格对齐 B8 的 real 包，未联调方法抛 DEPENDENCY_FAIL 不伪成功）、`RemitSecretProvider`（环境变量 `SHOP_SETTLE_REMIT_<CHANNEL>_SECRET`，prod fail-fast）。
- `remit/RemitRouter`（List findFirst）。
- `deposit/service/DepositService.java`（重构，见步骤）、`deposit/service/DepositPaySettlementService.java`（或在 DepositService 内分方法，建议拆出）、`deposit/dto/DepositPayRequest.java`（加 `Integer payMethod`、`Integer terminal`，校验非空）。
- `deposit/mapper/SettDepositLogMapper.java`（+ XML 如使用 XML）：新增 `int casStatus(@Param("logNo") String, @Param("from") int, @Param("to") int)`、`SettDepositLog selectByPayNo(String payNo)`、待打款退还扫描 `selectResignRefundPending(now, limit)`。
- `deposit/entity/SettDepositLog.java`：加 status/payNo/channelRemitNo/lastQueryTime（对齐 DDL-D2）。
- `deposit/controller/MerchantDepositController.java`：`pay` 改为返回 `{logNo, payNo, payUrl}`（先建缴费单再调支付域）。
- `merchant/controller/AdminController.java`：新增 `POST /admin/deposit/fine`（入参 `DepositFineRequest{merchantId, amountFen, reason, clientToken}`）；新增 `POST /admin/withdraw/{no}/mark-failed`（暴露既有 markFailed，供打款失败人工/自动处理；评审可选）。
- `deposit/job/DepositRefundJob.java`：扫描结果先过 `AftersaleClient` 纠纷校验，再走打款（见步骤 3）。
- `deposit/job/RemitQueryJob.java`（新增，ShedLock `settle:remit-query`，每 60s；同时扫 t_sett_withdraw 30? 否——打款受理中的单：提现在 remitOne 受理后需新增"打款中"中间态复用 status=20 + channel_remit_no 非空 表示已提交待确认；保证金退还 log.status=10 + channel_remit_no 非空）。
- `withdraw/service/WithdrawService.java`：`remitOne` 改为事务外调 `RemitRouter.remit`（三段式，对齐 P2-5 原则），落 channel_remit_no；终态 30 由查询 Job/回执 CAS 推进；终态失败走既有 markFailed（冻结退回 22 并出 WITHDRAW_RESULT）。
- `withdraw/entity/SettWithdraw.java` + mapper：加 channelRemitNo/remitFailReason/lastQueryTime/queryCount。
- `mq/listener/DepositPaySucceededListener.java`（新增）：`MqListener<PaymentSucceededEvent>`，topic `MqTopics.ORDER_PAID`，**消费组 `cg_sett_deposit_pay`（新增，需加入 create-topics.sh 消费组列表 :39）**，仅处理 payScene=4。
- `enums/FlowChangeTypes.java`：新增 `DEPOSIT_FINE_INCOME=43`、`INSURANCE_PREMIUM_INCOME=16`（16 在本卡被 B11 使用；为避免两卡争抢常量编号，16/43 在本规划统一分配）。
- `merchant/service/MerchantAccountService`（记账服务，FIXES_E 已有 accountService）：罚款入平台账户 `creditAvailable(0, PLATFORM, logNo, 43, amount, "保证金罚款入账")`。

shop-pay-service（配合，改动小）：
- `PaymentServiceImpl.createPayment`：接受 C1 的 payScene=4；该场景 subject 固定"保证金缴费"，支付单 order_no=DP 流水号（受 t_pay_order.uk_order_no 保护天然幂等），不产生 t_pay_channel_flow 之外的特殊逻辑；墓碑槽位 `uk_order_active` 对 DP 单号同样生效，允许商户断网重试缴费。
- PaymentSucceededEvent 发事件回填 payScene（C2）。
- 支付域 ORDER_PAID 自身不区分场景；**order 域支付成功 Listener 必须加守卫**：查无此业务订单且 payScene=4 时直接 ACK（严禁抛错毒丸）。settlement `ClearingService.onPaymentSucceeded` 首行加 `if (event.getPayScene()!=null && event.getPayScene()==PayScenes.DEPOSIT) return;`（其自身的 mq_consume 记录照写，避免重投）。

shop-aftersale-service：
- 新增 `inner/controller/InnerAftersaleController.java`（`@RestController @RequestMapping("/inner/aftersale")`）实现 C8：按 merchantId 聚合 t_aftersale_order（merchant_id 列已存在）与 t_aftersale_dispute，返回窗口期内未终结单数。
- 新增 mapper 查询 `countOpenByMerchantSince(merchantId, since)`、`countRecentIntervene(...)`（介入单 80/裁决中按状态码）。

deploy：
- `deploy/rocketmq/create-topics.sh:39` 消费组列表追加 `cg_sett_deposit_pay`（topic 复用 shop_order_paid 现有常量名——实施时以 MqTopics.ORDER_PAID 实际值为准，该 topic 已在 topics 列表内，仅加消费组）。

**核心实现步骤（状态/方法签名/幂等）**
1. 缴费：`POST /merchant/deposit` → ①短事务建 DP 日志 logNo（log_type=10、status=10、amount、uk_log_no 幂等 + clientToken 防重，**不动 deposit_balance**）②事务外调 `payClient.createPayment(CreatePaymentCommand{orderNo=logNo, userId=merchantId 对应付款用户, payMethod, amountFen, subject="保证金缴费", terminal, payScene=4})`，回写 pay_no，返回 payUrl。③`DepositPaySucceededListener` 收到 scene=4 事件：t_sett_mq_consume 幂等 → DP 日志 CAS 10→20（获胜方）→ `changeDeposit(+amount)` + 账户流水 DEPOSIT_PAY(40)（biz_no=logNo，UK(biz_no,change_type) 幂等）→ 触发 P0-1 自动补扣钩子 `shortfallClawback.onDepositPaid(merchantId)`（见卡 P0-1）。支付失败/超时：DP 日志保持 10 或由支付关单事件/对账置 30（本期以查询/对账兜底，不新增关单 MQ，文档注明）。
2. 罚款：`POST /admin/deposit/fine` → `DepositService.fine(merchantId, amount, reason, clientToken)`：`selectForUpdate` 校验余额 ≥ amount（不足返回业务错误，不允许余额负；穿仓追讨不通过罚款）→ `changeDeposit(-amount)` → 写 log_type=30 DP 日志（biz_no 用 clientToken 幂等）→ 同事务平台账户 `creditAvailable(...,43,...)`；若扣后低于 50% 阈值，复用 `publishAlert`（DEPOSIT_ALERT outbox 既有）。
3. 清退退还：`DepositRefundJob` 每日 3 点扫 status=2 清退中且 resign_time+90 天到期的商户 → 调 `aftersaleClient.existsOpenDispute(merchantId, resignTime)`：exists=true 跳过并留告警/日志（次日再判，不清零不打款）；exists=false → 建 log_type=40、status=10 的退还日志（biz_no=merchantId+期号幂等）→ 事务外 `RemitRouter.remit`（按商户结算账户/预留收款信息；收款账户来源为环境残留，见下）→ 成功受理落 channel_remit_no；`RemitQueryJob` 查询终态：成功 CAS 10→20 且 `changeDeposit(-balance)` 置零 + 商户 status→3 已清退 + DEPOSIT_REFUND(41) 流水；失败置 status=30 保留余额并 DEPOSIT_ALERT 告警人工处理。
4. 提现打款：`WithdrawService.remitOne` 同样三段式（短事务冻结已在申请/审核阶段完成 → 事务外 remit，落 channel_remit_no，保持 status=20 → 查询/回执 CAS 20→30 才 unfreezeOut + 手续费 23 + WITHDRAW_RESULT outbox；失败 markFailed 退回 22）。`WithdrawRemitJob` 职责不变，只提交打款；终态以 RemitQueryJob 为准。
5. 幂等：缴费 clientToken+uk_log_no；支付侧 order_no=logNo 幂等；到账消费 event_id + DP CAS 双幂等；打款渠道幂等键=withdrawNo/logNo（渠道侧重试不重复代发）；流水 UK(biz_no, change_type)；RemitQueryJob touch 风格 CAS 防多节点重复查（同 B8 touchQuery 模式）。

**契约变更**：C1、C2、C8。**DDL**：D2（settlement V5 的 withdraw/deposit_log 部分）。

**单测用例清单**
- 缴费：重复 clientToken 返回同 logNo；支付单 ORDER_PAID scene=4 到账后余额+amount、流水恰一笔；事件重放零副作用；scene=1 不触发入账；支付未成功余额不变。
- `ClearingService` scene 守卫：scene=4 事件不建清算单、不查 OrderClient。
- 罚款：余额充足扣减+平台 43 入账+DP 日志 30；余额不足拒绝且余额不变；扣后 <50% 发 DEPOSIT_ALERT；clientToken 重放不重复扣。
- 清退：观察期未满不处理；满 90 天但 aftersaleClient 返回 exists=true 不打款；exists=false 走 remit；remit 受理后查询成功才置零+商户 30+41 流水；查询失败余额不动、告警；重复 Job 执行幂等（uk biz_no/期号）。
- 提现：remit 受理成功但未确认时 status 保持 20、冻结不释放；查询成功 30 出款+手续费+outbox 一次；失败退回 22 且可再次申请；打款渠道异常不持有 DB 事务（可用事务同步断言/ mock 慢调用验证无事务包裹）。
- Mock/real RemitClient 开关矩阵与 prod 密钥 fail-fast。
- InnerAftersaleController：构造进行中售后/已完成售后/介入中三种数据，existsOpenDispute 判据正确。

**E2E 验收点（建议新增 `DepositE2ETest`；扩展 `SettlementE2ETest` 打款段）**
1. 商户缴保证金：下单支付→mock 支付回调成功→轮询保证金余额增加、DP 日志 20。
2. 平台罚款（管理端 token）后余额减少、平台收入增加、阈值告警可查。
3. 商户清退登记：构造 90 天前 resign_time 且无未结售后（或 inner 接口返回 false），DepositRefundJob（或手动触发端点）后余额 0、打款 mock 收到代发请求（金额=全额）、商户状态 30；存在未结售后时不退。
4. 提现申请→审核→RemitJob 提交→mock 代发→查询确认→WD 单 30、冻结扣减、手续费正确、WITHDRAW_RESULT 事件一条。

**环境残留声明（真实渠道）**
- 银行卡/支付宝真实代发（B2B 代发接口、白名单、U 盾/证书、对公账户验证）为真实渠道环境残留，本地 kind 不可联调；`BankRemitClient/AlipayRemitClient` 仅交付骨架与 fail-fast。
- **商户清退收款账户**：当前数据模型无商户绑定银行卡/支付宝账户表（t_sett_withdraw 的收款账户来自提现申请）。清退退还必须复用提现账户或新增商户结算账户绑定——本规划采用"复用最近一次成功提现账户，无则转人工、DEPOSIT_ALERT 挂起"，账户绑定产品功能属环境/产品残留，需在任务卡评审确认。
- 90 天纠纷取数的时钟：以 aftersale 域返回为准，settlement 不本地伪造无纠纷结论（修掉现有注释里的口径妥协）。

**风险与回归面（对账与幂等）**
- 缴费"先款后账"：以 ORDER_PAID 为唯一入账依据，支付域对账（长款/短款）仍兜底；DP 日志 10 态长期挂起需运营视图（可由 admin/deposit/records 扩展状态筛选，不新增表）。
- 打款"先账后款"风险：严格禁止在渠道确认前扣减保证金/解冻出款；终态只认查询 CAS，避免 mock 时代"提交即成功"的旧语义被错误沿用。
- 对账：提现/退还打款目前无对账文件输入，真实渠道代发对账文件下载/核对不在本期 SPI（RemitChannelClient 可再加 downloadRemitBill），标注为后续缺口；本期以主动查询+人工标记闭环。
- 回归面：FIXES_E 瀑布扣保证金（deductPartialForRefund 20/31 流水不变）、WithdrawService 全部既有测试（免费 3 笔/0.1% 最低 2 元/T+1/日 50 万）、DepositService 既有测试、保证金 <50% 限提规则。

---

### 卡 B11：运费险购买侧贯通（建单勾选/试算保费/事件透传/保费入清算），让既有理赔链路生产可达

**现状证据**
- 理赔侧已实现：`AftersaleMqServiceImpl.onRefundSuccess`(:208-220) 判定退货退款+窗口 hasInsurance+责任方合格+每单一次 → 建 t_aftersale_insurance + 72h 延时 outbox；`AftersaleTimeoutServiceImpl.claimInsurance` markClaimed CAS 后 `userClient.creditBalance(bizNo="INS:"+orderNo)` 理赔到账；`AftersalePolicy.insuranceEligible/insuranceClaimFen/insuranceClaimDeadline` 完整（2500 封顶、72h）。
- 购买侧三处断点：①`AftersaleMqServiceImpl` :94（onOrderShipped 新建窗口）与 :128（onOrderConfirmed 新建窗口）恒写 `setHasFreightInsurance(0)`；`updateShipped`(:98)/`updateConfirmed`(:131) 参数不含保险标识；②`buildInsurance`(:243) `premiumFen=0L`；③订单 `CreateOrderRequest`/`Order`/DDO/试算/事件/清算全无保费字段，用户根本付不出保费。
- design 8.6：保费 0.5-5 元（按距离和重量）、理赔最高 25 元、退款成功后 72h、每单一次、7 天无理由/商家责任。

**改动模块与精确文件清单**

shop-order-service：
- `order/dto/CreateOrderRequest.java`：新增 `Boolean buyFreightInsurance`（null/false 不买）。
- 新增 `order/support/FreightInsuranceCalculator.java`：`long premiumFen(CreateOrderRequest req, PriceCalcResult price)`；**口径**：design 的"按距离和重量"在当前模型无数据源（无商品重量字段、无地址距离服务），本期以配置 `shop.order.freight-insurance.premium-fen`（默认 100 分=1 元，启动校验 50≤x≤500）统一保费，仅对实物/有运费订单开放（freightFen>0 或非虚拟商品；类目黑白名单可后补）。该简化在代码注释与 design 残留中显式标注。
- `order/service/impl/OrderCreateServiceImpl.java`：试算后若勾选，计算保费并计入 PriceCalcResult.insurancePremiumFen，**payFen 含保费**；组装 Order 时写 hasFreightInsurance/premiumFen；组装 CreatePaymentCommand（调 PayClient）amountFen 用含保费的 payFen（金额校验等式：支付金额=订单 payFen 不变，天然透传）。
- `order/entity/Order.java` + mapper/XML：新增 insurancePremiumFen、hasFreightInsurance（列见 D3）。
- `order/assembler/OrderAssembler.java`：Order→OrderDTO 两字段。
- 订单发事件处（`order/mq` 下 OrderConfirmedEvent/OrderShippedEvent 构建器，实施时定位）：confirmed 事件带 hasFreightInsurance+insurancePremiumFen；shipped 事件带 hasFreightInsurance（+premiumFen）。
- 取消/超时未支付：保费未实际收取（支付单未成功），无退款动作，常规关单。

shop-api：C4（PriceCalcResult）、C5（OrderDTO）、C6（OrderConfirmedEvent）、C7（OrderShippedEvent）、C9（ClearingBreakdown）。

shop-marketing-service：
- 营销试算服务实现 PriceCalcResult 新字段的透传/赋值入口（保费不由营销优惠抵扣；marketing 仅负责把 order 侧传入/自算的 premium 放进结果——实施时以现有试算 Feign 边界为准：若保费在 order 侧试算后本地追加，则 marketing 仅 DTO 增字段无逻辑；评审时按现有 PriceCalc 调用链二选一，**禁止用优惠券/积分抵扣保费**）。

shop-settlement-service：
- `engine/SplitRequest.java`：新增 `long insurancePremiumFen`；`engine/SplitResult.java`：新增同名字段；`SplitEngine.split` 透传保费，**不参与佣金/通道费基数**（佣金基数仍 base=商品-商户优惠；通道费仍商品额×60bps），扩展恒等式：
  `用户实付(含保费) + 营销补贴 = 商户应收 + 佣金 + 通道费 + 技服费 + 运费险保费(平台/保险收入)`。
- `clearing/service/ClearingService.onPaymentSucceeded`：SplitRequest 取 `order.getInsurancePremiumFen()`（OrderDTO 新字段，经既有 OrderClient 回查链路）；`applySplit` 落 t_sett_clearing.insurance_premium_fen；payAmountFen 含保费（支付事件金额已含，等式自洽）。
- `statement/service/SettleClearingExecutor`：六笔记账之外加一笔平台收入：`accountService.creditAvailable(0L, AccountRole.PLATFORM, clearingNo, FlowChangeTypes.INSURANCE_PREMIUM_INCOME /*16*/, nz(clearing.getInsurancePremiumFen()), "运费险保费入账（不退）")`；净额结算时保费**不随 reversed\* 冲减**（退款不退保费）。
- 清算冲正 `ClearingReverseService`：退款比例基数维持"商品实付（不含保费）"——实施时核对 reverse ratio 与 RefundSplitter 的取数来自清算单商品字段而非 payAmountFen 总额；增加单测钉住"保费不退、不进比例分母"。

shop-aftersale-service：
- `mq/service/impl/AftersaleMqServiceImpl.java`：:94/:128 两处新建窗口写 `e.getHasFreightInsurance()` 与 premiumFen（shipped 事件无 premium 时置 0，confirmed 事件落 premium_fen，列见 D4）；`windowMapper.updateShipped/updateConfirmed` 签名与 XML 增加 hasFreightInsurance/premiumFen 透传（注意：先 shipped 后 confirmed 同一窗口的更新不得把 1 覆盖回 0——更新 SQL 用 `GREATEST(has_freight_insurance, #{has})` 或仅在传入 1 时置位）。
- `buildInsurance`：从窗口读 premiumFen 落 `ins.setPremiumFen(w.getPremiumFen())`（替换 :243 的 0L）；claimFen 维持封顶逻辑（现状实际运费无上行数据，恒 2500，保留注释口径）。

**核心实现步骤（金额链路）**
`勾选 → 试算 payFen=商品应付+保费 → 支付单收全款（ORDER_PAID amount 含保费）→ 清算单分账：商户应收/佣金/通道费/技服费不变，保费单列平台保险收入(16) → 发货/确认事件把 0/1 与保费写入售后窗口 → 退货退款成功：既有 72h 理赔链路自然可达（bizNo=INS:orderNo 每单一次）`。
- 保费**不在退款时退还**（design 无退保规则；与技服费/通道费同属不退费用），故 RefundSucceededEvent/冲正/售后退款金额均不含保费。
- topic/tag/消费组：无新增；全部复用 ORDER_SHIPPED/ORDER_CONFIRMED/ORDER_PAID/REFUND_SUCCESS 既有消费组（cg_aftersale_shipped/confirmed 实际组名以 aftersale listener 为准，不改名）。

**契约变更**：C4、C5、C6、C7、C9；`FlowChangeTypes.INSURANCE_PREMIUM_INCOME=16`（服务内部常量，统一在 B10 卡编号声明处分配）。

**DDL**：D3（order V3）、D4（aftersale V3）、D2 中 t_sett_clearing.insurance_premium_fen（settlement V5）。

**单测用例清单**
- FreightInsuranceCalculator：勾选+有运费→保费=配置值且 ∈[50,500]；不勾选→0；配置越界启动/调用失败。
- OrderCreate：勾选时 payFen=商品应付+保费、Order 两字段落库、支付命令金额=payFen；不勾选路径金额不变（回归全部既有用例）。
- SplitEngine：带保费用例扩展恒等式（保费加在两边）；保费为 0 时与旧结果逐字段一致；佣金/通道费基数不含保费。
- ClearingService 登记：OrderDTO 含保费时清算单 insurance_premium_fen 正确、payAmountFen 含保费、恒等式成立。
- SettleClearingExecutor：多一笔 16 流水入平台账户；全额退款后净额结算时保费仍全额留平台、不退。
- ClearingReverseService/RefundSplitter：含保费订单部分退款，比例分母不含保费、退款总额不含保费。
- AftersaleMq：shipped→confirmed 顺序下窗口 hasFreightInsurance 最终为 1（含乱序/重放：先到 confirmed 后到 shipped 不被覆盖为 0）；事件重放幂等；buildInsurance.premiumFen 取窗口值。
- 理赔既有链路回归：mock 退款成功→72h（可缩短配置）→claimInsurance 到账 2500、bizNo 幂等、每单一次。

**E2E 验收点（建议新增 `FreightInsuranceE2ETest`，复用 World 购买流程）**
1. 建单勾选运费险：订单详情 hasFreightInsurance=1、payFen 比不勾选恰好多保费；支付成功→清算/结算后平台账户有一笔 16 保费收入，商户应收与不勾选场景一致。
2. 发货→确认收货→申请退货退款（商家责任）→退款成功→72h 理赔（测试配置压缩延时）后轮询用户余额到账 2500，t_aftersale_insurance.premium_fen=实缴保费、status=20。
3. 不勾选运费险的退货退款：无理赔单生成（回归现状）。
4. 勾选订单退款金额不包含保费（售后可退余额=商品实付分摊）。

**环境残留声明**
- 保费定价"按距离和重量 0.5-5 元"无数据支撑（商品重量、收货距离均无字段/服务），本期为配置固定保费；真实分级定价依赖商品中心重量字段与物流距离服务，属后续缺口/环境残留。
- 理赔款出资方：现状 claimInsurance 是平台直接给用户加余额（简化为平台承担/保险公司事后结算），真实保险出单、与保险公司保费归集/理赔结算不在本期，属环境/合作残留；保费计平台收入(16)是该简化下的自洽口径，需财务确认科目。

**风险与回归面（对账与幂等）**
- 资金守恒：扩展恒等式必须在 SplitEngineTest/SettleClearingExecutorTest/E2E 三处钉住；保费是用户付款的一部分，最易出错的是 payFen 含保费但清算漏记（长款）或退款误退保费（短款/重复赔付）。
- 幂等：保险开关经两个订单事件传播，GREATEST/仅置位更新防止事件乱序把 1 覆盖为 0；理赔侧既有 markClaimed CAS + bizNo 幂等不变。
- 回归面：全部建单/试算（营销 FIXES_F）、价格快照、支付金额校验、清算 126 测试（FIXES_E）、售后退款/价保/积分退还链路。

---

### 卡 P0-1：shop_refund_shortfall 消费者落地（工单+告警）与 outbox status=2 每日扫表兜底、新缴保证金自动补扣

**现状证据**：topic `shop_refund_shortfall` 与消费组 `cg_sett_shortfall` **已在 create-topics.sh 预建**（:21/:39）；
生产端 `ClearingReverseService.publishShortfall`(:215) 已在穿仓（status=2）时发 outbox（REFUND_SHORTFALL，tag null，bizNo=refundNo，payload `RefundShortfallEvent`）；
**Java 侧无 `cg_sett_shortfall` 消费者、无工单落库；FIXES_E 残留项明确"挂起追讨尚无独立挂起表与自动补扣任务"**。

**改动模块与精确文件清单（shop-settlement-service）**
- `clearing/event/ShortfallWorkOrder.java`（实体，表 t_sett_shortfall_workorder）、`clearing/mapper/ShortfallWorkOrderMapper.java`。
- `clearing/service/ShortfallWorkOrderService.java(+Impl)`：`void onShortfall(RefundShortfallEvent e)`、`void dailyRescan(LocalDate now)`、`void clawbackOnDepositPaid(long merchantId)`。
- `mq/listener/RefundShortfallListener.java`（新增）：`MqListener<RefundShortfallEvent>`，topic `MqTopics.REFUND_SHORTFALL`，**消费组沿用已预建的 `cg_sett_shortfall`**，tag "*"。
- `clearing/support/AlarmNotifier.java`（新增接口）+ `logging/LoggingAlarmNotifier`（默认实现：结构化 error 日志+指标）；真实钉钉/飞书/工单 webhook 实现为环境残留（配置 `shop.settle.alarm.webhook`，缺省仅日志，不阻塞消费）。
- `clearing/job/ShortfallResidualScanJob.java`（新增）：`@Scheduled(cron="0 0/30 2 * * ?")`（每日 2:00 起半小时兜底窗，可评审）+ `@SchedulerLock(name="settle:shortfall-rescan", ...)`。
- `clearing/mapper/ClearingReverseMapper`：新增 `List<...> selectSuspended(@Param("before") LocalDateTime, @Param("limit") int)`（status=2 分页）。
- `deposit/service/DepositService`（B10 缴费到账点）调 `shortfallWorkOrderService.clawbackOnDepositPaid(merchantId)`（同事务或 afterCommit，建议在缴费确认事务内，见步骤 3）。
- `enums/DepositLogTypes.REFUND_COMPENSATE(20)` 正式启用（当前常量存在但无调用）。

**核心实现步骤（topic/tag/消费组/状态迁移）**
1. 消费：`RefundShortfallListener.onMessage` → `ShortfallWorkOrderService.onShortfall`（@Transactional）：①t_sett_mq_consume `tryRecord(eventId, shop_refund_shortfall, cg_sett_shortfall, refundNo)` 幂等（同 RefundSucceededListener 既有范式）②工单 `INSERT ... ON DUPLICATE KEY`/捕获 DuplicateKeyException（uk_event_id），status=10 ③INSERT 获胜方：置 status=20 已告警（CAS 10→20）、alert_count+1，调 AlarmNotifier（webhook/日志失败**不回滚**消费——告警通道故障不能让 MQ 无限重试，catch 后仅记 last_error 列/日志）。
2. 每日扫表兜底（双保险，针对 outbox status=2 挂起或 relay 死信导致事件从未投递）：`ShortfallResidualScanJob` 分页扫 `t_sett_clearing_reverse.status=2`（V4 已有列与 idx_status），逐条按 reverse_no/refund_no 查工单；无工单则用冲正行字段**重建** `RefundShortfallEvent` 走同一 onShortfall 落单逻辑（uk_event_id 用 `RESIDUAL-`+reverseNo 合成，避免与原 eventId 冲突；同一冲正只补一张单——工单表加 reverse_no 普通键并先查后插）；有工单仍 10 态的补告警。
3. 自动补扣：`clawbackOnDepositPaid(merchantId)` 在保证金缴费到账事务内（B10 步骤 1③之后）：查该商户 status=10/20 的工单，按时间顺序在新增/现有保证金余额内补扣（`changeDeposit(-LEAST(...))` + DP 日志 log_type=20 REFUND_COMPENSATE、biz_no=reverseNo、账户流水 31，UK 幂等），累加工单 clawed_back_fen；补满则工单 CAS→30 已追缴结清并出 info；不足则保留缺口工单。补缴动作与缴费入账在同一事务保证原子；无新增 MQ。
4. 人工核销：运营后台暂以 SQL/管理端后续卡处理（status=40 + remark），本期不新增端点（如审计要求可在 AdminController 加 `POST /admin/shortfall/{id}/write-off`，评审定；规划默认不加）。

**契约变更**：无（事件类 `RefundShortfallEvent` 已在 settlement 服务内部，非 shop-api；MqTopics 常量已有）。**DDL**：D2 第 4 节工单表。

**单测用例清单**
- onShortfall：首次落单 10→20 告警一次；eventId 重放（mq_consume 拦截）零副作用；冲正行重复事件不产生多张工单；AlarmNotifier 抛异常时消费仍 ACK、告警失败留痕。
- dailyRescan：构造 status=2 冲正行但无工单→补单；已有工单→不重复；非 status=2 不扫；分页边界（>limit 行）跨天补齐。
- clawback：商户有两张缺口工单，新缴保证金按时间顺序补扣、金额正确、DP 20 流水与账户 31 流水各一笔且 biz_no 幂等；缴足后工单 30；不足保留 10/20；重复触发（缴费事件重放）不重复补扣。
- 端到端幂等：同一条 REFUND_SHORTFALL 经"实时消费 + 次日扫表"两条路径，最终恰一张工单、一次初始告警。

**E2E 验收点（建议扩展 `SettlementE2ETest` 穿仓段或新增 `ShortfallE2ETest`）**
1. 构造待结算/可提现/保证金三档均不足的退款（FIXES_E 已有穿仓造数手段），冲正行 status=2 后：轮询工单生成、status=20、告警日志/钩子被调用一次。
2. 人工删掉工单模拟事件丢失，触发 ShortfallResidualScanJob（测试可直接调 service 方法）后工单补齐。
3. 该商户随后缴足保证金：工单 30、保证金按缺口扣减、DP 20 流水可查。

**环境残留声明**：真实告警/工单通道（钉钉/飞书/ITSM webhook、值班轮转）为环境配置残留；消费者、落库、扫表、补扣全部在 mock 环境可验证。

**风险与回归面（对账与幂等）**
- 幂等三层：mq_consume(event_id) + 工单 UK(event_id)/reverse_no + 补扣流水 UK(biz_no,change_type)；实时与扫表双路径不得重复开单/重复扣。
- 资金：自动补扣只动保证金现金（缴费入账），不得在商户无新增资金时凭空冲账；补扣顺序与三档瀑布口径一致。
- 回归面：ClearingReverseService 三档瀑布/穿仓 status=2（FIXES_E P1-10）既有全部测试；OutboxRelay 快慢车道/挂起语义不得改动。

---

### 卡 P2-4：对账差错处理改逐差异 REQUIRES_NEW 独立事务

**现状证据**：`ReconcileServiceImpl.runReconcile`(:62) 与 `retryPendingDiffs`(:168) 为方法级单一大 `@Transactional`，
循环内 `handleOne`（长款补单 CAS + outbox ORDER_PAID tag "recon"；短款 incrRetry/超 max=5 转 MANUAL(40)；金额不符 adjustAmount）。
任一差异失败（渠道补单 Feign 失败、死锁、毒丸数据）会回滚整批已成功差异，并因大事务长时间持锁放大冲突。

**改动模块与精确文件清单（shop-pay-service）**
- `feature/recon/service/impl/ReconcileServiceImpl.java`：移除 `runReconcile`/`retryPendingDiffs` 方法级 `@Transactional`（类级若有事务注解一并收窄到查询方法）；循环改为调用独立 Bean。
- 新增 `feature/recon/service/ReconDiffHandler.java(+Impl)`：`@Transactional(propagation=REQUIRES_NEW, rollbackFor=Exception.class)` 的 `void handleOne(ReconDiff diff)`（或按差异类型三个内部方法，单入口）；`@Lazy`/独立 Bean 保证 REQUIRES_NEW 经代理生效（参考 AftersaleTimeoutServiceImpl 自注入代理注释里的同类陷阱）。
- `ReconcileResult`/批次汇总：循环收集每条差异成功/失败/跳过结果（record），批次状态更新（t_pay_recon_batch 20→30）放循环后的独立短事务；只要存在未处理差异，批次保持 20（可由 status=10/20 差异计数判定，不因为某条异常就置 30）。
- 异常分类：单条差异内 `DuplicateKeyException`/补单 CAS rows=0 视为已被并发处理（成功跳过）；其余异常记录该 diff 的 retry_count+1/fail 信息（该递增在 REQUIRES_NEW 内已随该条事务提交，不影响兄弟行），循环继续；超 `DEFAULT_MAX_RETRY=5` 仍转 MANUAL(40)（既有逻辑保留）。

**核心实现步骤**：无 MQ/契约改动；保持 outbox ORDER_PAID tag "recon" 与补单 CAS rows>0 才发事件的既有判定不变，只是其事务边界从"整批"缩小为"单条"。

**契约变更**：无。**DDL**：无。

**单测用例清单**
- 3 条差异，第 2 条 handleOne 抛异常：第 1、3 条提交成功（各自 outbox/状态落库），第 2 条 retry_count+1，批次仍 20；下次 retry 只处理剩余。
- 全部成功：批次 30、finish_time 落定。
- 并发补单：两个线程处理同一长款，仅一个 CAS rows>0 发 ORDER_PAID；另一个 REQUIRES_NEW 事务内 DuplicateKey/CAS=0 跳过，无异常向上。
- 死锁模拟（mock 抛 DeadlockLoserDataAccessException）：仅该条回滚重试，兄弟行已提交不回滚。
- 短款连续失败 5 次：第 5 次后 status=40 MANUAL，不阻塞同批其他差异。
- 事务传播断言：ReconDiffHandler 为独立 Bean、方法注解 REQUIRES_NEW（注解反射测试 + 集成验证失败隔离）。

**E2E 验收点**：扩展对账相关 E2E（现有对账单走 classpath mock 账单）：构造含多条差错的账单文件（长款+短款+金额不符混合），触发 T+1 对账后轮询：长款已补单且发出 ORDER_PAID、短款在重试、金额不符调账，批次完成态与各差异状态分别正确；重跑 retry 端点只处理残留。

**环境残留声明**：无。

**风险与回归面（对账与幂等）**
- 批次原子性语义变化是有意为之（从"全成或全回滚"变为"逐笔提交+批次汇总"），与 design 6.5 差错逐笔挂账语义一致；必须保证批次状态由差异计数推导，避免出现"批次 30 但有 10 态差异"。
- 幂等：补单 CAS + 差错 UK(batch_no,channel_code,channel_txn_no) 不变；outbox 至少一次 + 下游消费幂等不变。
- 回归面：ReconcileJob/ReconRetryJob 调度、mock 账单解析、既有 recon 全部测试。

---

### 卡 P2-5：渠道退款 HTTP 移出 DB 事务（PROCESSING 先提交 → 事务外调渠道 → CAS 终态），与 B8 共用收敛漏斗

**现状证据**：`RefundServiceImpl.refund` 方法级 `@Transactional` + 分布式锁 `pay:lock:refund:{orderNo}`；
`executeRefund`(:141) 在同一事务内 markProcessing → 循环 splits `doRefundOne`(:193)（余额走 `userClient.creditBalance`、
渠道走 `channelRouter.refund` 同步 HTTP）→ addRefundedFen CAS → addPaidFen → markSuccess/markRefunded →
:244 outbox REFUND_SUCCESS。渠道慢/超时会拉长行锁与 DB 连接占用，渠道已退款但本地事务回滚会造成资金长短款。

**改动模块与精确文件清单（shop-pay-service）**
- `feature/refund/service/impl/RefundServiceImpl.java`：重构 `executeRefund` 为三段式（可拆 `RefundOrchestrator` 新类承载事务边界，RefundServiceImpl 保持入口/锁/幂等）。
- `feature/refund/support/RefundConvergeService.java`：与 B8 同一收敛漏斗（本卡提供同步触发入口，B8 提供回调/查询入口）。
- `feature/refund/mapper/RefundMapper`：split 级 CAS 补齐——`int markSplitProcessing/refundSplit 按 refundNo 批量 10→20`、`int casSplitSuccess(@Param("refundNo"), @Param("payMethod/channelCode"), @Param("channelRefundNo"))`（20→30 回写渠道退款号）、失败 20→40；若现有 XML 仅有退款单级 CAS，则新增 split 级（t_pay_refund_split 已有 status、channel_refund_no、UK(channel_code,channel_refund_no)）。
- `RefundStateMachine`：本卡把 10→20、20→30/40、40→10(reopen) 的 assertTransition 全部接入（与 B8 同批评审）。

**核心实现步骤（三段式状态机 10 WAIT → 20 PROCESSING → 30 SUCCESS/40 FAIL）**
1. **段一（短事务 TX1）**：锁内重读退款单，`stateMachine.assertTransition(10/40→20)`；`markProcessing` CAS 10→20（retry 路径 reopen 40→10 后同样进入）；建/置 splits 为 20（预生成渠道幂等号 refundNo+"-"+index 落 channel_refund_no 或单独 request_no，保持现有幂等号规则）；**提交事务**。此阶段不调任何外部系统。
2. **段二（事务外）**：按 split 逐个执行外部动作——渠道 split 调 `channelRouter.refund(ChannelRefundRequest)`；余额 split 调 `userClient.creditBalance(bizNo=refundNo)`。两种结果：受理成功(status=20)/受理中(10)/失败(30)；**外部调用幂等**：渠道以 refundNo-index 为幂等号重试安全；余额侧 bizNo 幂等。段二整体无 DB 事务（仅允许日志/透传字段读取）；异常不回滚任何东西（单已在 20 态）。
3. **段三（短事务 TX3，逐 split 或整单 REQUIRES_NEW）**：调 `RefundConvergeService.converge(refundId, SYNC)`——split 级 20→30 CAS 回写渠道退款号；全部成功：退款单 markSuccess(IN(10,20)→30)、addRefundedFen、channelFlowMapper.addPaidFen、markRefunded，**CAS 获胜方**发 outbox REFUND_SUCCESS(tag "refund", bizNo=refundNo)；任一失败/渠道受理中：渠道失败的 split→40、整单 markFail(→40, retry_count+1) 留 fail_reason（运营 retry 端点 reopen 后从 20 态 split 续做，成功的 split 不重复打款）；受理中保持 20，交 B8 的回调/RefundQueryJob 收敛。
4. **竞争仲裁**：段三同步收敛与渠道异步回调/查询可能同时到达——统一在 converge 内用 markSuccess/markSplitSuccess 的 CAS 行数仲裁，只有获胜方写终态+发事件；败者 reload 已终态单直接返回。退款入口的分布式锁保持（保护同订单多笔部分退款的 addRefundedFen 不超付），但锁内不再有 HTTP。
5. 事务边界实现注意：跨 Bean 调用保证代理生效；TX3 用 `@Transactional(propagation=REQUIRES_NEW)`；`refund()` 入口方法本身不再标 `@Transactional`（或仅保留只读/单条 SQL 的短事务）。

**契约变更**：无（退款 DTO/事件不变）。**DDL**：无新业务列（复用 D1 中查询列支撑受理中补偿；若 split 状态迁移所需索引已存在则不加）。

**单测用例清单**
- 三段式成功：TX1 提交后单即 20（mock 渠道在调用中验证此时 DB 已可查到 20）；段二完成后 30 + outbox 一条。
- 渠道调用抛超时异常：单停留 20（不回滚到 10、不置 40），无 outbox；随后 mock 回调成功 → converge 到 30（证明"渠道侧其实成功"可被回调追平，杜绝长短款）。
- 渠道明确失败：split→40、整单 40、retry_count=1；`POST /refunds/{no}/retry` reopen 40→10→20，已成功 split（如余额先行成功）不重复 credit（bizNo 幂等 + split=30 跳过）。
- 同步段三与回调 converge 并发（CountDownLatch 对齐）：仅一个 markSuccess CAS 获胜，REFUND_SUCCESS 仅一条，addRefundedFen 只加一次。
- 多笔部分退款累计不超付（addRefundedFen CAS 既有保护）在无 HTTP 长事务后仍然成立。
- 事务断言：doRefundOne 的渠道/Feign 调用点不在任何活动事务上（TransactionSynchronizationManager.isActualTransactionActive()==false 的断言测试）。
- 混合支付（渠道+余额）：渠道成功、余额失败时整单 40，retry 后渠道不重复退款（渠道幂等号）、余额补做成功后整单 30。

**E2E 验收点（扩展 `PayE2ETest`/`AftersaleE2ETest` 退款段）**
1. mock 渠道退款同步成功路径行为与现状一致（退款 30、售后完成、清算冲正、余额到账）。
2. mock 渠道配置"受理中+需查询"：退款发起后单 20 立即可查（不阻塞到渠道返回），经 RefundQueryJob/回调到 30。
3. 渠道失败后运营 retry 成功，全程只有一条 REFUND_SUCCESS。

**环境残留声明**：无（全部 mock 可验；真实渠道行为随 B8 联调）。

**风险与回归面（对账与幂等）**
- 这是本次资金风险最高的重构：核心不变量是"**外部调用发生在 PROCESSING 落库之后**"与"**终态/outbox 只由 CAS 获胜方写一次**"；评审需逐行确认退款单与 split 两级 CAS、渠道幂等号、余额 bizNo 三重幂等在新边界下仍然闭合。
- 受理中窗口的资金核对依赖 T+1 对账：refund 渠道侧成功而本地长期 20 的单，应能被对账单发现（现有 recon 主要比对支付单；退款对账若现有账单不含退款行，属既有缺口，本卡至少保证 RefundQueryJob 主动收敛，并在残留清单登记"退款账单比对"后续项）。
- 回归面：FIXES_D 的退款 outbox 第 9 参与 8 个发送点、aftersale REQUIRES_NEW 先落退款单（AftersaleRefundStore）与 pay 退款的协作、RefundController retry 端点、全部 refund/aftersale 退款测试。

---

### 卡 P2-1：售后超时延时消息补确定性 eventId + 继承 BaseEvent + 同事务消费流水（AUDIT_MQ_CONSISTENCY §三 P2-1 / RESILIENCE Z9）

**现状证据（shop-aftersale-service，逐处取证）**
- `support/AftersaleTimeoutMessage.java:17-29`：`implements Serializable`，**不继承 `com.shop.common.model.BaseEvent`**，无 eventId / occurredAt / bizNo。
- `mq/listener/AftersaleTimeoutListener.java:35-37`：`onMessage` 直接 `timeoutService.dispatch(message)`，**消费侧不写 `t_aftersale_mq_consume`**；对比同域 `AftersaleMqServiceImpl.onRefundSuccess`(:140) 等三个订单/退款事件消费者均以 `mqConsumeLogMapper.insertIgnore(eventId,...)` 同事务幂等。
- 生产点 1：`AftersaleServiceImpl.sendDelay`(:839-846) 构造消息时不赋 eventId，经 `outboxPublisher.publishDelay(AFTERSALE_TIMEOUT, kind, msg, no, seconds)` 发送；调用点 6 处（:252/:305 审核、:373 收货、:409/:563 换货发货、:502 举证）。
- 生产点 2：`AftersaleMqServiceImpl.onRefundSuccess` :226-235 的 72h 理赔延时消息同样无 eventId。
- 双保险扫描侧直接 new 消息调 dispatch：`job/AftersaleTimeoutJob.java`（audit/receive/exchange_ship 三个 @Scheduled）与 `job/AftersaleClaimJob.java`（insurance/evidence），与 MQ 路径之间**无共享幂等键**，仅靠各业务自身状态 CAS 防重复（如 markClaimed、updateStatus CAS），缺统一的消息级幂等流水；若 dispatch 内某分支漏了 CAS 或先做了非 CAS 副作用（如未来新增的外部调用），MQ 重投与扫表会重复执行。
- `MqConsumeLogMapper.insertIgnore(eventId, topic, bizNo)` 与表 `t_aftersale_mq_consume`(UK event_id) 已就绪；topic `MqTopics.AFTERSALE_TIMEOUT` 与消费组 `cg_aftersale_timeout` 已存在（无 topic/DDL 变更）。

**改动模块与精确文件清单（全部在 shop-aftersale-service）**
- `support/AftersaleTimeoutMessage.java`：改为 `extends BaseEvent`（保留 aftersaleNo/kind/insuranceId 三字段；BaseEvent 已提供 eventId/occurredAt/bizNo）。
- 同文件新增两个确定性工厂，保证**同一业务超时无论重发几次 eventId 恒定**：
  - `static AftersaleTimeoutMessage forAftersale(String aftersaleNo, String kind)`：eventId = `"TO-" + aftersaleNo + "-" + kind`，bizNo=aftersaleNo；
  - `static AftersaleTimeoutMessage forInsurance(Long insuranceId, String aftersaleNo, String kind)`：eventId = `"TO-INS-" + insuranceId + "-" + kind`，bizNo=aftersaleNo（insurance 记录以 id 唯一，UK t_aftersale_insurance.order_no 已保证每单一次）。
  - 各 kind 每业务单本就只应生效一次（二次审核延时、二次换货延时到达时业务 CAS 已是 no-op），故恒定 eventId 与业务语义一致。
- `aftersale/service/impl/AftersaleServiceImpl.java`：`sendDelay`(:839) 改用上述工厂构造消息（insuranceId 非空走 forInsurance，其余 forAftersale），outbox publishDelay 的 bizKey 与事件 bizNo 保持同一单号。
- `mq/service/impl/AftersaleMqServiceImpl.java`：:226-235 理赔延时改用 `AftersaleTimeoutMessage.forInsurance(insurance.getId(), o.getOrderNo(), KIND_INSURANCE)`。
- `aftersale/service/AftersaleTimeoutService.java`：新增 `void dispatchTracked(AftersaleTimeoutMessage msg)`（与现有 `dispatch` 并列）。
- `aftersale/service/impl/AftersaleTimeoutServiceImpl.java`：实现 `dispatchTracked`，`@Transactional(rollbackFor = Exception.class)`：
  1. `mqConsumeLogMapper.insertIgnore(msg.getEventId(), AftersaleDelayTopics.AFTERSALE_TIMEOUT, msg.getBizNo())`；返回 0（MQ 重投或扫表已处理过同一 eventId）直接 return ACK；
  2. 返回 1 才执行现有 `dispatch(msg)` 的 switch 分发（autoApprove/autoConfirmReceive/autoConvertExchangeToRefund/self.claimInsurance/self.closeEvidence）。
  消费流水与业务动作在**同一事务**：业务抛异常（含 R-B6 的 DEPENDENCY_FAIL）时消费流水随事务回滚，broker 可重投恢复；成功则同提交，重投/扫表被 eventId 拦截。
  - 注入 `MqConsumeLogMapper`（该类现未依赖它，构造器加参）。
- `mq/listener/AftersaleTimeoutListener.java:36`：改调 `timeoutService.dispatchTracked(message)`。
- `job/AftersaleTimeoutJob.java`、`job/AftersaleClaimJob.java`：五个扫描循环内构造消息改用同一工厂（forAftersale/forInsurance），并改调 `dispatchTracked`——**MQ 与扫表双路径用同一确定性 eventId 去重**（先执行者写流水，后到者 no-op），这是双保险的正确合并方式。
- 不动 `AftersaleDelayTopics`（tag/kind 不变）、不动 outbox 框架、不动任何业务状态机。

**核心实现步骤（topic/tag/消费组/状态机）**
- topic `shop_aftersale_timeout`（MqTopics.AFTERSALE_TIMEOUT）、tag=kind（audit/receive/exchange_ship/insurance/evidence）、消费组 `cg_aftersale_timeout`，全部沿用不改。
- 链路：`业务状态变更（同事务）→ outbox publishDelay（确定性 eventId）→ relay 投递 → Listener → dispatchTracked 开事务：insertIgnore event_id → 业务 CAS 动作 → 提交`；扫表路径构造相同 eventId 走同一入口。
- 状态机迁移本身不变（售后单/纠纷/理赔单的 CAS 维持第二道防线）；本卡只补第一道"消息级幂等 + 可重试"。

**契约变更**：无（AftersaleTimeoutMessage 是服务内部消息体，不在 shop-api）。**DDL**：无（复用 t_aftersale_mq_consume）。

**单测用例清单**
- 工厂确定性：同 aftersaleNo+kind 两次构造 eventId 相等；insurance 以 insuranceId 派生且不同理赔单不同 ID；bizNo 正确。
- `dispatchTracked`：首次 insertIgnore=1 执行业务；同 eventId 第二次 insertIgnore=0 直接返回、业务方法零调用（mock 验证）。
- 业务抛 BizException（含 R-B6 模拟 creditBalance/refundPoints/returnStock 失败）：事务回滚，mq_consume 无该行（用同库测试验证回滚），下次同 eventId 仍可重新执行成功——证明"失败可重试"。
- 五个 kind 的 dispatch 路由不回归（与现有 dispatch switch 行为逐一分支一致）。
- MQ 路径与 Job 路径交叉：先走 job 写流水，再投递 MQ 消息，listener no-op；反之亦然。
- 既有 claimInsurance/closeEvidence 的 @Transactional 自注入代理（self）在 dispatchTracked 外层事务内行为不变。

**E2E 验收点（扩展 shop-e2e `AftersaleE2ETest`，不新增类）**
1. 审核超时自动同意：延时消息（测试缩短延时或直接调 inner/Job）触发后售后单状态推进；人为重放同一条消息（同 eventId）状态不二次变更、无重复退款/入库副作用。
2. 72h 理赔：退款成功后理赔到账一次；重放 insurance 消息不产生第二笔 INS: 余额入账（既有 bizNo 幂等 + 新 eventId 流水双保险）。
3. 故障注入（与 R-B6 联合验收）：user 服务返回空体/500 时理赔消息消费失败、售后/理赔单不被标记成功；恢复后重投闭合且只入账一次。

**环境残留声明**：无（纯内部消息与既有表，mock 环境全量可验）。

**风险与回归面（对账与幂等）**
- 事件体加字段（继承 BaseEvent）是兼容演进，但 outbox 中历史存量消息无 eventId：relay 重放极旧消息时 eventId 为 null 会导致 insertIgnore(NULL) 被唯一索引去重合并——上线前确认 outbox 无 AFTERSALE_TIMEOUT 积压（本系统为新部署，风险低）；如需稳妥，dispatchTracked 对 null/blank eventId 回退为按 aftersaleNo+kind+insuranceId 现算（工厂补 `deriveId` 静态方法，listener 入口先归一化），本规划要求实现该兜底。
- 回归面：五个超时分支的全部既有用例、FIXES_D 的 72h 理赔 outbox（P1-1）、AftersaleMqServiceImpl 三大订单事件消费、两个扫描 Job 的 ShedLock。
- **与 B11 的边界**：B11 修改 AftersaleMqServiceImpl 的窗口写入与 buildInsurance、本卡改其 :226 延时消息构造与消费基类，两卡同文件不同方法，须同批评审避免合并冲突；`cg_aftersale_timeout` 及 t_aftersale_mq_consume 由本卡独占改动，B11 不得重复加消费记录。

---

### 卡 R-B6：售后三处 Feign 空体/失败语义硬化与失败补偿闭合（AUDIT_RESILIENCE.md B6）

**事实核查结论（先证伪/证实，行号已全部复核）**
审计所列三处错误判空（`r != null && !r.isSuccess()`）在当前代码中**均已修复**，审计行号对应现状如下：
1. `aftersale/service/impl/AftersaleTimeoutServiceImpl.java:87-98`（理赔 userClient.creditBalance）：现为
   `if (r == null || !r.isSuccess()) throw new BizException(DEPENDENCY_FAIL, "运费险理赔到账失败: ...")`，注释明确"null 必须按失败处理，抛错回滚 markClaimed，MQ/60s 扫表重试，user 侧 bizNo=INS:orderNo 幂等"。
2. `mq/service/impl/AftersaleMqServiceImpl.java:161-173`（退积分 userClient.refundPoints）：现为 `if (r == null || !r.isSuccess()) throw new BizException(DEPENDENCY_FAIL, "积分退还失败: ...")`，注释说明消费记录随事务回滚、broker 重试、bizNo 幂等。
3. `aftersale/service/impl/AftersaleServiceImpl.java:857-863`（退货入库 productClient.returnStock）：现为 `if (r == null || !r.isSuccess()) throw new BizException(DEPENDENCY_FAIL, "退货入库失败: ...")`，注释注明 product 侧 (order_no,sku_id,type) UK 幂等、重试安全。
- 退款链路 `payClient.refund` 走私有 `unwrap`（:1003-1011，null/非 success 均抛 DEPENDENCY_FAIL）+ `markFailInNewTx` 失败留痕（:787-797），本就是安全写法；:431 saleable 判空也正确。
- **但根因未除**：全仓 grep 无任何 `ErrorDecoder` 实现（Feign 裸默认解码，对应 RESILIENCE B2）；且三处修复目前**缺少 null/空体/500 三类响应的测试钉防回退**，MQ 消费路径的"异常→回滚→重投→幂等闭合"也无端到端验证。故本卡性质为**硬化+防回退+补偿闭合验证卡**（与 B9 同类：代码主体已修，补测试与残余语义），不含业务逻辑重写。

**改动模块与精确文件清单（shop-aftersale-service，除注明外均为测试/小硬化）**
- 生产代码（仅小改，若复核发现缺口才动；当前预期零逻辑改动）：
  - 三个调用点（creditBalance/refundPoints/returnStock）保持"null 或 !isSuccess 或抛异常"三种失败都不外泄成功的语义；若任一 catch 块存在吞异常（复核确认无），统一改为抛 `BizException(DEPENDENCY_FAIL)`。
  - 不新增本地 ErrorDecoder：Feign 统一失败语义（500/超时/空体解码为可重试异常、错误码透传）属 RESILIENCE B2，**模式与框架实现归 GAP_PLAN_PLATFORM**；本卡只要求在 B2 落地后售后三处无需改业务代码即可受益（当前裸 Feign 的 500/超时以 RuntimeException 形式抛出，同样触发回滚重试，语义已闭合，验证之即可）。
- 测试：
  - `AftersaleTimeoutServiceImplTest`（新增/扩展）：mock userClient.creditBalance 分别返回 null、`Result` 空 data 非 success、HTTP 500（FeignException/模拟抛出）三种，断言抛 DEPENDENCY_FAIL、markClaimed 回滚（保险单仍 INSURANCE_WAIT）、无余额到账；恢复返回成功后再次调用通过且仅入账一次。
  - `AftersaleMqServiceImplTest`：refundPoints 同样三种失败，断言事务回滚（mq_consume event_id 不落库）、售后单不转完成；重投成功路径下积分 bizNo=aftersaleNo 仅退一次。
  - `AftersaleServiceImplTest`：returnStock 三种失败下 confirmReceive 整体失败（售后单不离开商家收货中、退款不发起）；成功重放仅入库一次（依赖 product 侧 UK，可用 mock 验证单次调用）。
  - `unwrap` 用例：null Result 与非 success Result 均抛 DEPENDENCY_FAIL。

**核心实现步骤（失败语义与补偿闭环）**
1. 统一失败口径（文档化 + 代码注释固化）：对资金/库存类 Feign 调用，**null、!success、抛异常三者等价于失败**；消费事务内失败一律抛可重试异常让事务回滚 + broker 重投；同步商家 HTTP 入口（confirmReceive）失败则把 DEPENDENCY_FAIL 返回给操作者，由商家重试或扫描 Job 兜底。
2. 重试幂等基座（已存在，测试钉住）：creditBalance bizNo=`INS:orderNo`、refundPoints bizNo=aftersaleNo、returnStock UK(order_no,sku_id,type)、payClient.refund refundNo 幂等——因此 MQ 任意次数重投安全。
3. 与 P2-5 三段式收敛漏斗的对接（重要契约点）：`startRefund`（AftersaleServiceImpl :787-811）现状已把 pay 侧同步返回的非 FAIL 单置本地 `REFUND_PROCESSING`（:800-806，含 WAIT→PROCESSING 与 FAIL→PROCESSING 两分支 CAS），售后终态只等 REFUND_SUCCESS 事件（onRefundSuccess）。P2-5 后渠道退款常态为"同步受理 20 PROCESSING + 回调/查询收敛"：
   - 新增回归测试钉住：payClient.refund 返回 PROCESSING（status=20）时售后退款单停留退款中、售后单不完成、不报错；后续 REFUND_SUCCESS 事件到达才完成（既有链路不变）；
   - payClient 返回 null/500：`markFailInNewTx` 落 FAIL 后异常上抛（既有），售后动作不被标记成功，可由运营/商家重试；
   - 明确 aftersale **不新增**任何渠道退款查询/回调逻辑——终态收敛唯一入口在 pay 域 RefundConvergeService（B8/P2-5），aftersale 只认事件。
4. 与 P2-1 的协作：dispatchTracked 同事务写消费流水后，R-B6 的失败抛错会连同流水一起回滚，这是延时消息链路（理赔/自动处理）失败可重投闭合的前提；两卡联合验收 E2E 第 3 点。

**契约变更**：无。**DDL**：无。

**单测用例清单**：见上（3 调用点 × 3 失败形态 = 9 个核心用例 + 回滚/重试幂等断言 + PROCESSING 对接 1 个 + unwrap 2 个）。

**E2E 验收点（扩展 `AftersaleE2ETest`，依赖 WireMock/故障注入能力；若 shop-e2e 现无下游故障注入基建，以服务内 mock 的集成测试覆盖并在残留中声明）**
1. 退货退款商家确认收货时 product 服务宕机（500/空体）：售后单停在商家收货中、库存未增、退款未发起；product 恢复后商家重试（或扫描兜底）成功，库存恰回补一次、退款正常发起。
2. 退款成功事件消费中 user 积分服务空体：消息消费失败、售后单不完成、消费流水无残留；user 恢复后重投，积分退一次、售后完成。
3. 72h 理赔时 user 余额服务 500：理赔单保持 10 待理赔、无 INS 入账；恢复后延时消息/60s 扫表收敛，只入账一笔 2500。
4. P2-5 联调点：pay 退款同步返回受理中，售后侧全程不判失败，REFUND_SUCCESS 回调收敛后售后完成。

**环境残留声明**
- 框架级 Feign ErrorDecoder/超时重试统一（RESILIENCE B2）归 GAP_PLAN_PLATFORM；本卡不重复定义，仅在其落地后回归三处调用点。
- shop-e2e 的下游宕机/空体注入若现无基建（ApiClient 仅走网关 HTTP），故障注入 E2E 可降级为服务内 mock 集成测试，待平台测试基建就绪补真·下游宕机场景。

**风险与回归面（对账与幂等）**
- 主要风险是"修复被回退"（三处均为手写判空而非框架保证）：9 个失败形态用例 + 注释固化 + B2 框架根治三层防线。
- 不得把 null 当成功但也不得把"可重试失败"误判为终态毒丸（DEPENDENCY_FAIL 必须在 MqErrorPolicy 的可重试分类内——复核框架策略，若被归为终态 BizException 直接 ACK 丢弃，则必须改用可重试错误码或走扫描兜底；测试覆盖 broker 重投行为）。
- 回归面：售后退款/退货入库/价保/积分退还/运费险理赔全链路、FIXES_D 的 AftersaleRefundStore 与 outbox 8 发送点、与 P2-5/P2-1/B11 同模块合并回归。

---

### 卡 API-F：售后/支付入参契约与越权守卫硬化（AUDIT_API_CONTRACT.md aftersale+pay 侧重要/建议项）

**事实核查结论（行号均已对照当前代码逐项复核，全部有效）**

| 子项 | 当前代码事实 | 判定 |
|---|---|---|
| N-1 明细级联校验 | `AftersaleApplyRequest.java:32-33` items 仅 `@NotEmpty`，**无 `@Valid`**；内部 Item（:44-53）已写 `@NotNull/@Min(1)/@Max(999)` 但不级联不生效；且 Item 无"≤可退/可换数量"的服务端复核说明 | 属实 |
| B1 责任方自报 | `AftersaleServiceImpl.java:189/:211` 直接取 `req.getResponsibilitySide()` 落单，:220 以 `req.getReturnFreightFen()` 算运费补偿；resubmit :298-299 同样接受客户端改判；`RefundCalculator.freightCompensation`(:69-74) 对商家责任分支 `Math.max(0, agreedFreightFen)` **无上限**——买家自报"商家责任+任意运费"即可多得退款 | 属实 |
| B2 举证越权 | `AftersaleServiceImpl.submitEvidence`(:507-526) 只校验介入单存在/举证期，**无任何归属校验**；买家端点 `AftersaleController:80-86` 传 UserContext.getUserId() 但服务端不 requireOwner；商户端点 `MerchantAftersaleController:58-64` 传的是 `UserContext.getUserId()`（用户 ID，非商户身份），服务端也不 requireMerchant；`EvidenceRequest` evidenceType 无 1-3 白名单、content/mediaUrls 无长度/URL 约束（DDL 两列均 VARCHAR(1024)，超长直接 DB 报错） | 属实（IDOR） |
| A2 商户分页 | `MerchantAftersaleController:66-69` 用 `UserContext.getMerchantIdOrNull()`（非商户为 null 不 403）；`pageMerchant`(:674-685) `.eq(merchantId, null)` 恰好查空，暂无数据泄露，但语义不是 403 且依赖 MyBatis 对 null 的处理 | 属实（防御口径） |
| ⑤ 字符串/枚举约束 | `AuditRequest`：rejectReason 无 @Size；`ArbitrateRequest`：result 无 1-3 白名单、awardFen 无 @Min/@Max、remark 无 @Size；`LogisticsRequest`：company/logisticsNo 无 @Size；`ShipRequest`（shop-order-service）：logisticsCompany 无 @NotBlank/@Size、logisticsNo 无 @Size；`AftersaleApplyRequest`：reason 用 @NotNull（空串可过，应 @NotBlank）且无 @Size、type 无取值白名单、returnFreightFen 无 @Min(0)/上限 | 属实 |
| ⑥ 支付侧 | `ChannelNotifyController:33-48` 的 @RequestBody **无 @Valid**，`PayNotifyRequest` 七字段零注解（空体/空金额进入服务，NPE 风险或 500）；`ChannelLimits.check:74-77` 非法终端抛 **IllegalArgumentException**（非 BizException(10001)，全局异常语义不统一）；`ReconcileController` retry 的 limit(:46) 无上限可传巨大值、diffs status(:38) 无 10/20/30/40 白名单、run 的 date(:29) 格式错误走框架 400 而非统一 10001 | 属实 |
| ⑦ shop-api 内部命令 | `AmountCommand`：有 NotNull/NotBlank 但 amountFen 无 @Min、remark 无 @Size；`PointsLockCommand`：points/deductFen 无 @Min；`PointsRefundCommand`：**零注解**（userId/bizNo/points 均无约束） | 属实（归 C11 契约清单） |

**改动模块与精确文件清单**

shop-aftersale-service：
- `aftersale/dto/AftersaleApplyRequest.java`：items 加 `@Valid`；reason 改 `@NotBlank` + `@Size(max=512)`（对齐 DDL 512）；type 加自定义枚举校验或服务端白名单（1-5）；returnFreightFen 加 `@Min(0)`（上限不放在 Bean Validation——见 B1 服务端口径）；Item.qty 保留 @Min(1)/@Max(999) 并在 service 申请/重提处逐行复核 `qty <= 订单行可申请数量`（现有 itemRef 投影 paid/refunded/active_no 已支撑，缺的是负/越界数量的统一前置错误码 10001，禁止落到 RefundCalculator 才抛 IllegalArgumentException:37-39——该 IAE 也要在本卡统一改为 BizException(10001)）。
- `aftersale/service/impl/AftersaleServiceImpl.java`：
  - apply(:189/:211) 与 resubmit(:298)：**忽略客户端 responsibilitySide**，新建售后一律写 `ResponsibilitySide.BUYER`（买家申请默认买家责任）；字段保留在 DTO 仅为兼容，服务端不采信（可加注释"由商家审核/平台仲裁改判"）；责任改判的既有入口（audit 商家同意商家责任、arbitrate 平台仲裁）保持。
  - :220 运费补偿：申请阶段买家自报运费不得入退款金额——`freightCompensation` 仅在**商家审核确认商家责任**（audit/merchantReceive 路径）或仲裁路径计入；申请单可暂存 returnFreightFen 作为商家审核参考（加 `@Min(0)`），但不参与申请时退款计算。
  - `support/RefundCalculator.java:69-74`：`freightCompensation` 加上限——与运费险理赔封顶对齐，配置项 `shop.aftersale.freight-compensation-cap-fen`（默认 2500，即 25 元，与 AftersaleCodes.INSURANCE_CLAIM_CAP_FEN 同口径），`Math.min(cap, Math.max(0, agreed))`；超上限按 cap 截断并记备注（产品若要更高赔付走平台仲裁 awardFen）。
  - submitEvidence(:507)：方法开头按 side 归属校验——side=SIDE_BUYER 时 `requireOwner(requireAftersale(no), operatorId)`；side=SIDE_MERCHANT 时 `requireMerchant(requireAftersale(no), merchantId)`；签名改为 `submitEvidence(String no, int side, Long operatorId, Long merchantId, EvidenceRequest req)`。
- `aftersale/controller/AftersaleController.java:80-86`：买家举证传 `UserContext.getUserId()`（requireUser 语义由 requireOwner 兜底 403）。
- `aftersale/controller/MerchantAftersaleController.java:58-64`：改传 `WebIdentity.requireMerchantId()`（非商户身份直接 403，对齐 MerchantOrderController），side 固定 SIDE_MERCHANT；`:66-69` page 同样改 requireMerchantId()。
- `aftersale/dto/EvidenceRequest.java`：evidenceType 加 `@NotNull @Min(1) @Max(3)`；content `@Size(max=1024)`；mediaUrls `@Size(max=1024)` 并服务端按逗号拆分逐元素 `@org.hibernate.validator.constraints.URL`（scheme 仅允许 http/https——见残留，对象存储域名白名单不在本期）；空 content 且空 mediaUrls 拒绝（至少一项）。
- `aftersale/dto/AuditRequest.java`：rejectReason `@Size(max=512)`，agree=false 必填校验维持服务端。
- `aftersale/dto/ArbitrateRequest.java`：result `@Min(1)@Max(3)`、awardFen `@Min(0)`（金额上限服务端按可退余额校验）、remark `@Size(max=512)`。
- `aftersale/dto/LogisticsRequest.java`：company/logisticsNo 加 `@Size(max=64)`（DDl 64）。

shop-order-service（⑤跨模块一项）：
- `order/dto/ShipRequest.java`：logisticsNo 加 `@Size(max=64)`、logisticsCompany 加 `@NotBlank @Size(max=64)`；控制器确认已有 @Valid（实施时核对发货控制器，无则补）。

shop-pay-service（⑥）：
- `feature/payment/dto/PayNotifyRequest.java`：notifyId/payNo 或 refundNo/status 等按"回调至少可路由"加约束：`status @NotBlank`、`notifyId @NotBlank`、amountFen `@Min(1)`（支付回调；退款回调 DTO 同口径，见 B8 的 RefundNotifyRequest，两卡共用校验风格）；`ChannelNotifyController:34` 参数加 `@Valid`。
  - 注意：渠道回调验签失败当前按业务返回 60002——**Bean Validation 失败（空体/缺字段，无法验签/无法路由）必须返回渠道可识别的规范失败 ACK**（HTTP 200 包体 code=参数错误，或渠道约定的非 success 应答），不得向渠道抛裸 500/NPE（否则渠道疯狂重发刷告警）；全局异常处理器对 `/notify/**` 路径做专门应答映射，测试钉住。
- `channel/ChannelLimits.java:75`：IllegalArgumentException 改 `new BizException(ErrorCode.PARAM_ERROR /*10001*/, ...)`（非法终端是客户端参数错误）。
- `feature/recon/controller/ReconcileController.java`：retry limit 用 `@Max(500)`（配合默认 100）；diffs 入口 status 服务端白名单（null 或 ∈{10,20,30,40}，否则 10001）；run 的 date/channel 非法（含 @DateTimeFormat 解析异常）由全局异常处理统一映射为 BizException(10001)——若框架现有日期解析异常映射不是 10001，在全局异常处理器补 DateTimeParseException/MethodArgumentTypeMismatchException → 10001（该处理器若在 shop-framework，改动列入平台协作点，优先在本控制器用 String 入参+手工 parse(LocalDate.parse, catch DateTimeParseException → 10001) 局部落地，不跨模块改框架）。

shop-api：仅 C11（⑦，三个内部命令注解 + 各 inner 控制器 @Valid），业务代理不自行改。

**核心实现步骤（错误码/状态/越权语义）**
- 统一参数错误码 10001（PARAM_ERROR）：售后申请负数量/越界数量、非法 type/result/evidenceType、非法枚举、非法终端、分页非法参数全部前置返回 10001，禁止 IllegalArgumentException/500 外泄；RefundCalculator:37-39 的 IAE 同步改 10001。
- 越权统一 403（FORBIDDEN）：非买家本人举证、非归属商户举证/分页 → FORBIDDEN，复用既有 requireOwner/requireMerchant（:987-999）。
- B1 资金口径变更后的状态路径：申请（买家责任、无运费补偿）→ 商家 audit 改判商家责任时按审核表单确认赔付运费（≤cap）→ 平台仲裁 result=2/3 时 awardFen 为最终金额（既有仲裁路径不变）。
- 校验顺序保持"先验签/身份 → 再 Bean Validation → 再业务状态"，回调路径除外（见上，参数不全也要规范 ACK）。

**契约变更**：C11（shop-api 三命令）；其余均为服务内 DTO。**DDL**：无（约束全部对齐现有列长度）。

**单测用例清单**
- N-1：items[0].qty=null/0/-1/1000 → 400/10001（验证 @Valid 级联生效，这是本项的关键回归点）；qty 超过订单行可申请数 → 业务 10001；换货/补发负数量不落库（控制器层拒绝，售后单计数为 0）。
- B1：申请体带 responsibilitySide=1(商家)+returnFreightFen=999999，落单责任方仍为买家、退款金额不含运费；商家审核改判商家责任后补偿 ≤2500（cap 配置改 1000 时截断 1000）；仲裁 awardFen 路径金额正确。
- B2：买家 A 给 B 的售后单举证 → 403；商户 X（登录商户身份）给商户 Y 的单举证 → 403；未登录/普通用户调商户举证端点 → 403；evidenceType=4/0、content 超 1024、mediaUrls 含 ftp:// 与非 URL → 10001；合法提交落 side/userId 正确。
- A2：买家 token 调 /merchant/aftersales/page → 403。
- ⑤：AuditRequest rejectReason 513 字符、ArbitrateRequest result=9/awardFen=-1、LogisticsRequest 单号 65 字符、ShipRequest company 空/超长 → 10001。
- ⑥：PayNotifyRequest 空体/缺 status/amount=0 → 规范应答（不 NPE、不 500、不 200 成功）；ChannelLimits 非法终端断言 BizException code=10001；retry limit=100000、diffs status=9、run date="2026-13-40" → 10001。
- C11：三命令的注解存在性（反射测试，放 shop-api 构建期）；user inner 控制器 @Valid 生效（null/0/负拒绝）。

**E2E 验收点（shop-e2e）**
1. IDOR：买家 A 登录对买家 B 的 aftersaleNo 提交举证 → 403；商户 token 跨商户举证 → 403；买家 token 调商户分页 → 403。
2. 买家申请构造 responsibilitySide=1+大额运费，审核前退款金额不含运费（详情/退款单金额断言）。
3. 非法枚举/负数/超长集合：apply qty=-1、arbitrate result=9、回调空体、非法支付终端下单 → 各自规范错误码（400 系列/10001/回调规范 ACK），无 500。
4. 正常路径不回归：合法申请/审核/举证/仲裁/对账触发全程 200。

**环境残留声明**
- 媒体对象存储托管、上传鉴权、CDN 域名白名单归总残留：本期仅做 http(s) scheme + 元素 URL 格式 + 总长度守卫，不实现真正的域名归属校验与上传服务。
- 全局异常处理器若位于 shop-framework（参数/日期异常 → 10001 的统一映射），框架级改动归平台域协作；本卡支付对账日期采用控制器内手工 parse 局部落地，不被框架排期阻塞。

**风险与回归面（对账与幂等）**
- 本卡不改任何资金计算结果（B1 除外：修掉"自报运费"资金漏洞，属正向收敛）——回归重点是合法商家责任/仲裁赔付金额与现状一致、仅封顶与采信时点变化。
- 回调校验加严后，mock 渠道回调测试报文必须带齐字段（更新 ApiClient/测试报文构造），避免 E2E 自造空体被新校验拦截。
- 回归面：售后五类申请/审核/重提、平台介入全链路、价保试算、对账管理端、支付回调（B8 退款回调 DTO 沿用同一校验风格）、shop-api 三命令的全部内部调用方（加注解后历史非法传参会在编译后首次调用暴露，需全量编译验证）。

---

## 5. B9 事实核查专论（审计点 SplitEngine.java:68 vs SettleClearingExecutor.java:95-97）

**核查方法**：逐行核对当前 SplitEngine/SettleClearingExecutor/测试与 design 7.2.1/7.2.2。

**事实链**
1. `SplitEngine.java:77`：`long merchantReceivable = base - commission - channelFee - TECH_FEE_FEN + freight;`
   其中 `TECH_FEE_FEN=50L`（:34）、`base=product-merchantBearDiscount`（:67）。**技服费确实已从商户应收扣减**。
2. 审计引用的 `SplitEngine.java:68` 当前内容是平台佣金计算注释/`commission = multiplyBps(base, rateBps)`（:68-69），**旧行号已失效**；审计成文时的代码与当前代码不一致，说明该问题在审计后已被修复（类注释 20-28 行专门记载了 design 7.2.2 公式文字漏写"- 技术服务费"并声明以 7.2.1 承担方定义为准）。
3. `SplitResult.techFeeFen=50` 恒记为费用项；`SettleClearingExecutor.java:95-97` 将其 `creditAvailable(0, PLATFORM, clearingNo, TECH_FEE_INCOME(13), 50, ...)` 计平台收入——**与分账扣减是同一笔钱的两端**：商户端少结 50、平台端多收 50，资金守恒恒等式
   `用户实付 + 营销补贴 = 商户应收 + 佣金 + 通道费 + 技服费`（含运费项，见类注释）两边相等，不存在"每单 50 分平台虚记收入"。
4. `SplitEngineTest` 五个用例中两个全字段用例（:41-48、:71-77）显式断言该恒等式两个方向，并断言技服费=50、应收=base-commission-channelFee-50+freight。
5. 退款侧 `SettleClearingExecutor` 按净额结算时技服费不退（与 design "技服费商户承担"一致）；`ClearingReverseService` 冲正不回退技服费，口径一致。

**结论**：**B9 在当前代码基线不成立，判定为"已修复"**。处置：不出功能修复卡，仅出 **B9 回归测试卡**（§4 卡 B9）做防回退保护；同时建议修订 design.md 7.2.2 公式文字（补上"- 技术服务费"）——该文档修订不属本规划代码范围，列出供文档负责人处理。

---

## 6. 全局环境残留与跨卡协作声明

1. **真实渠道（微信/支付宝/网银/云闪付/花呗/白条支付与退款、银行卡/支付宝代发）**：本地 kind 环境不可接入；本规划交付的是生产级骨架（SPI/双轨开关/密钥 fail-fast/KMS 接线点/HTTP 签名解析结构/对账单适配点）与 mock 双轨全链路，真实 endpoint、证书、商户平台配置、webhook 注册、沙箱与生产联调、真实账单样本录制均为环境残留。
2. **密钥/KMS**：现状 `ChannelSecretProvider` 支持 `SHOP_PAY_CHANNEL_<CHANNEL>_SECRET` 环境注入与 prod 非默认值校验；打款侧新增 `RemitSecretProvider` 同构；真实 KMS/Vault 取密实现为环境残留，代码只留 `SecretFetcher` 接口。
3. **商户清退收款账户**、**运费险按距离/重量定价所需重量与距离数据**、**保费/理赔与保险公司真实结算科目**、**代发打款对账文件**均为模型/合作方残留，已在对应卡内标注，规划内采用自洽简化（最近提现账户/固定保费/平台承担理赔/主动查询兜底），需产品与财务评审确认。
4. **执行顺序建议**：①契约包 C1-C11 统一评审合入（shop-api/shop-common）→ ②DDL D1-D4 手工执行（无 Flyway，按 information_schema 守卫幂等）→ ③P2-5+B8 同批（共用 RefundConvergeService）→ ④B11 → ⑤B10 → ⑥P0-1（消费者/工单/扫表可先于 B10，自动补扣钩子随 B10）→ ⑦P2-4 可与任意批次并行；B9 测试卡随时可入；⑧P2-1 与 B11 同模块同批评审（消费链路不重复改），R-B6 随 P2-1/P2-5 联合验收；API-F 独立可先行（C11 随契约包①）。
5. 全程禁止把真实渠道测试密钥、webhook 地址提交进仓库；所有新增配置必须有 mock 默认值且 prod profile 下缺失即 fail-fast。
6. **MQ P3-1（settlement/aftersale 消费事务内做 Feign 同步调用的模式治理）的模式卡归 `GAP_PLAN_PLATFORM`**（统一"事务内远程调用"判定标准、ArchUnit/盘点口径与整改模板）；本文件涉及 settlement/aftersale 消费端的具体执行（如 ClearingService 回查 OrderClient、aftersale 退款/积分/理赔三处 Feign 的失败语义）随 R-B6/P2-1/P2-5 相关卡落地，两处文件不得重复定义模式本身，仅在平台模式卡定稿后做一致性回归。
