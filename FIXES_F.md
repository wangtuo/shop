# FIXES_F 修复报告（shop-marketing / shop-user / shop-product）

负责人：F ｜ 日期：2026-09-16 ｜ 构建：`mvn -o` 离线全量测试，三模块全绿

| 模块 | 基线 | 修复后 | failures/errors/skipped |
|---|---|---|---|
| shop-marketing-service | 83 | **109**（+26） | 0 / 0 / 0 |
| shop-user-service | 113 | **113** | 0 / 0 / 0 |
| shop-product-service | 148 | **148** | 0 / 0 / 0 |

测试数核对自各模块 `target/surefire-reports/TEST-*.xml`。单测中 `OutboxPublisher`、Redisson 均为 mock；
`SeckillStockClientLuaTest` 直连本地 Redis（127.0.0.1:6379）验证真实 Lua 原子性，无 Redis 时整类 `assumeTrue` 跳过。
未改动 shop-framework / shop-gateway / 其他服务 / application.yml / deploy。

---

## P1-1 事务内发送全部改走 transactional outbox

框架 API（m2 中 shop-framework-2.0.0）：
`OutboxPublisher.publish(topic, tag, payload, bizNo)`、`publishDelay(topic, tag, payload, bizNo, delaySeconds)`，
必须在活动事务内调用（事务外抛 IllegalStateException）；OutboxRelayJob 每 2s 至少一次投递，消费端按 bizNo 幂等。

全量 grep 核对（`mqProducer`/`MqProducer`/`.send(`/`.sendAsync(`/`.sendDelay(`）：三模块 main 源码已无任何直发残留，
且无 KafkaTemplate/RocketMQTemplate 等其他生产者；`MqTopics` 的剩余引用全部是 MQ 消费端（Listener / beginConsume）。

| 文件:行号 | 改动 |
|---|---|
| shop-marketing-service/.../activity/service/SeckillService.java:219-226 | `publishEvent` 统一 `outboxPublisher.publish(SECKILL_EVENT, type, event, orderNo)`；LOCK/DEDUCT/RELEASE 三类事件均在 `@Transactional` 方法（lock:57 / confirm:165 / release:181）内；bizNo 沿用 orderNo |
| shop-marketing-service/.../activity/service/GroupbuyService.java:184-192 | sendEvent 改 outbox.publish；调用方 openOrJoin:50/release:124/expireGroups:140 均 `@Transactional` |
| shop-marketing-service/.../activity/service/PresaleService.java:73-78 | 尾款超时取消改 `outboxPublisher.publishDelay(PRESALE_EVENT, …, orderNo, delaySeconds)`，延时事件随预售单同事务提交/回滚 |
| shop-marketing-service/.../activity/service/PresaleService.java:133-137 | 普通事件改 outbox.publish；register:42/confirm:82/timeoutScan:91/cancelByOrderNo:110 均 `@Transactional` |
| shop-user-service/.../account/service/impl/AccountServiceImpl.java:570-584 | `publishPointsEvent` 仅把 send 换成 `outboxPublisher.publish(POINTS_CHANGED, null, event, bizNo)`；**occupy→条件变更→finalize 时序、各 changeType、bizNo 原值（P9/R1/P1/O1/SH1/C1 等）一律未动**，6 个调用点（189/231/265/302/373/456 行）全部在 `@Transactional` 方法内 |
| shop-product-service/.../stock/service/impl/StockServiceImpl.java:287-308 | `fireStockWarning` 改 outbox.publish（bizNo=skuId，保持原值）；全部调用路径（doLock/releaseExisting/returnExisting/replenish，84/126/158/191/257 行均 `@Transactional`）在事务内，预警事件与 t_stock_warning 落库同提交 |

说明：GrowthServiceImpl、UserPointsMqServiceImpl、MqConsumeServiceImpl 中只有 DB 写入/嵌套服务调用/消费去重（beginConsume），
本来就没有生产发送，无需迁移；CouponService 无 MQ 发送。不存在「事务外、提交后纯通知」类直发点。

