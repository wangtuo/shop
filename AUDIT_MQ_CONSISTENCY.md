# MQ 事件生产/消费一致性审计报告

- 审计对象：`/Users/bytedance/bits/shop`（Spring Boot 多模块 RocketMQ 电商系统，非 git 仓库）
- 审计范围：全部 MQ 事件的生产端（事务发件箱/直发）、消费端（幂等/同事务）、事件清单、对账补偿任务、库存闭环
- 审计方式：只读代码审计，未修改任何源码；证据均以「文件:行」标注（路径相对仓库根）
- 审计日期：2026-09-17
- 结论速览：生产端 **零直发热点**（业务代码无一处直接调 `MqProducer`，全部走同事务 Outbox）；26 个 Listener 中 25 个有同事务消费记录幂等，1 个（售后超时延时消息）无 eventId/无消费记录但有状态条件更新 + 扫表双保险。发现 **阻断项 1 个、重要项 7 个、一般项 4 个**。

---

## 一、结论摘要

1. **生产端一致性已闭环**：全仓库业务代码 grep `MqProducer` 零命中；约 20 个生产调用点全部经 `OutboxPublisher.publish/publishDelay`，而 `OutboxPublisher.enqueue` 在无活动事务时 fail-fast 抛异常（`shop-framework/src/main/java/com/shop/framework/outbox/OutboxPublisher.java:40-43`），事件与业务状态同提交/回滚，不存在「事务提交失败但消息已发」窗口。唯一直发点是中继本身 `OutboxRelayDispatcher.java:46` 的 `sendRaw`（REQUIRES_NEW 独立事务 + CAS `markSent`）。
2. **消费端幂等整体到位**：26 个 `MqListener` 实现中，25 个在业务写入同一事务、同一数据库内先做消费记录去重（INSERT IGNORE / UK 冲突捕获），再叠加业务层条件更新（`WHERE status=?` CAS）与流水唯一键，共三层防护。唯一例外 `AftersaleTimeoutListener` 消费的消息体不继承 `BaseEvent`（无 eventId），不做消费记录，仅靠状态条件 + 60s 扫表双保险。
3. **中继（relay）机制完整**：快车道 2s 扫描 + 每消息 REQUIRES_NEW + CAS 标记 + 指数退避；慢车道 60s 重放挂起消息（冷却 300s、挂起上限 3 次），超限只打 ERROR 死信日志。
4. **主要缺口集中在「无消费者的事件」与「无对账的域」**：9 个 topic 在全仓库无任何消费者，其中资金类 `REFUND_SHORTFALL`（商家保证金穿仓短路）既无消费者、其 topic 又未在 `deploy/rocketmq/create-topics.sh` 预建（脚本配套的 broker 模板明确要求生产关闭自动建 topic）——**本次审计唯一阻断项**。普通库存域（非秒杀）没有任何对账定时任务，极端双重故障 + 跨 topic 乱序下锁定库存可能悬挂且无自动恢复。
5. 库存锁/确认/释放/回库主链路在重复投递、乱序投递下均有闭环（消费记录 + `t_product_stock_log` UK(order_no, sku_id, type) 且查询不过滤状态 + 状态 CAS），乱序场景的残余风险仅在「取消时同步 Feign 释放失败（异常被 catch 仅日志）且 CANCELLED 先于 CREATED 消费」时成立。

---

## 二、阻断项（P0，发布前必须解决）

### P0-1　`shop_refund_shortfall` topic 未预建且全仓库零消费者——商家穿仓补偿链路断裂

**证据链：**

1. topic 常量与生产：`shop-common/src/main/java/com/shop/common/constant/MqTopics.java` 定义 `REFUND_SHORTFALL = "shop_refund_shortfall"`；生产者为 `shop-settlement-service/src/main/java/com/shop/settlement/clearing/service/ClearingReverseService.java:215`（退款冲正瀑布 pending→available→deposit 全部耗尽后，清算单置 `status=2` 挂起并发出该事件），外层 `onRefundSucceeded` 为 `@Transactional`（同文件 :63）。
2. 无消费者：全仓库 `grep REFUND_SHORTFALL` 仅命中常量、生产者、两个测试类与文档（FIXES_E.md / CODE_REVIEW.md），**没有任何 `MqListener` 订阅该 topic**。平台已向买家垫付退款、却无法从商家回款的穿仓单，只会停在 `status=2`，无任何下游追缴/通知/人工工单动作。
3. topic 未预建：`deploy/rocketmq/create-topics.sh:11-18` 共预建 23 个 topic，清单中没有 `shop_refund_shortfall`。而 `deploy/rocketmq/broker.conf.tpl:9` 明确注释「开发环境自动建 Topic/Group；**生产必须关闭并由运维预建**」（`autoCreateTopicEnable=true` 仅开发态）。生产一旦关闭自动建 topic，该事件在中继 `sendRaw` 阶段必然失败 → `recordFailure` 退避重试 → 挂起 3 次 → 慢车道死信 ERROR（`OutboxRelayJob` / `OutboxRelayDispatcher.java:54`），且无告警渠道。

**影响**：资金安全闭环缺失（穿仓无人接管）+ 生产部署后该事件 100% 投递失败。

**修复建议**：
- 补齐 `create-topics.sh`，加入 `shop_refund_shortfall`（并排查 CI 是否有 topic 清单与常量类的一致性校验，建议加测试枚举 `MqTopics` 与脚本清单 diff）。
- 明确穿仓单的接管方：新增 settlement 域（或运营后台）消费者——订阅后落「穿仓待追缴」记录并触发告警/工单；或确认由外部运营系统订阅，在 ACCEPTANCE 中显式声明该外部依赖。
- 在 `status=2` 挂起单上增加每日扫表兜底任务（即使 MQ 全损也能捞出穿仓单），与「事件 + 扫表」双保险的既有设计保持一致。

---

## 三、重要项（按风险分级）

### P1-1　普通库存域无任何对账任务，双重故障 + 跨 topic 乱序下锁定库存永久悬挂

**证据**：
- `shop-product-service` 全模块无一个 `@Scheduled` 类（秒杀对账任务在 marketing 域，且只告警）。
- 取消释放的同步兜底会吞异常：`shop-order-service/src/main/java/com/shop/order/support/OrderResourceReleaser.java:33` `releaseAll` 三步（积分/营销/库存）分别在 :40、:49、:66 `catch (Exception) { log.error(...) }`，**无重试、无告警、无补偿落库**。
- 乱序消费窗口客观存在：`shop_order_created` 与 `shop_order_cancelled` 是不同 topic，RocketMQ 不保证跨 topic 顺序。取消事件处理器 `StockServiceImpl.handleOrderCancelled`（`shop-product-service/.../stock/service/impl/StockServiceImpl.java:400-418`）在查无 LOCKED 流水时直接跳过（消费记录已写入，不会重放）；而迟到的 `handleOrderCreated`（:364-384）查 UK 无任何状态流水时会执行 `doLock`，此后再无释放事件 → 锁定库存悬挂。

