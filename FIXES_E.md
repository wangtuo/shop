# 结算服务资金安全整改（P1-1 / P1-4 / P1-10 / P1-12）修复报告

- 模块：`shop-settlement-service`（Spring Boot 3.2.5 / Java 17 / MyBatis-Plus / RocketMQ / MySQL 8）
- 前提：agent B 的安全整改（DataCipher 加密落库、M-3 幂等键、商户鉴权）保持不破坏；`DataCipherTest`(10)、`IdempotencyKeyIsolationTest`(4)、`MerchantServiceTest`(7)、`MerchantWithdrawControllerTest`(2) 等全部仍绿。
- 边界：未改动 shop-framework / shop-gateway / 其他服务 / application.yml / deploy。`shop-common` 仅**新增**一个 topic 常量（向后兼容），并已 `mvn -o -q -pl shop-common install -DskipTests`。
- 测试结果：`mvn -o -q -pl shop-settlement-service test` 全绿，**18 个测试类、126 个用例，Failures=0 Errors=0 Skipped=0**（基线 113，净增 13）。
- DDL 已应用到本地 `shop_settlement`（`docker exec -i shop-mysql mysql -uroot -proot shop_settlement`），并经真实 MySQL 集成测试验证。

---

## 一、P1-1 事务内消息全部改为 transactional outbox

模块内 `MqProducer` 已**彻底移除**（`grep -rn "MqProducer|mqProducer|sendAsync" src/main/java` 零命中）。凡与本地数据变更同事务的消息，一律改为 `OutboxPublisher.publish`（框架实现：事务未激活时 fail-fast 抛 `IllegalStateException`，事件行与业务数据同提交/回滚，`OutboxRelayJob` 每 2s 至少一次投递，消费端按 bizNo 幂等）。

| 位置 | 事件 | bizKey |
| --- | --- | --- |
| `shop-settlement-service/src/main/java/com/shop/settlement/clearing/service/ClearingService.java:268` | CLEARING_REGISTER（清算登记同事务） | orderNo |
| `shop-settlement-service/src/main/java/com/shop/settlement/clearing/service/ClearingReverseService.java:215` | REFUND_SHORTFALL（缺口告警，与冲正扣款/明细同事务） | refundNo |
| `shop-settlement-service/src/main/java/com/shop/settlement/deposit/service/DepositService.java:196` | DEPOSIT_ALERT（保证金扣赔跌破 50%，与扣账/日志同事务） | merchantId |
| `shop-settlement-service/src/main/java/com/shop/settlement/statement/service/SettleClearingExecutor.java:126` | CLEARING_SETTLE（单笔结算，粒度从"每页一条"改为**每清算单一条**） | clearingNo |
| `shop-settlement-service/src/main/java/com/shop/settlement/withdraw/service/WithdrawService.java:70,375` | WITHDRAW_RESULT（remitBatch/markFailed/refuse 事务内，代码处有 P1-1 注释） | withdrawNo |

- 监听器 `mq/listener/PaymentSucceededListener.java`、`RefundSucceededListener.java` 只做委托，不含本地直发；`AccountService`、`StatementSettleService` 本身不发 MQ。
- 本模块没有"纯提交后通知"残留的直发点（原直发均处于业务事务内）；提交后投递统一由 outbox relay 承担。
- 同事务回滚的真实验证：`SettlementRepairRealDbTest.outboxAndBalance_rollbackTogether`——冲正末段阶段 CAS 失败时，outbox 行、冲正明细、账户流水、消费登记四者全部回滚（无幽灵事件、无半截账，Broker 可安全重试）。

## 二、P1-4 日终批自调用失效 + 单笔失败滚整批

旧实现 `StatementSettleService` 内部自调用 `settlePage()`/同类 `@Transactional` 方法，Spring AOP 代理被绕过，且整批一个事务，单条失败全批回滚。

修复：
1. 新增独立 Spring Bean `shop-settlement-service/src/main/java/com/shop/settlement/statement/service/SettleClearingExecutor.java`：
   - `@Transactional public SettleItem settleOne(SettClearing, LocalDate)`（第 58-111 行）——单笔清算单的阶段 CAS(20→30)、六笔记账（商户待结算入款+转可提现、平台佣金/技服费/通道费、营销出资）、结算单 get-or-create/累计、CLEARING_SETTLE outbox 全部在**该笔独立事务**内。