---

## P1-5 多 SKU 秒杀单 Redis 库存泄漏

文件：shop-marketing-service/.../activity/support/SeckillStockClient.java

批量预占 `tryAcquireBatch(long activityId, List<Long> skuIds, List<Integer> qtys)`（135-155 行），
一条 Lua 完成「全量校验 → 全量扣减」（ACQUIRE_BATCH_LUA，54-68 行）：

```lua
local n = #KEYS
for i = 1, n do
  local v = redis.call('GET', KEYS[i])
  if (v == false) then return -9223372036854775808 end          -- 任一键未初始化：整批不动
  if (tonumber(v) < tonumber(ARGV[i])) then return -1 end       -- 任一不足：整批不动
end
local minRemain
for i = 1, n do
  local left = redis.call('DECRBY', KEYS[i], ARGV[i])           -- 全部满足才扣
  if (i == 1) then minRemain = left elseif left < minRemain then minRemain = left end
end
return minRemain
```

要点：Lua 单脚本执行期间 Redis 不会插入其他命令，两个循环分离保证「先全部检查、后统一扣减」，
任一 key 不存在返回 `Long.MIN_VALUE`（NOT_INITIALIZED）、任一不足返回 -1（SOLD_OUT），两种失败路径所有 key 均不变。

SeckillService.lock（57-111 行）配套：
1. 先聚合 SKU 数量、**查齐全部 SKU 配置**（任一 SKU 不存在直接失败，尚未触碰 Redis）；
2. `batchAcquireWithFallback`（113-125）：NOT_INITIALIZED 时按 DB 可售对全部 key `initStock`（setIfAbsent 不覆盖）后整批重试一次；SOLD_OUT 直接抛 STOCK_NOT_ENOUGH；
3. Redis 成功后逐 SKU 做 DB 条件更新 `lockStock`、每 SKU 登记 LOCK outbox 事件；
4. try/catch 兜底（方案 b）：DB 条件更新 0 行或落单 UK 冲突时，`compensateRedis`（128-132）对全部已预占 key 逐笔回补后再抛——Lua 与 catch 补偿双保险。

测试：
- SeckillStockClientLuaTest（真实 Redis，5 个用例）：混合不足 {10,1} 扣 {2,2} 后两 key 余量不变；全足 {10,1} 扣 {2,1} → {8,0} 返回 0；一键缺失时已存在 key 不变返回哨兵；回补缺失键不造库存；initStock setIfAbsent 不覆盖。
- SeckillServiceTest（14 个）：多 SKU 仅一次批量 Lua（keys=[10:1,10:2]、qtys=[2,1]）、Lua SOLD_OUT 不触 DB、NOT_INITIALIZED 批量初始化后重试一次、DB 中途失败按序 release(10,1,2)/release(10,2,1) 全量回补、未知 SKU 在触碰 Redis 前失败。
- SeckillStockClientTest（mock，4 个）：校验 key 顺序、Lua 文本中「检查循环早于 DECRBY」、入参契约。

---

## P1-6 营销锁改为「锁包裹事务」，提交后才解锁

- 新文件 shop-marketing-service/.../inner/MarketingTxOps.java：独立 @Service 承载原 MarketingAppService 的全部业务事务体——
  lockInTx:43 / confirmInTx:75 / releaseInTx:92（`@Transactional(rollbackFor=Exception.class)`），
  orderNo 占坑幂等、券 lock/confirm/release、orderType 2 秒杀 / 3 拼团 / 4 预售分派逻辑原样搬迁。
- shop-marketing-service/.../inner/MarketingAppService.java:36-98：只负责锁编排。
  `runWithOrderLock`：`tryLock(10s, lease=-1 看门狗续期)` → 经 Spring 代理调用 txOps 事务方法（返回即已提交）→ finally 中
  `isHeldByCurrentThread()` 才 unlock；抢锁失败抛 TOO_MANY_REQUESTS 且不开事务；InterruptedException 恢复中断标志并抛 SYSTEM_ERROR；
  RedissonClient 缺失时 warn 降级无锁执行（依赖 DB 条件更新/UK）。
  锁 key：`mk:lock:promo:{orderNo}`，calculate 不加锁。