**触发条件（需双重故障，概率低但无自动恢复）**：取消发生时 product 服务不可用（同步 Feign 释放失败被 :66 吞掉），恢复后 CANCELLED 先于 CREATED 被消费。正常时序下不会发生（下单时同步 Feign 预锁、取消时同步释放先于异步事件；且 `selectByUk` 不过滤状态，迟到的 CREATED 看到 RELEASED 流水会幂等跳过——见附录 E）。

**修复建议**：product 域新增库存对账定时任务（ShedLock 保护）：扫描「订单已终态（取消/完成）超过 N 分钟但仍存在 status=0 LOCKED 流水」「无有效订单的 LOCKED 流水」，按订单状态释放或告警；`releaseAll` 三个 catch 块至少补告警指标与失败落库，纳入对账扫描输入。

### P1-2　秒杀对账只告警不修复，Redis/DB 偏差无自动校正

**证据**：`shop-marketing-service` 的 `SeckillReconcileJob`（cron `0 */15 * * * ?`，ShedLock `marketing:seckillReconcile` PT5M/PT30S）逐行比对 Redis 余量与 DB `总量-锁定-已售`，不一致仅 `warn` 日志，无重置/修单动作。秒杀链路在 Redis 异常时虽可由 DB 重建（RELEASE_KEY_MISSING 路径），但对账发现的真实偏差在大促期间只能人工介入。

**修复建议**：对账不一致时按 DB（权威账本）重建 Redis 余量，或至少输出 P1 告警 + 自动暂停该场次售卖，避免继续超卖。

### P2-1　售后超时延时消息无 eventId、无消费记录，幂等仅依赖内存态状态判断

**证据**：
- 消息体 `shop-aftersale-service/src/main/java/com/shop/aftersale/support/AftersaleTimeoutMessage.java` 只含 `aftersaleNo/kind/insuranceId`，**不继承 `BaseEvent`，无 eventId**。
- 消费入口 `AftersaleTimeoutServiceImpl.dispatch`（`.../aftersale/service/impl/AftersaleTimeoutServiceImpl.java:38-52`）直接路由到 `autoApprove/autoConfirmReceive/autoConvertExchangeToRefund/closeEvidence/claimInsurance`，**无 `t_aftersale_mq_consume` 落库**。
- 幂等仅靠各动作内的状态判断：`AftersaleServiceImpl.autoApprove`（`.../aftersale/aftersale/service/impl/AftersaleServiceImpl.java:690-700`，先查状态必须为 WAIT_MERCHANT_AUDIT）、`autoConfirmReceive` :704-710、`autoConvertExchangeToRefund` :714-729；`claimInsurance` 有 CAS `markClaimed`（AftersaleTimeoutServiceImpl.java:64-67）、`closeEvidence` 有状态 + 截止时间双重判断（:84-95）。
- 兜底：`AftersaleTimeoutJob` 每 60s 全量扫表重放，故消息丢失不影响最终一致。

**残余风险**：两条重复延时消息并发消费时，「查状态→推进」非单条 CAS（autoApprove/autoConfirmReceive 的状态判断在内存，推进依赖 `approve/confirmReceive` 内部条件更新），并发窗口取决于内部更新是否全部带 `WHERE status=?`；另外 `unknown kind` 抛 `IllegalArgumentException`（非终态错误）会被反复重试到 16 次进 DLQ。

**修复建议**：让 `AftersaleTimeoutMessage` 继承 `BaseEvent`（或补 UUID 字段），在 dispatch 入口同事务写 `t_aftersale_mq_consume`，与其他 25 个 listener 拉齐；核实 `approve/confirmReceive` 的更新 SQL 全部带状态条件。

### P2-2　各服务对 null eventId 的处理策略不一致

**证据**：
- order 域：合成兜底 id 放行，`shop-order-service/.../mq/service/MqConsumeService.java:23-27`（`eventId = "noid:" + topic + ":" + bizNo`）。
- user 域：直接抛 `IllegalArgumentException`，`shop-user-service/.../mq/service/impl/MqConsumeServiceImpl.java:25-27`——该异常不在 `MqErrorPolicy` 终态码集合内，会无限重试至 16 次进 DLQ。
- product / marketing / settlement / aftersale 域：消费记录表 eventId 列 NOT NULL，无空值分支，插入异常同样走重试→DLQ。
- 当前所有正常事件均由 `BaseEvent` 默认赋 UUID（`shop-common/.../model/BaseEvent.java`），故为理论风险；但它决定了异常消息（人工补发、版本错配）的行为是「可降级」还是「占住重试队列」。

**修复建议**：统一策略到框架层（推荐在 `MqConsumerRegistrar` 反序列化后统一兜底合成 `noid:topic:bizNo`，或统一 ACK 丢弃 + ERROR 告警），消除六套实现。

### P2-3　9 个 topic 全仓库无消费者（含 3 个资金/权益类），部分语义可能被静默吞掉

| topic | 生产者 | 现状评估 |
|---|---|---|
| `shop_refund_shortfall` | ClearingReverseService.java:215 | **已升 P0-1** |
| `shop_deposit_alert` | DepositService.java:196 | 保证金告警无订阅方，需确认是否外部运维系统消费 |
| `shop_withdraw_result` | WithdrawService.java:377 | 提现结果无订阅方，需确认通知服务是否在仓库外 |
| `shop_points_changed` | AccountServiceImpl.java:582 | 积分变更事件无订阅方（BI/成长值？） |
| `shop_clearing_register` / `shop_clearing_settle` | ClearingService.java:268 / SettleClearingExecutor.java:126 | 真实业务由 DB 状态 + DailySettleJob 驱动，事件疑似仅为开放通知；需显式声明 |
| `shop_seckill_event` / `shop_groupbuy_event` | SeckillService.java:228 / GroupbuyService.java:190 | 除 marketing 自身外无下游；GAPS.md G6-4 已记团购成功后订单流转未交叉核对 |
| `shop_stock_warning` | StockServiceImpl.java:309 | 预警已同事务落 `t_stock_warning`（:307 注释），无 MQ 消费者只影响实时通知 |

另：`shop_pay_result` 的 `tag=result`（PaymentServiceImpl.java:490 发出）无订阅者，只有 `tag=check` 被 PayCheckDelayListener 消费；`shop_presale_event` 的 open/register 等 tag 也被唯一消费者 `PresaleEventListener` 显式忽略（只收 CANCEL）。