2. `shop-settlement-service/src/main/java/com/shop/settlement/statement/service/StatementSettleService.java`：
   - `settlePage`（第 93 行起）**不再开事务**，扫描只读后逐单调用执行器（注入的是代理 Bean，不再自调用）；
   - 单笔异常 try/catch 隔离（第 113-126 行），ERROR 日志带 clearingNo/orderNo/merchantId/reason，失败项收入 `SettlePageResult.failures`（`FailedItem`，第 82 行；`failedCount` 第 56 行）可追踪；
   - 失败单阶段保持 20，下轮 job 自动重跑自愈；游标照常推进，不中断整批。
3. `job/DailySettleJob.java` 无事务注解（`@Scheduled` + `@SchedulerLock`），仅调 `runDailySettle`（第 143 行），事务边界下沉到每笔清算单。

测试：
- Mockito 编排单测 `StatementSettleServiceTest`（5 个）：按结算单汇总、跳过不计、单笔失败隔离+失败明细+游标推进、500/页翻页、整页全失败仍翻页。
- 真实事务集成测试 `SettlementRepairRealDbTest.dailyBatch_failureIsolation`：同一批内商户 9999 不存在的单据抛错，断言该单停留 stage=20、无结算单、无 outbox，而正常单 stage=30、可提现 +1000、结算单 1 条、outbox 1 条——独立事务语义实证。

## 三、P1-10 售后期长于结算周期（stage=30）的退款瀑布

修复前 stage=30 的退款只认保证金。现商户承担部分严格按 **待结算 → 可提现余额 → 保证金** 三档扣减，仅当三档合计仍不足才挂起，不再抛 DEPOSIT_NOT_ENOUGH 无限重试。

主流程：`shop-settlement-service/src/main/java/com/shop/settlement/clearing/service/ClearingReverseService.java:112-196`
- 第 1 档 `accountService.debitPendingPartial(...)`（第 114 行）→ 第 2 档 `debitAvailablePartial(...)`（第 121 行，剩余 >0 才进入）→ 第 3 档 `depositService.deductPartialForRefund(...)`（第 130 行，剩余 >0 才进入）；
- `shortfall = 扣完三档后的剩余`（第 138 行）；冲正明细记录三档实扣与缺口（第 151-159 行）；
- shortfall>0：`status=2(PARTIAL_SUSPENDED)` + ERROR 日志 + 同事务发布 REFUND_SHORTFALL outbox（第 181-190 行），**方法正常返回、消息 ACK**，杜绝毒消息无限重试；
- 保证金实扣 >0 时才写 REFUND_FROM_DEPOSIT 零额留痕流水（第 132-136 行）；
- 新增 `clearing/enums/ReverseStatuses.java`（1 全额扣回 / 2 部分挂起）、`clearing/event/RefundShortfallEvent.java`、`deposit/service/DepositDeductResult.java`（actualFen/balanceAfterFen）。
- 保证金侧：`deposit/service/DepositService.java:110` `deductPartialForRefund` 替代旧 `deductForRefund`——FOR UPDATE 锁商户行、LEAST 部分扣、余额清零返回实际值不抛错；50% 预警逻辑保留，DEPOSIT_ALERT 同步改 outbox。
- 新增流水类型 `enums/FlowChangeTypes.java:40` `REFUND_FROM_AVAILABLE = 34`；新增 topic `shop-common/.../MqTopics.java:59` `REFUND_SHORTFALL = "shop_refund_shortfall"`（纯新增常量）。

## 四、P1-12 待结算原子扣减（杜绝"先 SELECT 再按读到值条件更新"）

三档统一为"**`SELECT ... FOR UPDATE` 锁行取扣减前余额 → 一条 LEAST 原子 SQL 扣 min(need, 余额) → 服务层用锁内快照计算实际扣减值并回写流水**"。`need` 原样下发，任何读到的余额都不拼接进 SQL。

- `shop-settlement-service/src/main/java/com/shop/settlement/account/mapper/AccountMapper.java:43-49`（待结算）：

```sql
UPDATE t_sett_account
SET pending_settle_fen = pending_settle_fen - LEAST(#{need}, pending_settle_fen),
    version = version + 1, update_time = NOW()
WHERE owner_id = #{ownerId} AND role_type = #{roleType} AND deleted = 0
  AND pending_settle_fen > 0
```

- 可提现档 `AccountMapper.java:54-60`（同构，列 `available_fen`）；行锁读 `AccountMapper.java:68-71` `... FOR UPDATE`。
- 保证金档 `shop-settlement-service/src/main/java/com/shop/settlement/merchant/mapper/MerchantMapper.java:37-41`（同构，列 `deposit_balance_fen`）+ `selectForUpdate`（第 31 行）。
- 服务层回读/返回实际值：`account/service/AccountService.java:124-144`（待结算）、`152-171`（可提现）；`deposit/service/DepositService.java:110` 起。rows≠1 抛 SYSTEM_ERROR 回滚；余额为 0 返回 0 且不下发 UPDATE；(biz_no, change_type) 流水幂等保持不变（M-3 不破坏）。