- 拆 Bean 的原因：同类内自调用会绕过代理，锁与事务必须分处两个 Bean 才能保证「unlock 晚于 commit」。

测试：MarketingAppServiceTest（7 个，Mockito InOrder）严格验证
`getLock → txOps.lockInTx → unlock` 顺序、confirm/release 同样「事务方法 → unlock」、
事务抛异常时 unlock 仍发生在 lockInTx 返回之后、抢锁失败不执行事务、中断恢复标志、无 Redis 降级。
MarketingTxOpsTest（10 个）承载原业务行为用例（幂等、分派、状态推进、取消回退）。

---

## P2-4 秒杀回补不得凭空造库存 + Redis 缺失降级必告警

文件：SeckillStockClient.java

- RELEASE_GUARDED_LUA（73-78 行）：先 GET 判存在性，键缺失直接返回哨兵 `Long.MIN_VALUE`（RELEASE_KEY_MISSING），
  **不执行 INCRBY**；存在才 INCRBY。乱序 RELEASE / Redis flush 后不会凭空造出库存。
  ```lua
  local v = redis.call('GET', KEYS[1])
  if (v == false) then return -9223372036854775808 end
  return redis.call('INCRBY', KEYS[1], ARGV[1])
  ```
- release（161-179 行）命中哨兵：`releaseKeyMissingCount` 计数 +1，warn 日志带 activityId/skuId/qty/missingTotal。
- SeckillService.release:192-197 收到哨兵后重新查 SKU，按 DB 可售量 `available = total - locked - sold` 调 initStock 重建（setIfAbsent 双保险），事件照常登记。
- RedissonClient 缺失的三条降级路径（initStock:95 / tryAcquire:111 / tryAcquireBatch:135 / release:161）
  全部 warn + `redisUnavailableCount` 计数，不再静默返回 0；可通过 getReleaseKeyMissingCount()/getRedisUnavailableCount() 接监控。

测试：SeckillStockClientLuaTest 真实 Redis 验证缺失键回补后键仍不存在且计数 +1、存在键正常 3+5=8；
SeckillStockClientTest 验证脚本次序（GET 早于 INCRBY）、哨兵返回、3 次降级计数=3；
SeckillServiceTest `release_键缺失_按DB重建` 验证按 DB 可售 initStock 重建且 RELEASE 事件仍登记。

---

## P2-7 「每人限参与一次」唯一键 + 友好幂等报错

DDL：sql/marketing/V3__seckill_user_uk.sql（已在本地 shop_marketing 执行，SHOW INDEX 验证）

```sql
ALTER TABLE t_seckill_order
    DROP INDEX idx_activity_user,
    ADD UNIQUE KEY uk_activity_user (activity_id, user_id, deleted);
```

- 风格与仓库既有 UK 一致（sql/order/V2__order.sql:37 uk_user_sku(user_id, sku_id, deleted)、
  product V2 uk_name/uk_sku_code 均含软删列 deleted），软删后可再次参与；uk_order_no 保留。
- 上线前去重查询（执行前本地为 0 行重复）已写入脚本注释：
  `SELECT activity_id, user_id, COUNT(*) c FROM t_seckill_order WHERE deleted=0 GROUP BY 1,2 HAVING c>1;`
  有重复需先保留最早一条、软删其余再执行。
- 应用层（SeckillService.handleInsertDuplicate:139-148）：insert 撞 DuplicateKeyException 后按 orderNo 回查——
  已有同 orderNo 记录 → REPEAT_SUBMIT「秒杀订单处理中，请勿重复提交」；
  否则 → LIMIT_PURCHASE「您已参与过该秒杀活动，每人限参与一次」；随后事务回滚、Redis 已扣量逐笔回补。