**修复建议**：逐个 topic 在 ACCEPTANCE 中声明「开放事件/仓库外消费方/废弃」三类去向；仓库外消费需给出系统名与 SLA。无主事件至少在中继侧加「发出 7 天后仍无订阅组」的巡检指标。

### P2-4　支付对账大事务：单方法循环全部差异，一个坏 diff 可能回滚整批

**证据**：`shop-pay-service/.../recon/service/impl/ReconcileServiceImpl.java` 的 `runReconcile`（:62 起）为一个大 `@Transactional`，循环各渠道差异并在循环内 per-diff try/catch；但当被调子服务方法经过 Spring 代理且加入当前事务时，其抛出的运行时异常会把外层事务标记为 rollback-only，**调用方 catch 也无法阻止整批回滚**。同理需检查 `retryPendingDiffs`。

**修复建议**：改为逐差异 `REQUIRES_NEW`（参照 `OutboxRelayDispatcher` 的每消息独立事务模式），或用 `TransactionTemplate` 按 diff 开独立事务；单条失败落 `t_recon_diff` 状态由 ReconRetryJob（30 分钟）接管。

### P2-5　外部渠道退款 HTTP 调用包在数据库事务内

**证据**：`shop-pay-service/.../refund/service/impl/RefundServiceImpl.java` `executeRefund` 在事务内执行渠道退款调用并在 :244 发出 `REFUND_SUCCESS`（调用方 `refund` :67 / `retry` :95 均 `@Transactional`，且整段在 Redsson 锁内）。渠道慢响应会拉长 DB 事务与锁占用；渠道超时后 DB 回滚但渠道侧可能已退款成功，只能靠主动查询与 T+1 对账（ReconcileJob）纠正，期间状态为「渠道已退、本地未落」。

**修复建议**：渠道调用移到事务外（先落 PROCESSING 事务提交 → 调渠道 → 独立事务 CAS 终态），与「退款中」状态机配套；维持现有主动查询 + 对账兜底。

### P3-1　消费事务内嵌 Feign 同步调用，拉长本地事务/行锁

- `shop-settlement-service` PaymentSucceededListener → onPaymentSucceeded 内 Feign `orderClient` 回查订单后才落清算单；下游订单服务慢/不可用会占住 settlement 本地事务，失败抛 DEPENDENCY_FAIL 触发 RocketMQ 重试（16 次后进 DLQ）。
- `AftersaleTimeoutServiceImpl.claimInsurance` :70-77 在事务内 Feign `userClient.creditBalance`，失败整个理赔事务回滚后靠下轮消息/扫表重试。

建议：能靠事件体携带的数据就不回查；必须回查的改为事务外预取（先查后开事务），并为 DLQ 建立人工/自动重放流程（当前框架层 DLQ 后无运维台面）。

### P3-2　慢车道死信与消费端 DLQ 仅有日志，无告警与运维台面

`OutboxRelayJob.requeueSuspended` 对 `suspend_count>=3` 的消息只 `log.error`；消费端重试 16 次进 RocketMQ DLQ 后仓库内无处理流程。建议：死信计数接监控告警（Micrometer + 告警规则），提供 outbox 管理端点（已有 `manualRequeue(id)`，补页面/运维文档）。

### P3-3　Outbox DDL 无迁移工具，靠手工执行

`t_mq_outbox` 建表见 `sql/common/V3__outbox.sql`、`V5`（suspend_count 列）与 settlement 域 V4 副本；全部 pom/配置中无 Flyway/Liquibase。新环境漏跑脚本会导致所有 outbox 写入直接失败（全业务阻断），建议纳入启动自检（启动时校验 outbox 表存在）或引入迁移工具。

---

## 四、做得好的点（已闭环项）

1. **生产端零直发热点**：业务代码对 `MqProducer` 直接引用为 0；`sendAsync` 无业务调用方，延时全部走 outbox 的 `deliver_at`，无 `sendDelay` 业务直调。所有约 20 个 `OutboxPublisher` 调用点均已逐一核实在 `@Transactional` 方法内（详见附录 A）。`OutboxPublisher` 无事务即 fail-fast（OutboxPublisher.java:40-43），从机制上杜绝「事务回滚但消息已发」。
2. **Outbox 中继设计严谨**：`OutboxRelayDispatcher.dispatch` 每条消息 REQUIRES_NEW（:43）、发送成功后 CAS `markSent`（:48）、失败 `recordFailure` 指数退避 `LEAST(POWER(2,retry_count),300)`（:54）；快车道 2s 扫描 status=0 + `deliver_at<=now`；慢车道 60s 重放 status=2（冷却 300s、上限 3 次）。broker 宕机不丢消息，重放不重复（CAS）。
3. **消费幂等三层防护成体系**：同库同事务消费记录（6 个服务各自 `t_*_mq_consume`，UK(event_id) 或 UK(consumer_group,event_id)，INSERT IGNORE / 重复键捕获）+ 业务状态 CAS（如订单 `markPaid ... WHERE status=10`、`markCancelled ... WHERE status=10`、`closeAfterAftersale ... WHERE status IN(60,61,62)`）+ 业务流水 UK（库存 `t_product_stock_log` UK(order_no,sku_id,type)、退款 refundNo UK、营销参与记录 UK）。
4. **支付成功事件只由 CAS 胜出方发出**：`PaymentServiceImpl.completeSuccess` 仅在 `markSuccess` 影响行数 >0 时调 `publishPaid`（:453、:476-490），回调/余额/主动查询/对账四条路径并发也不会重复发 ORDER_PAID；对账补单另用 `tag=recon` 可区分（ReconcileServiceImpl.java:237）。
5. **状态推进事件与影响行数绑定**：ORDER_CANCELLED（OrderPersister.java:81-83）、ORDER_COMPLETED（:124-126）均在 CAS 更新 rows>0 时才登记事件，取消/关闭并发竞争的落败方不发事件。
6. **关键时限全部「MQ 延时消息 + DB 扫表」双保险**：支付超时（15min 延时 + PayTimeoutScanJob 每小时）、自动收货（10d 延时 + 每小时 5 分扫表）、售后窗口（15d + 每小时 15 分扫表，且跳过进行中的售后单）、售后各类超时（延时 + 60s 扫表）、支付主动查询（15min check 延时 + PayTimeoutJob 60s + T+1 对账 + 30min 差异重试）。任何单条消息丢失都有扫表兜底。
7. **消费失败策略区分终态/可恢复**：`MqErrorPolicy` 对参数/鉴权/金额类错误 ACK 丢弃毒消息，对 NOT_FOUND 类（如 ORDER_NOT_FOUND）故意重试——等上游数据/消息乱序补齐，16 次后才 DLQ；反序列化失败 ACK + error，不占重试。
8. **库存主链路对重复/乱序免疫（正常时序）**：重复事件被消费记录拦截；重复 PAID 安全（confirm 只认 LOCKED 流水，CAS 0→1）；CANCELLED 早到但同步释放已先行时，迟到 CREATED 因 `selectByUk` 不过滤状态（ProductStockLogMapper.java:19-23）看到 RELEASED 流水而幂等跳过；售后回库仅在 FINISHED + 退货退款/换货时执行，换货/买家责任回可售、商家质量责任入残次仓（StockServiceImpl.java:423-455）。
9. **退款防孤儿设计**：售后域在调支付域前以 REQUIRES_NEW 独立事务提交退款单并复用同一 refundNo（AftersaleServiceImpl.java:736-739 注释 P1-11），Feign 重试与状态重入不会产生重复退款单；pay 侧按 refundNo 幂等。
10. **幂等表历史坑已修并有注释留档**：product 域 `MqConsumeRecordMapper` 显式传入雪花 id，javadoc 记录了早期漏赋 id 导致隐式 0 冲突、所有插入互斥的事故。