并发实证（真实 MySQL，两个独立事务/连接同时提交）：`SettlementRepairRealDbTest.concurrentRefunds_atomicPendingDeduct`
- 待结算=100、可提现=200000、保证金=100000，两笔退款各需商户承担 4000；
- 断言：待结算合计恰好扣 **100**（不为负、不多扣），不足部分 **7900 全部追索到可提现**（不遗漏中间档），保证金**分文未动**，无缺口，两笔冲正均成功无异常。

## 五、DDL

`sql/settlement/V4__settlement.sql`（新建，幂等可重复执行，已应用到本地 shop_settlement）：
1. `t_mq_outbox`（与 `sql/common/V3__outbox.sql` 同构）：雪花 id、topic/tag/biz_key/body_json/deliver_at/status/重试字段，`KEY idx_status_deliver`；
2. `t_sett_clearing_reverse` 经 information_schema 守卫的存储过程加三列：`from_available_fen BIGINT NOT NULL DEFAULT 0`（位于 from_pending_fen 之后）、`shortfall_fen BIGINT NOT NULL DEFAULT 0`、`status TINYINT NOT NULL DEFAULT 1` + `KEY idx_status`。

注：仓库未接入 Flyway，SQL 按任务约定手工应用；`sql/common` 下 agent B 同样使用 V3 前缀（V3__outbox / V3__data_encryption），无工具版本冲突。

## 六、测试清单（126，全绿；surefire XML 口径）

| 测试类 | 用例数 |
| --- | --- |
| account.service.AccountServiceTest | 20（含 P1-12 待结算/可提现原子部分扣减 4 个） |
| clearing.service.ClearingReverseServiceTest | 9（瀑布三档/ stage30 不扣保证金/挂起 ACK 等） |
| clearing.service.ClearingServiceTest | 8（outbox 调用点） |
| deposit.service.DepositServiceTest | 11（部分扣赔、扣尽不抛错、50% 预警 outbox） |
| statement.service.StatementSettleServiceTest | 5（P1-4 编排/隔离/翻页） |
| **it.SettlementRepairRealDbTest**（新增，真实 MySQL） | **5**：三档顺序落库、三档扣尽挂起+outbox、P1-12 并发两笔、P1-4 单笔失败独立事务、P1-1 回滚无幽灵事件 |
| DataCipherTest / IdempotencyKeyIsolationTest 等其余 12 个类 | 68（agent B 安全用例全部保持绿） |

集成测试用最小 Spring 容器（HikariCP + MyBatis-Plus + DataSourceTransactionManager + 真实 Mapper/服务），不启动 Nacos/RocketMQ/Redis；分布式锁内联 mock（互斥语义由 InnoDB 行锁保证），outbox 只校验本地表。每用例前后清理 9100-9199 测试数据，不污染其他库表。

## 七、残留风险与后续项

1. **挂起追讨尚无独立挂起表与自动补扣任务**（任务允许后续）：当前缺口落在 `t_sett_clearing_reverse.status=2 + shortfall_fen` 并有 REFUND_SHORTFALL 告警与 ERROR 日志，消息正常 ACK 不会无限重试；但商户后续入账/补缴保证金后，系统不会自动把缺口补扣。建议后续新增 `t_sett_refund_shortfall`（或复用 status=2 明细扫描）追讨 Job：补缴/结算入账时按 reverseNo 补扣剩余档位并清零 shortfall。
2. 集成测试依赖本地 docker MySQL（localhost:3306, root/root, shop_settlement）；CI 无 MySQL 时该类需改用 testcontainers 或 profile 跳过（模块当前未引入 testcontainers 依赖，仅 BOM）。
3. 跨域分布式锁（Redisson `lock:merchant:fund:{id}`）在本测试中以行锁替代验证；生产上"锁释放在事务提交前"的 P1-3 老问题若仍存在，正确性最终由各档 FOR UPDATE/LEAST 行锁兜底，但锁等待竞争窗口未在本次范围内重构。
4. 流水 remark 文本中携带本次扣减金额（如"扣保证金 2000分"），属可读备注非权威金额；权威金额以各 change 字段与冲正明细三档列为准。
5. 本工作目录不是 git 仓库（无 .git），无法执行 commit/PR；改动均已落盘，待纳入版本管理时提交信息请按要求附 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。