测试：SeckillServiceTest 中 mapper.insert 抛 DuplicateKeyException 的两个用例（用户 UK → 友好报错+release 回补；orderNo UK → 重复提交），
配合 Redis Lua 集成测试，并发下第二人在 DB UK 处被强约束挡下。

---

## P2-1 领券幂等键：支持每人多张 + 客户端 requestNo

文件：shop-marketing-service/.../coupon/service/CouponService.java:43-103；
控制器 CouponCenterController.java ClaimRequest 新增 `requestNo` 字段，claim 透传。

- 旧逻辑固定幂等键 `claim-{userId}-{couponId}`（24h），perUserLimit>1 时第 2 张合法领取被挡，且与
  t_user_coupon UK(user_id,coupon_id,issue_way,request_no) 矛盾。
- 新逻辑（三参 `claim(userId, couponId, requestNo)`，@Transactional 标在控制器经代理进入的此方法上；
  两参重载保留同名注解以兼容未来代理直调，其内为自调用、加入外层事务）：
  - 客户端上送 requestNo（strip 后非空）：Redis 键 `idem:marketing:coupon:claim:{u}:{c}:{requestNo}`，TTL 24h，
    request_no 原样落库；重复请求被挡或被 DB UK 兜底；
  - 未上送：仅 `marketing:coupon:debounce:{u}:{c}` **3s 短 TTL** 防连点（DEBOUNCE_TTL_SECONDS=3s），
    DB request_no 用 `claim-{userId}-{couponId}-{第N张}`，N = 当前已持张数 +1，保证多张可领，并发同号仍由 DB UK 挡住；
  - 业务失败（RuntimeException）立即 delete 幂等/防连点桶，放行立即重试；RedissonClient 缺失 warn 降级为仅 DB UK。
  - DB UK、库存条件扣减 increaseReceived/冲突回减 decreaseReceived 等原语义不变。

测试：CouponServiceTest（14 个），新增 4 个 P2-1 用例——
perUserLimit=2 连领两张落库 requestNo=["claim-1-50-1","claim-1-50-2"] 且防连点 TTL=3s；
客户端 requestNo strip 后落库、Redis 键含 requestNo、TTL=24h；
3s 窗口重复点击被拒且不查券不扣库存；业务失败删除桶允许重试。

---

## 残留风险与后续建议

1. **多 SKU 秒杀订单仍只落首条 t_seckill_order（SeckillService:90-104）**。本次 P1-5 修的是 Redis 泄漏，
   订单模型未扩展：confirm/release/skuPkId 按 orderNo `selectOne` 只处理首 SKU，多 SKU 单的其余 SKU
   在 confirm 时 locked_stock 不会转 sold；多 SKU 的 LOCK 事件共用 bizNo=orderNo，若消费端仅按 bizNo 去重会丢弃后续 SKU 事件
   （需按 bizNo+skuId 去重）。建议后续单独立项：t_seckill_order 一 SKU 一行 + confirm/release 按 orderNo 批量处理。
2. **SeckillReconcileJob 目前仍是 warn-only 对账**，发现 Redis/DB 不一致只告警不自动修复；
   releaseKeyMissingCount/redisUnavailableCount 已可接监控，自动修复建议与对账 Job 一并排期。
3. **fireStockWarning 的 bizNo=skuId 为改动前原值**：同一 SKU 多次预警共用 bizNo，消费端若按 bizNo 去重会折叠重复预警
   （保持原直发语义，未在本次扩大改动）。如需每条预警必达，建议改为 warning 记录 id 作 bizNo。
4. **无 requestNo 领券 3s 防连点**：用户 3s 内两次真实独立领取（非重试）会被按连点拒绝；C 端多领场景应上送 requestNo。
5. **V3 DDL 需在各环境按流程执行**（先跑注释中的去重查询，0 重复再 ALTER）；Flyway/启动自动迁移不在本次范围内。
6. `SeckillStockClientLuaTest` 依赖本地 Redis，CI 无 Redis 时整类跳过（脚本文本契约仍由 mock 单测兜底）。