---

## 五、修复建议（按优先级）

| 优先级 | 事项 | 落点 | 建议做法 |
|---|---|---|---|
| P0 | 穿仓链路 | create-topics.sh + ClearingReverseService | 补 topic；新增穿仓消费者/告警/工单；status=2 每日扫表兜底 |
| P1 | 普通库存对账 | shop-product-service（新建 Job） | 扫描终态订单残留 LOCKED 流水，自动释放/告警；releaseAll 失败补告警与落库 |
| P1 | 秒杀对账自愈 | SeckillReconcileJob | 以 DB 为准重建 Redis 或自动停售 |
| P2 | 售后超时消息幂等 | AftersaleTimeoutMessage / dispatch | 继承 BaseEvent + 同事务消费记录 |
| P2 | null eventId 统一 | shop-framework MqConsumerRegistrar | 框架层统一兜底或统一 ACK 告警 |
| P2 | 无主事件去向 | 9 个 topic + PAY_RESULT result tag | ACCEPTANCE 声明外部消费方或标废弃，加无订阅巡检 |
| P2 | 对账大事务 | ReconcileServiceImpl:62 | 按差异 REQUIRES_NEW 独立事务 |
| P2 | 退款 HTTP 出事务 | RefundServiceImpl.executeRefund | PROCESSING 先提交 → 事务外调渠道 → CAS 终态 |
| P3 | 消费事务内 Feign | settlement / aftersale | 事务外预取；建 DLQ 重放台面 |
| P3 | 死信可观测 | OutboxRelayJob / DLQ | 接监控告警 + 运维端点文档化 |
| P3 | Outbox DDL 部署 | sql/common/V3、V5 | 启动自检或引入迁移工具 |

---

## 附录 A：生产端全量调用点审计（任务 1）

### A.1 机制结论

- 业务代码对 `MqProducer` 的直接引用：**0 处**（grep 全仓业务模块零命中）。
- `MqProducer.send`（MqProducer.java:73）：仅框架/测试引用；`sendAsync`（:93）无业务调用方；业务侧无 `sendDelay` 直调（延时统一登记 outbox `deliver_at`）；`sendRaw`（:115）唯一调用方为中继 `OutboxRelayDispatcher.java:46`。
- 全部业务事件经 `OutboxPublisher.publish / publishDelay` 落 `t_mq_outbox`，与业务更新同事务提交；无事务时 fail-fast（OutboxPublisher.java:40-43）。
- 结论：**未发现「@Transactional 方法内直发 MQ」的风险点**；「事务提交失败但消息已发」窗口在当前代码中不存在。

### A.2 生产调用点清单（均已核实外层事务）

| # | 文件:行 | Topic（tag） | 触发方法与事务边界 |
|---|---|---|---|
| 1 | shop-order-service/.../order/service/OrderPersister.java:69 | ORDER_CREATED | persist()，@Transactional REQUIRED :49 |
| 2 | 同文件 :70 | ORDER_PAY_TIMEOUT（deliver_at +15min） | 同上，同事务双事件 |
| 3 | 同文件 :83 | ORDER_CANCELLED | cancel() :79；markCancelled rows>0 才发（:81） |
| 4-5 | 同文件 :97-98 | ORDER_SHIPPED + ORDER_AUTO_CONFIRM（+10d） | ship() :91，rows>0 |
| 6-7 | 同文件 :112-113 | ORDER_CONFIRMED + ORDER_AFTERSALE_WINDOW（+15d） | confirm() :107，rows>0 |
| 8 | 同文件 :126 | ORDER_COMPLETED | close() :122；markClosed rows>0（:124） |
| 9 | shop-pay-service/.../feature/payment/service/impl/PaymentServiceImpl.java:185 | PAY_RESULT(tag=check，+15min) | createPayment @Transactional :79/:95，仅在线渠道 |
| 10-11 | 同文件 :488、:490 | ORDER_PAID(tag=paid) + PAY_RESULT(tag=result) | publishPaid :476；仅 markSuccess CAS 胜出方调用（:453；回调 :439、余额 :172、主动查询 :539 三路径） |
| 12 | shop-pay-service/.../recon/service/impl/ReconcileServiceImpl.java:237 | ORDER_PAID(tag=recon) | handleLong，差异补单 rows>0（注意 P2-4 大事务） |
| 13 | shop-pay-service/.../refund/service/impl/RefundServiceImpl.java:244 | REFUND_SUCCESS | executeRefund；调用方 refund :67 / retry :95 均 @Transactional，Redisson 锁内（注意 P2-5） |
| 14 | shop-aftersale-service/.../support/AftersaleEventPublisher.java:51 | AFTERSALE_CHANGED(tag=newStatus) | AftersaleServiceImpl.change :827（全部入口 :110-:714 为事务入口）及 onRefundSuccess :221 |
| 15 | shop-aftersale-service/.../aftersale/service/impl/AftersaleServiceImpl.java:845 | AFTERSALE_TIMEOUT（延时） | audit 2d :252、:305；receive 3d :373；exchange_ship 5d :409/:563；evidence 3d :502 |
| 16 | shop-aftersale-service/.../mq/service/impl/AftersaleMqServiceImpl.java:230 | AFTERSALE_TIMEOUT（insurance，72h） | REFUND_SUCCESS 消费同事务（含 t_aftersale_insurance 落库） |
| 17 | shop-marketing-service/.../activity/service/SeckillService.java:228 | SECKILL_EVENT(open/deduct/release) | 各 @Transactional 方法 |
| 18 | shop-marketing-service/.../activity/service/GroupbuyService.java:190 | GROUPBUY_EVENT | openOrJoin :50 / release :123 / expireGroups :140 |
| 19 | shop-marketing-service/.../activity/service/PresaleService.java:76 | PRESALE_EVENT（CANCEL 延时） | register :43 |
| 20 | 同文件 :135 | PRESALE_EVENT | timeoutScan :91 / cancelByOrderNo :110 |
| 21 | shop-product-service/.../stock/service/impl/StockServiceImpl.java:309 | STOCK_WARNING | fireStockWarning :289；与 t_stock_warning 落库同事务（:307 注释） |
| 22 | shop-settlement-service/.../clearing/service/ClearingService.java:268 | CLEARING_REGISTER | onPaymentSucceeded @Transactional :70 |
| 23 | shop-settlement-service/.../clearing/executor/SettleClearingExecutor.java:126 | CLEARING_SETTLE | settleOne @Transactional :58，应收<=0 跳过 |
| 24 | shop-settlement-service/.../clearing/service/ClearingReverseService.java:215 | REFUND_SHORTFALL | onRefundSucceeded @Transactional :63；**无消费者 + topic 未预建（P0）** |
| 25 | shop-settlement-service/.../deposit/service/DepositService.java:196 | DEPOSIT_ALERT | 保证金相关事务方法 |
| 26 | shop-settlement-service/.../withdraw/service/WithdrawService.java:377 | WITHDRAW_RESULT | 各状态流转 @Transactional |
| 27 | shop-user-service/.../account/service/impl/AccountServiceImpl.java:582 | POINTS_CHANGED | 调用方 :81-:456 均 @Transactional |

### A.3 中继链路（框架自带，非业务直发）

- 快车道：`OutboxRelayJob` `@Scheduled(fixedDelayString=${shop.outbox.relay-interval-ms:2000})` + `@SchedulerLock(name=mqOutboxRelay, PT2M)`，扫 status=0 且 deliver_at<=now，id 序 LIMIT 100。
- 派发：`OutboxRelayDispatcher.dispatch` REQUIRES_NEW（:43）→ sendRaw（:46）→ markSent CAS（:48）；异常 recordFailure（:54，maxRetry 默认 20，退避 2^n 封顶 300s）。
- 慢车道：requeueSuspended fixedDelay 60s，锁 mqOutboxSuspendRequeue PT2M，冷却 300s、maxSuspend 3，超限 ERROR；另提供 manualRequeue(id)。

---

## 附录 B：消费端全量幂等矩阵（任务 2，共 26 个 Listener）

幂等表均与业务库同库、与业务写入同事务（消费记录与业务状态在一个 `@Transactional` 内提交，回滚则一起回滚）。

| # | Listener（模块） | topic / group / tag | 事件类型 | 消费记录手段 | null eventId | 业务二次幂等 |
|---|---|---|---|---|---|---|
| 1 | order/.../mq/OrderPaidListener.java | shop_order_paid / cg_order_paid / * | PaymentSucceededEvent | MqConsumeService.firstTime INSERT IGNORE t_order_mq_consume | 合成 noid: 兜底（:23-27） | markPaid CAS status=10 |
| 2 | order/.../mq/PayTimeoutListener.java | shop_order_pay_timeout / cg_order_pay_timeout | 延时事件 | 同上 | 兜底 | 关单 CAS status=10→50 |
| 3 | order/.../mq/AutoConfirmListener.java | shop_order_auto_confirm / cg_order_auto_confirm | 延时事件 | 同上 | 兜底 | confirm CAS status=30 |
| 4 | order/.../mq/AftersaleWindowListener.java | shop_order_aftersale_window / cg_order_aftersale_window | 延时事件 | 同上 | 兜底 | 关窗 CAS status=40，进行中售后跳过 |
| 5 | order/.../mq/RefundSucceededListener.java | shop_refund_success / cg_order_refund | RefundSucceededEvent | 同上（PayEventConsumer.onRefunded :64，@Transactional） | 兜底 | closeAfterAftersale CAS status IN(60,61,62)→70 |
| 6 | order/.../mq/AftersaleChangedListener.java | shop_aftersale_changed / cg_order_aftersale | AftersaleChangedEvent | 同上（AftersaleEventConsumer :35 @Transactional） | 兜底 | 状态机条件更新；ORDER_NOT_FOUND 抛错重试 |
| 7 | product/.../stock/mq/OrderCreatedStockListener.java | shop_order_created / cg_product_order_created | OrderCreatedEvent | tryRecord→MqConsumeRecordMapper.tryInsert INSERT IGNORE t_product_mq_consume（显式雪花 id） | 无分支，列 NOT NULL（P2-2） | 库存流水 UK(order_no,sku,type) + lockStock CAS available→locked |
| 8 | product/.../stock/mq/OrderPaidStockListener.java | shop_order_paid / cg_product_order_paid | PaymentSucceededEvent | 同上，StockServiceImpl:389 | 同上 | 仅确认 status=0 流水，CAS 0→1 |
| 9 | product/.../stock/mq/OrderCancelledStockListener.java | shop_order_cancelled / cg_product_order_cancel | OrderCancelledEvent | 同上，:402 | 同上 | 仅释放 status=0 流水，CAS 0→2 |
| 10 | product/.../stock/mq/AftersaleStockListener.java | shop_aftersale_changed / cg_product_aftersale | AftersaleChangedEvent | 同上，:425 | 同上 | markReturned CAS status=1→3 + UK |
| 11 | marketing/.../mq/OrderCreatedListener.java | shop_order_created / cg_marketing_order_created | OrderCreatedEvent | MqConsumeTemplate.runOnce（@Transactional :20，INSERT IGNORE t_marketing_mq_consume） | 无分支（P2-2） | 活动参与/占用 UK |
| 12 | marketing/.../mq/OrderPaidListener.java | shop_order_paid / cg_marketing_order_paid | PaymentSucceededEvent | 同上 | 同上 | 状态 CAS |
| 13 | marketing/.../mq/OrderCancelledListener.java | shop_order_cancelled / cg_marketing_order_cancelled | OrderCancelledEvent | 同上 | 同上 | 释放占用 CAS |
| 14 | marketing/.../mq/PresaleEventListener.java | shop_presale_event / cg_marketing_presale_cancel / 仅 CANCEL | PresaleEvent | 同上 | 同上 | 取消幂等 UK；非 CANCEL tag 直接忽略 |
| 15 | settlement/.../mq/listener/PaymentSucceededListener.java | shop_order_paid / cg_sett_paid | PaymentSucceededEvent | MqConsumeService.tryRecord INSERT IGNORE t_sett_mq_consume（自增 id） | 无分支（P2-2） | 清算单 stage=10 UK(order_no)；事务内 Feign 回查（P3-1） |
| 16 | settlement/.../mq/listener/OrderConfirmedListener.java | shop_order_confirmed / cg_sett_confirmed | OrderConfirmedEvent | 同上 | 同上 | stage CAS 10→20；清算单缺失抛 DEPENDENCY_FAIL 重试 |
| 17 | settlement/.../mq/listener/OrderCompletedListener.java | shop_order_completed / cg_sett_completed | OrderCompletedEvent | 同上 | 同上 | B 端账期提前，条件更新 |
| 18 | settlement/.../mq/listener/RefundSucceededListener.java | shop_refund_success / cg_sett_refund | RefundSucceededEvent | 同上 | 同上 | 冲正瀑布 + refundNo UK；穿仓 status=2 发 P0 事件 |
| 19 | aftersale/.../mq/listener/OrderShippedListener.java | shop_order_shipped / cg_aftersale_shipped | OrderShippedEvent | mqConsumeLogMapper.insertIgnore t_aftersale_mq_consume（自增 id），AftersaleMqServiceImpl:74 | 无分支（P2-2） | 状态条件 |
| 20 | aftersale/.../mq/listener/OrderConfirmedListener.java | shop_order_confirmed / cg_aftersale_confirmed | OrderConfirmedEvent | 同上，:106 | 同上 | 开售后窗口幂等 |
| 21 | aftersale/.../mq/listener/RefundSucceededListener.java | shop_refund_success / cg_aftersale_refund_success | RefundSucceededEvent | 同上，:140 | 同上 | onRefundSuccess 条件更新 + 运费险 72h 延时事件同事务 |
| 22 | aftersale/.../mq/listener/AftersaleTimeoutListener.java | shop_aftersale_timeout / cg_aftersale_timeout | **AftersaleTimeoutMessage（不继承 BaseEvent，无 eventId）** | **无消费记录**（P2-1） | 不适用 | 仅业务状态条件（AftersaleServiceImpl:692/706/716；claim CAS、evidence 状态+时间双判）+ 60s 扫表双保险 |
| 23 | pay/.../mq/listener/PayCheckDelayListener.java | shop_pay_result / cg_pay_timeout_query / tag=check | PaymentSucceededEvent(查询指令) | MqConsumeSupport.firstConsume INSERT IGNORE t_pay_mq_consume，UK(consumer_group,event_id)，@Transactional :46 | 无分支（P2-2） | activeQuery 渠道查询，状态 CAS |
| 24 | user/.../mq/listener/OrderPaidListener.java | shop_order_paid / cg_user_order_paid | PaymentSucceededEvent | MqConsumeServiceImpl.beginConsume（selectCount + insert 捕获 DuplicateKeyException） | **抛 IllegalArgumentException→重试至 DLQ**（:25-27，P2-2） | 积分流水 UK |
| 25 | user/.../mq/listener/OrderCancelledListener.java | shop_order_cancelled / cg_user_order_cancel | OrderCancelledEvent | 同上 | 同上 | 冻结积分回退 CAS |
| 26 | user/.../mq/listener/RefundSucceededListener.java | shop_refund_success / cg_user_refund | RefundSucceededEvent | 同上 | 同上 | 退回积分 CAS |

同事务原子性抽查：product 域 tryRecord 与库存 CAS 在同一 `@Transactional` 方法（StockServiceImpl.java:364-384 等）；marketing 域由 MqConsumeTemplate 统一包裹（:20 @Transactional）；settlement/aftersale/user 均在标注 @Transactional 的消费服务方法内先写消费记录再写业务。消费记录回滚会导致消息重试，而业务层 CAS/UK 保证重试不产生副作用——原子性成立。

---

## 附录 C：事件类型全量清单（任务 3，谁发→谁消费→做什么→失败补偿）

| 事件 topic（tag） | 生产者 | 消费者（group） | 消费动作 | 失败补偿/兜底 |
|---|---|---|---|---|
| shop_order_created | OrderPersister.java:69 | product: cg_product_order_created（锁库存）；marketing: cg_marketing_order_created（核销活动占用/预售） | TCC 锁定、营销资源冻结 | 下单时同步 Feign 已预锁（MQ 为第二道，UK 幂等）；取消时同步释放；**无库存对账（P1-1）** |
| shop_order_paid（paid/recon） | PaymentServiceImpl.java:488；ReconcileServiceImpl.java:237(recon) | order(cg_order_paid)、product(cg_product_order_paid)、marketing(cg_marketing_order_paid)、settlement(cg_sett_paid)、user(cg_user_order_paid) | 订单 status→20、库存 LOCKED→DEDUCTED、营销核销、落清算单 stage=10、积分发放入账 | 各域 CAS/UK 幂等；支付侧 15min 主动查询 + T+1 对账 + recon tag 补单；settlement 缺单抛错重试 |
| shop_order_cancelled | OrderPersister.java:83 | product(cg_product_order_cancel)、marketing(cg_marketing_order_cancelled)、user(cg_user_order_cancel) | 释放锁定库存/营销资源、回退冻结积分 | 取消时 OrderResourceReleaser 同步 Feign 先释放（失败仅日志 P1-1）；MQ 第二道 |
| shop_order_shipped | OrderPersister.java:97 | aftersale(cg_aftersale_shipped) | 初始化售后相关数据 | 消费记录幂等；状态条件 |
| shop_order_confirmed | OrderPersister.java:112 | settlement(cg_sett_confirmed)、aftersale(cg_aftersale_confirmed) | 清算 stage 10→20、开 15d 售后窗口 | 清算单缺失 DEPENDENCY_FAIL 重试 |
| shop_order_completed | OrderPersister.java:126 | settlement(cg_sett_completed) | B 端账期提前 | 条件更新幂等 |
| shop_order_pay_timeout（延时 15min） | OrderPersister.java:70 | order(cg_order_pay_timeout) | 未支付则超时关单（CAS 10→50）+ 发 ORDER_CANCELLED | PayTimeoutScanJob `0 * * * * ?` 每小时扫 status=10 + expire_time<now 双保险；P1-7 支付域交叉核对 |
| shop_order_auto_confirm（延时 10d） | OrderPersister.java:98 | order(cg_order_auto_confirm) | 自动收货 status 30→40 | AutoConfirmScanJob `0 5 * * * ?` 扫表 |
| shop_order_aftersale_window（延时 15d） | OrderPersister.java:113 | order(cg_order_aftersale_window) | 关售后窗口/完成订单（跳过进行中售后） | AftersaleWindowScanJob `0 15 * * * ?` |
| shop_aftersale_timeout（多 kind 延时） | AftersaleServiceImpl.java:845；AftersaleMqServiceImpl.java:230 | aftersale(cg_aftersale_timeout) | 自动同意/自动确认收货/换货转退款/举证关闭/运费险理赔 | AftersaleTimeoutJob 60s 三扫 + AftersaleClaimJob 60s 双保险；无消费记录（P2-1） |
| shop_pay_result（check，延时 15min） | PaymentServiceImpl.java:185 | pay(cg_pay_timeout_query) | activeQuery 渠道，成功则 CAS 完成并发 paid/result | PayTimeoutJob 60s + T+1 ReconcileJob + ReconRetryJob 30min |
| shop_pay_result（result） | PaymentServiceImpl.java:490 | **无消费者（P2-3）** | — | 主动查询/对账已覆盖语义 |
| shop_refund_success | RefundServiceImpl.java:244 | order(cg_order_refund)、settlement(cg_sett_refund)、aftersale(cg_aftersale_refund_success)、user(cg_user_refund) | 售后后订单收口、清算冲正瀑布、售后单推进、积分回退 | refundNo UK 幂等；重试 Job；冲正穿仓→REFUND_SHORTFALL（P0） |
| shop_aftersale_changed（tag=状态） | AftersaleEventPublisher.java:51 | order(cg_order_aftersale)、product(cg_product_aftersale) | 订单售后态收口；FINISHED+退货退款/换货时库存回库（可售/残次分账） | 状态机 + UK；售后扫表兜底 |
| shop_stock_warning | StockServiceImpl.java:309 | **无消费者** | — | 已同事务落 t_stock_warning；阈值管理在 DB |
| shop_clearing_register | ClearingService.java:268 | **无消费者（P2-3）** | — | 真实落库与消费者同事务完成 |
| shop_clearing_settle | SettleClearingExecutor.java:126 | **无消费者（P2-3）** | — | DailySettleJob `0 30 2 * * ?` 驱动结算 |
| shop_clearing_reverse | （MqTopics 常量） | 见 REFUND_SUCCESS 链路（冲正由同步消费触发） | 退款冲正 | — |
| shop_seckill_event | SeckillService.java:228 | **无外部消费者** | — | Redis 重建路径 + SeckillReconcileJob 15min 巡检（P1-2） |
| shop_groupbuy_event | GroupbuyService.java:190 | **无外部消费者**（GAPS G6-4） | — | GroupbuyExpireJob `0 */5 * * * ?` |
| shop_presale_event | PresaleService.java:76/135 | marketing 自身 cg_marketing_presale_cancel（仅 CANCEL tag） | 超时取消预售单、回补库存 | PresaleFinalJob `0 */10 * * * ?` |
| shop_points_changed | AccountServiceImpl.java:582 | **无消费者（P2-3）** | — | 积分流水 UK 自洽 |
| shop_withdraw_result | WithdrawService.java:377 | **无消费者（P2-3）** | — | WithdrawRemitJob `0 0 9-22 * * ?` 等状态机驱动 |
| shop_deposit_alert | DepositService.java:196 | **无消费者（P2-3）** | — | DepositRefundJob `0 0 3 * * ?` |
| shop_refund_shortfall | ClearingReverseService.java:215 | **无消费者 + topic 未预建（P0-1）** | — | 无兜底，必须补建 |

---

## 附录 D：对账/补偿任务清单（任务 4）

| 任务（模块） | 调度 | ShedLock（mostFor/leastFor） | 扫描范围与单批 | 退出/终止条件 |
|---|---|---|---|---|
| OutboxRelayJob 快车道（framework） | fixedDelay 2000ms | mqOutboxRelay / PT2M | status=0 且 deliver_at<=now，id 序 LIMIT 100 | 发送成功 markSent；失败退避挂起 |
| OutboxRelayJob 慢车道 | fixedDelay 60000ms | mqOutboxSuspendRequeue / PT2M | status=2，next_retry 冷却 300s | suspend_count≥3 → ERROR 死信（P3-2） |
| PayTimeoutScanJob（order） | cron `0 * * * * ?` | orderPayTimeoutScan / PT5M / PT1M | status=10 且 expire_time<now，LIMIT 200 | CAS 成功者发 CANCELLED；逐条 try/catch 日志继续 |
| AutoConfirmScanJob（order） | cron `0 5 * * * ?` | 同名锁 | status=30 超 10d | CAS 成功推进 |
| AftersaleWindowScanJob（order） | cron `0 15 * * * ?` | 同名锁 | status=40 超 15d | 存在进行中售后（状态 1/2/4/5）则跳过 |
| PayTimeoutJob（pay） | fixedDelay 60s，init 30s | payTimeoutScan / PT5M / PT1M | 超时支付单 | 先 activeQuery 渠道；确认未付→markClosed + 余额释放 |
| ReconcileJob（pay） | cron `0 17 2 * * ?`（T+1 02:17） | payReconDaily / PT30M / PT5M | 按渠道拉账与本地逐笔比对 | 差异落 t_recon_diff（大事务 P2-4） |
| ReconRetryJob（pay） | fixedDelay 30min，init 5min | payReconRetry / PT1M | 短差异重新查询渠道 | 达上限转人工状态 |
| SeckillReconcileJob（marketing） | cron `0 */15 * * * ?` | marketing:seckillReconcile / PT5M / PT30S | 全部 SeckillSku：Redis 余量 vs DB 总量-锁定-已售 | 不一致仅 warn（P1-2） |
| GroupbuyExpireJob | cron `0 */5 * * * ?` | 有锁 | 到期团 | 失败团释放资源并发 GROUPBUY_EVENT |
| PresaleFinalJob / CouponExpireJob | cron `0 */10 * * * ?` | 有锁 | 到期预售/优惠券 | 状态 CAS |
| AftersaleTimeoutJob（aftersale） | fixedDelay 60s 三扫 | 有锁 | 审核/收货/换货发货超时单 | 状态不匹配即跳过（条件更新） |
| AftersaleClaimJob（aftersale） | fixedDelay 60s | 有锁 | 运费险待理赔 + 举证到期 | markClaimed CAS；截止时间未到跳过 |
| DailySettleJob（settlement） | cron `0 30 2 * * ?` | settle:daily-settle / PT2H | stage=20 清算单 | 应收<=0 跳过；发 CLEARING_SETTLE |
| AutoWithdrawJob / WithdrawAuditJob / WithdrawRemitJob | `0 0 8 * * ?` / `0 */10 * * * ?` / `0 0 9-22 * * ?` | 有锁 | 提现单状态机 | CAS 推进，发 WITHDRAW_RESULT |
| DepositRefundJob（settlement） | cron `0 0 3 * * ?` | 有锁 | 保证金退还 | 状态条件 |
| PointsExpireJob / GrowthDiscountJob（user） | `0 30 3 * * ?` / `0 0 2 31 12 ?` | 有锁 | 过期积分/年成长折扣 | 批量条件更新 |
| **product 域** | **无任何 @Scheduled 类** | — | — | 普通库存无对账（P1-1） |

所有任务均为多实例安全（ShedLock 同名锁单点执行），逐项 try/catch 不中断整批；共性弱点是失败仅日志、缺监控指标（P3-2）。

---

## 附录 E：库存最终一致性验证（任务 5）

### E.1 正常闭环

- **锁定 lock**：下单主链路在订单落库前先同步 Feign 调 `StockServiceImpl.lockStock`（:85，Redisson `lock:stock:{skuId}` + `skuMapper.lockStock` available→locked CAS + 写 LOCKED 流水）；订单创建同事务再发 ORDER_CREATED，消费端 `handleOrderCreated`（:364）按 UK 幂等，是第二道锁。
- **确认 confirm**：ORDER_PAID 消费（:387）事件体不含 items，按本域 `selectLockedByOrder` 逐笔 LOCKED→DEDUCTED（CAS 0→1）。
- **释放 release**：取消时 (a) `OrderResourceReleaser.releaseAll` 同步 Feign 先释放（:33，异常仅日志）；(b) ORDER_CANCELLED 消费 `handleOrderCancelled`（:400）再按 LOCKED 流水释放（CAS 0→2），无流水视为已确认/已释放跳过。
- **回库 return**：AFTERSALE_CHANGED 仅在 FINISHED + RETURN_REFUND/EXCHANGE 时触发 `handleAftersaleChanged`（:423）→ DEDUCTED→RETURNED（CAS 1→3）；换货与买家责任回可售、商家质量责任入残次仓（:435-447）。
- 秒杀库存以 Redis 为准，RELEASE_KEY_MISSING 时可从 DB 重建；15min 对账巡检（仅告警，P1-2）。

### E.2 重复事件

- 重复 CREATED/PAID/CANCELLED/AFTERSALE：第一层消费记录（eventId UK）直接 ACK；即使绕过（如历史消费记录丢失），第二层库存流水 UK(order_no, sku_id, type)（`selectByUk` 不过滤状态，ProductStockLogMapper.java:19-23）与 `updateStatusIf ... WHERE status=?`（:40-44）使 lock/confirm/release/return 全部幂等。
- 重复 PAID：confirm 只处理 status=0 流水，DEDUCTED/RELEASED 均不再动。
- 支付侧四条成功路径（回调、余额、主动查询、对账补单）并发时，`markSuccess` CAS 只有一个胜出方发 ORDER_PAID（PaymentServiceImpl.java:453-490）。

### E.3 乱序事件

- **CANCELLED 早于 CREATED（且同步释放已成功）**：取消同步释放先把 R1 置 RELEASED；CANCELLED 消费查无 LOCKED 跳过；迟到 CREATED 的 `selectByUk` 命中 RELEASED 流水（UK 不带状态）→ 幂等跳过，**不产生悬挂**。闭环。
- **CANCELLED 早于 CREATED（同步释放也失败被吞 + 两 topic 跨 topic 乱序投递）**：CANCELLED 先消费：无 LOCKED 流水跳过且消费记录已落；CREATED 后消费：无任何 UK 流水 → doLock 成功，此后无释放事件 → **锁定库存永久悬挂，product 域无扫表任务可捞**（P1-1）。触发需 product 服务在取消窗口不可用 + 恢复后跨 topic 乱序，属双重故障；`shop_order_created` 与 `shop_order_cancelled` 为不同 topic，broker 不保证先后。
- **PAID 早于 CREATED**：PAID 消费 `selectLockedByOrder` 为空即无事可做（消息已 ack）；随后 CREATED 锁定后无人 confirm——但下单同步预锁使 R1 在 CREATED MQ 消费前通常已存在；真正极端时序（product 长时间不可用导致 CREATED 积压，而 PAID 已投递）下同样依赖库存对账兜底，当前缺失（并入 P1-1 修复范围，对账任务应同时扫描「已支付订单的 LOCKED 流水」执行 confirm）。
- **重复 PAID / 售后事件乱序**：均由状态 CAS 与 FINISHED 状态门槛收敛。

### E.4 结论

正常时序与单故障下库存最终一致性成立；残余缺口统一指向「product 域缺少以订单状态为权威源的库存对账任务」，修复 P1-1 后 E.3 两个极端乱序场景均可自动收敛。

---

## 六、需在 ACCEPTANCE 中声明的残留

1. **P0-1 未修复前不具备上线条件**：`shop_refund_shortfall` 无消费者、topic 未预建，生产关闭 autoCreateTopic 后必现投递失败；商家穿仓单无接管方。
2. product 域无库存对账任务，双重故障 + 跨 topic 乱序下的锁定库存悬挂（P1-1）与「PAID 先到、CREATED 积压」场景需人工或修 P1-1 后消除。
3. 9 个无消费者 topic（含 deposit_alert / withdraw_result / points_changed 三个业务通知类）需逐个声明仓库外消费方（系统名、SLA）或标记为开放事件/废弃；`PAY_RESULT tag=result`、PRESALE_EVENT 非 CANCEL tag 同此。
4. `AftersaleTimeoutMessage` 无 eventId/无消费记录（P2-1），上线前需确认 `approve/confirmReceive` 内部更新全部为条件 CAS，并接受「靠扫表保证最终一致、不保证消息层幂等」的语义。
5. 六服务 null eventId 行为不一致（P2-2），异常补发消息在 5 个服务会走重试→DLQ；当前无 DLQ 运维台面与告警（P3-2）。
6. 退款渠道 HTTP 在 DB 事务内（P2-5）、支付对账单大事务（P2-4）、消费事务内 Feign 调用（P3-1）为已知架构折中，依赖渠道查询 + T+1 对账兜底；极端窗口内可能出现短时状态不一致。
7. 秒杀对账仅告警不自愈（P1-2）；大促值班需覆盖 Redis/DB 偏差处置流程。
8. `t_mq_outbox` 等 DDL 无迁移工具（P3-3），新环境部署手册必须包含 sql/common V3、V5 及各域副本的执行步骤；生产 broker 必须按 broker.conf.tpl 注释关闭 autoCreateTopicEnable 并以 create-topics.sh 预建（修复 P0-1 后为 24 个 topic）。
