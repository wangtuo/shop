# 代码审查报告（任务 #20）

审查日期：2026-09-16
范围：并发正确性 / 幂等 / 事务消息 / 工程质量（静态审查，未执行构建，未改任何源码与 pom）
审查基线：工作目录当前快照（shop-user auth/JWT、shop-framework 鉴权、pay/settlement/aftersale/marketing controller、PaymentServiceImpl、sql/ 目录正由其他 agent 修改，相关条目标注「修复中，回归后复核」）

---

## 1. 总览

### 1.1 模块矩阵

| 模块 | 并发防护总体评价 | 主要风险 |
|---|---|---|
| shop-framework | 中：锁模板/幂等切面/MQ 封装可用但有关键语义缺陷 | 看门狗失效（P1-8）；切面 key 组成依赖调用方（P2-1） |
| shop-order-service | 良：状态机全量条件更新；下单编排有逆序补偿 | 超时关单不查支付域（P1-7）；无 outbox（P1-1） |
| shop-product-service | 优：库存 TCC 条件更新 + 流水 UK + 状态 CAS 最完整 | 锁在事务提交前释放（已被行锁兜住，P1-8 共性） |
| shop-user-service | **差：余额/积分「先更新后插流水且吞掉 UK 冲突」** | **退款重复入账（P0-1）** |
| shop-marketing-service | 中：券/拼团条件更新到位；秒杀 Redis 与 DB 衔接有洞 | 多 SKU 库存泄漏（P1-5）、锁重入 Redis 泄漏（P1-6） |
| shop-pay-service | 中：回调验签/金额校验/累计退款守卫齐全 | 重复发事件、远程调用在本地事务内（修复中，P1-2/P1-9） |
| shop-settlement-service | 良偏中：CAS + 流水幂等 + 瀑布模型正确 | 日终批事务自调用失效（P1-4）、已结算退款只扣保证金（P1-10） |
| shop-aftersale-service | 中：状态机 CAS、占用 active_no 条件更新正确 | startRefund 跨服务事务窗口（P1-11） |
| shop-common / shop-api | 良：BaseEvent 自带 eventId，金额全部 Long 分 | — |
| shop-gateway | 本次未深入（鉴权属 agent A） | — |

### 1.2 阻断项计数

- **P0：1 项**（资金类，用户域账户入账幂等失效）
- **P1：11 项**（其中 2 项位于 agent B 正在修改的文件，按要求标注待复核）
- **P2：9 项**
- **P3：5 项**

亮点（避免误伤）：库存 TCC（`StockServiceImpl`）、订单/支付/退款/清算/提现状态机全部走条件更新；金额一律 `Long ...Fen`，未见浮点金额；`@Mapper`/`@Service` 注解齐全；消费幂等表（event_id UK）在各域普遍落地；下单主链路是「先落库提交、后发消息 + 失败反向前关闭」的正确范式（`OrderCreateServiceImpl:137-155`）。

---

## 2. 并发与一致性发现（按严重度）

### P0-1 余额/积分/成长：先更新余额后插唯一流水，且 DuplicateKeyException 被吞 → 并发重复入账

- 文件：
  - `shop-user-service/src/main/java/com/shop/user/account/service/impl/AccountServiceImpl.java:90-118`（debitMoney/creditMoney）
  - 同文件 `:266-301`（refundPoints）、`:307-371`（grantPoints）、`:141-188`（lockPoints）
  - 消费侧仅按 eventId 去重：`shop-user-service/.../mq/service/impl/MqConsumeServiceImpl.java:24-48`
- 问题：标准写法应为「先占幂等键，再动余额」。实际顺序是：
  1. `flowMapper.selectByBizAndType(bizNo, type)` 查不到（两个并发线程都查不到）；
  2. `accountMapper.credit(...)` 无条件 `balance = balance + amount`（`UserAccountMapper.java:25-29`，入账 SQL 无任何业务键条件）；
  3. `insertFlowQuietly(...)` 插入 `(biz_no, change_type)` UK 流水，冲突时**仅 catch 打日志**（`:523-528`），异常不外抛 → 事务正常提交，余额增量保留、流水只有一条。
  
  `refundPoints`（`:290-295` UK 冲突后 return）、`grantPoints`（`:356-361` UK 冲突后 return 0）、`lockPoints`（`:177-182`）同型：余额/冻结已变动，异常被吞，事务提交。
  
  对照：`GrowthServiceImpl.addGrowth:57-71` 是**先插流水后更新成长值**，顺序正确，可作为修复模板。
- 触发场景（具体）：
  1. 渠道异步回调与支付域 15 分钟主动查询（`PayCheckDelayListener`）/对账重试几乎同时判定一笔退款成功，或 `RefundServiceImpl.retry` 与回调并发；
  2. pay 侧对同一 refundNo 产出两条 eventId 不同的 `REFUND_SUCCESS`（见 P1-2，`publishRefundSucceeded` 每次 new 事件、`BaseEvent.eventId` 构造即生成）；
  3. user 消费组两个线程并发处理：`beginConsume` 因 eventId 不同双双放行 → 两次 `creditMoney` 同一 refundNo → **余额被加两次，只留一条流水**。
  
  积分发放（bizNo=orderNo）、退款退积分（bizNo=refundNo）同理可重复发积分/重复退批次。顺序重复投递（非并发）因首笔提交后可见流水而安全，但上面的并发时序在生产真实存在。
- 严重度：**P0**（真实资金重复入账，且账实不符，事后只能靠对账发现）。
- 修复方向（代码级）：
  - 统一为「先插幂等占位、后改余额」：事务开头 `INSERT IGNORE INTO t_user_account_flow(biz_no, change_type, ...占位) `，影响 0 行直接 return；余额更新成功后回填 amount/balance_after；或
  - 至少不得吞掉 UK 冲突：让 `DuplicateKeyException` 外抛回滚事务（当前 catch 等于把保护栏拆了）；
  - `credit` 类无条件 UPDATE 增加「同事务先占键」保障；debit 保留 `balance >= amount` 条件；
  - 消费侧 `t_user_mq_consume` 增加 `(biz_no, 业务类型)` 唯一约束做第二道闸（不同 eventId 同业务键也只入账一次）。

### P1-1 全局「数据库事务内同步发 MQ」，无 outbox / afterCommit，存在幽灵消息与丢消息窗口

- 涉及（22 处 send 中的事务内调用，举例）：
  - `SeckillService.java:64,111,128,149-155`（LOCK/DEDUCT/RELEASE 在 `@Transactional` 内）
  - `PaymentServiceImpl.java:141,144-146,346,374-389`（余额支付成功、回调成功均在事务提交前 send ORDER_PAID/PAY_RESULT）
  - `RefundServiceImpl.java:185,224-238`（REFUND_SUCCESS 在退款事务提交前发）
  - `AccountServiceImpl.java:531-542`、`DepositService.java:161-172`、`GroupbuyService.java:85,94-102,156`、`PresaleService.java:72-77,101-102`、`StockServiceImpl.fireStockWarning:287-306`
  - 框架侧无任何 afterCommit/outbox 设施：全仓 grep 无 `TransactionSynchronization/afterCommit/outbox`；`MqProducer.send`（`shop-framework/.../mq/MqProducer.java:71-86`）为纯同步发送。
- 问题：
  1. send 成功后本地事务回滚/提交失败 → 消费者收到**幽灵事件**（库存 confirm、清算登记、积分发放等跨域副作用已发生，本地却没落库）；
  2. 本地提交后应用崩溃/send 抛错 → 事件丢失。部分链路有扫描任务兜底（支付有主动查询、订单有超时扫描），但 **ORDER_PAID / REFUND_SUCCESS 没有生产侧重发任务**，丢了就靠各域永远看不到对方状态。
- 触发场景：`SeckillService.lock` 多 SKU 循环中第 2 个 SKU 失败：第 1 个 SKU 的 LOCK 事件已发、DB 随事务回滚，SECKILL_EVENT 消费者（当前无消费者，仅未来对账）会读到不存在的锁定；支付回调在 `markSuccess` 后 send 完 ORDER_PAID，提交时 DB 连接断开 → 订单/积分/清算全部推进而支付单回 WAIT。
- 严重度：P1（架构性一致性缺口，design 要求生产级最终一致）。
- 修复方向：二选一：
  - 本地消息表：事务内写 outbox 表，后台任务/SteadyJob 投递，投递结果回写；
  - 或全部改为 `TransactionSynchronizationManager.registerSynchronization(afterCommit)` 发送 + 关键事件保留状态扫描重发（当前只有支付/订单有扫描，需补 ORDER_PAID、REFUND_SUCCESS 的对账重发）。
  - 下单主链路（`OrderCreateServiceImpl.sendCreatedEvents` 在 `OrderPersister.persist` 事务提交后调用）已是正确范式，建议推广到上述全部点。

### P1-2 支付回调并发重复时仍重复发布 ORDER_PAID（修复中，回归后复核）

- 文件：`shop-pay-service/.../payment/service/impl/PaymentServiceImpl.java:339-347,350-372`
- 问题：`completeSuccess` 中条件更新 0 行且发现最新状态已是 SUCCESS 时直接 `return`（`:354-360`），但 `handleNotify` 在其返回后**无条件**继续执行 `markNotifyDone` + `publishPaid(findByPayNo(...))`（`:343-346`）。两个不同 notifyId（渠道重发常换通知号）或「回调 + 主动查询」并发时，两条 ORDER_PAID 都会发出，且 `BaseEvent.eventId` 各自不同。
- 下游影响盘点：订单域 `markPaid` 是 CAS 10→20，0 行安全（`PayEventConsumer:56`）；清算有 `uk_order_no`（`V2__settlement.sql:115`）；用户积分/退款见 P0-1（不抗并发不同 eventId）；商品 confirm 走流水 UK 安全；营销 confirm 走券/秒杀状态 CAS 安全。**短板集中在用户域**。
- 严重度：P1（本身是 P0-1 的触发器之一）。
- 修复方向：`completeSuccess` 返回 boolean（是否本次真正推进），仅本次推进成功才 `publishPaid`；或在 `publishPaid` 前再校验「本线程是状态 10/20→30 的成功者」。
- 备注：该文件在 agent B 修改清单内，标注**修复中，回归后复核**。

### P1-3 Redisson 锁：显式 leaseTime 致看门狗不续期；且锁普遍在事务提交前释放

- 文件：`shop-framework/.../lock/DistributedLockTemplate.java:39-57`
- 问题 1：`tryLock(wait, 30, SECONDS)` 显式指定了 leaseTime，Redisson **不会启用看门狗自动续期**（看门狗仅在 leaseTime=-1 时生效）。类注释「业务执行慢时看门狗自动续期」与实现不符。业务（尤其含 Feign/渠道 HTTP 的 `PaymentServiceImpl.lockAndCreate:124`、`RefundServiceImpl.refund:68`、`WithdrawService.apply:75`）超过 30s 锁自动失效，第二线程可进入临界区。
- 问题 2：所有调用点都是「`@Transactional` 方法内 `lockTemplate.execute(...)`」，锁在 lambda 返回时释放，而事务在方法返回后才提交——锁不覆盖「更新后到提交前」窗口：
  - `StockServiceImpl.doLock:100`（有行 X 锁 + UK 兜底，实际安全）
  - `PaymentServiceImpl.lockAndCreate:124`（findByOrderNo 判重可被第二线程读到旧值，依赖 t_pay_order order_no UK，待 agent C 确认）
  - `ClearingReverseService.onRefundSucceeded:75`（与 P1-12 叠加）
  - `WithdrawService.apply:75`、`RefundServiceImpl.refund:68`（同型）
- 严重度：P1。
- 修复方向：需要看门狗的重载用 `tryLock(wait, TimeUnit.SECONDS)`（lease=-1）并配 `lockWatchdogTimeout`；或把锁粒度上移到事务外（锁模板包事务，而非事务包锁），并在每个临界区明确列出「锁失效后由哪条 DB 条件更新/UK 兜底」。

### P1-4 日终结算批 `@Transactional` 自调用失效，500 条/页的多步记账无事务保护

- 文件：`shop-settlement-service/.../statement/service/StatementSettleService.java:86-157,164-178`
- 问题：`runDailySettle`（无 `@Transactional`）在同类中直接调用 `this.settlePage(...)`（`:168`），Spring 代理被绕过，`settlePage` 上的 `@Transactional` 不生效，页内全部语句以自动提交执行：阶段 20→30 CAS（`:101`）、商户货款两笔流水、平台佣金/技服费/通道费、营销出账（`:118-137`）任一步失败都会留下「stage=30 但流水半截」的清算单；重跑时 `selectDuePage` 只捞 stage=20，且 CAS 0 行跳过，**缺口永久不会自愈**。Job 与 AdminController（`:81`）都走这条路径。
- 严重度：P1（资金账本残缺）。
- 修复方向：把 `settlePage` 拆到独立 Bean（如 `SettlePageExecutor`）注入后调用；或给 `runDailySettle` 加 `@Transactional`（注意页内 500 单长事务）；推荐独立 Bean + 单清算单粒度事务。

### P1-5 秒杀多 SKU：只落一条 SeckillOrder，confirm/release 漏放其余 SKU，Redis 库存永久泄漏

- 文件：`shop-marketing-service/.../activity/service/SeckillService.java:53-77,99-130`
- 问题：
  1. `lock` 循环多个 SKU，但只在第一个 SKU 时 `seckillOrderMapper.insert`（`:65-75`，`firstOrder` 后再无插入）。`confirm(orderNo)`/`release(orderNo)` 按 orderNo 只查到一条 SeckillOrder，只处理第一个 SKU 的 DB `deductStock/releaseStock` 与 Redis `INCRBY`；多 SKU 秒杀单其余 SKU 的锁定库存与 Redis 预占**永不释放/永不扣减**。
  2. 循环中任一后续 SKU 失败抛异常，事务回滚 DB，但此前 SKU 的 Redis `DECRBY` 没有补偿（`acquireWithFallback` 只在本 SKU `lockStock==0` 时回补，`:88-92`）。
  3. `SeckillReconcileJob:36-40` 发现不一致只 `log.warn`，不自动修复。
- 触发场景：一单买同一秒杀活动的 2 个不同 SKU（下单接口允许，`OrderCreateServiceImpl` 按 SKU 聚合后整单传营销域）；或第 2 个 SKU 售罄导致整单失败。
- 严重度：P1（超卖方向相反——库存被永久少卖，且账实漂移）。
- 修复方向：SeckillOrder 按 (order_no, sku_id) 落多行（DDL `uk_order_no` 需改，agent C 对齐）；confirm/release 按 orderNo 查全部行循环处理；Redis 扣减注册事务同步（afterCompletion(ROLLED_BACK) 逐笔 INCRBY 补偿），或把 Redis 操作移出事务并在 catch 中显式回补所有已扣 SKU。

### P1-6 营销锁幂等检查 + insert 与秒杀 Redis 扣减之间存在并发泄漏窗口

- 文件：`shop-marketing-service/.../inner/MarketingAppService.java:50-78` → `SeckillService.lock`
- 问题：重复 lock 的幂等靠「先 select t_marketing_lock by orderNo，后 insert UK」。两个并发同 orderNo 请求（下单补偿重试 + ORDER_CREATED 消费、或 Feign 超时重放）都 select 为空后，都会先执行 `stockClient.tryAcquire`（Redis DECRBY 已扣）；最终一个 insert UK 冲突回滚，但**它扣的 Redis 库存不会回补**（DB 随事务回滚，Redis 不回滚）。
- 严重度：P1（与 P1-5 同属 Redis/DB 非原子，Redis 泄漏概率更高）。
- 修复方向：先 `INSERT IGNORE t_marketing_lock` 占坑（0 行直接返回），占坑成功后再扣 Redis/券；失败路径统一 afterCompletion 补偿 Redis。

### P1-7 订单超时取消不查支付域，可把「已支付」订单关掉，且无事后对账

- 文件：`shop-order-service/.../order/service/impl/OrderOperateServiceImpl.java:76-106`；`shop-order-service/.../mq/PayTimeoutListener.java:39-44`；`shop-order-service/.../job/PayTimeoutScanJob.java:33-45`；对侧 `PayEventConsumer.onPaid:49-58`（markPaid 0 行仅打 info 日志）
- 问题：`timeoutCancel` 只看本地 `status==10`，不向 pay 域确认渠道是否已扣款。延时消息（expirePaySeconds）/每分钟扫描与渠道回调、pay 域 15 分钟主动查询在临界点竞态：订单 10→50 成功后，ORDER_PAID 到达，`markPaid` WHERE status=10 影响 0 行被当作「重复/已处理」记日志结束——用户付了钱、订单已取消、库存/券/积分已释放、秒杀库存已回补并被他人买走，没有任何任务扫描「支付成功但订单取消」做恢复或退款。
- 严重度：P1。
- 修复方向：取消前先调 pay 域反查（pay 侧 `scanTimeout` 关支付单前已先查渠道，订单侧应对齐）；或建立 P0 对账：pay=SUCCESS 且 order=CANCELLED → 自动发起退款（优先）或恢复订单。

### P1-8（并入 P1-3 锁问题，编号保留）

### P1-9 退款：先渠道打款/余额退回，后做累计额守卫；锁在提交前释放（修复中，回归后复核）

- 文件：`shop-pay-service/.../refund/service/impl/RefundServiceImpl.java:67-91,136-186,242-255`
- 问题：
  1. `executeRefund` 先循环 `doRefundOne`（真实渠道退款 HTTP / `userClient.creditBalance` 跨服务调用，`:141-158,188-213`），**之后**才用 `paymentMapper.addRefundedFen`（WHERE `refunded_fen + ? <= amount_fen`，`PaymentMapper.java:49-52`）做防透支守卫（`:161-164`）。并发两笔部分退款（锁 key 为 orderNo，但锁在事务提交前释放，见 P1-3）：第二笔读到旧 `refundedFen` 通过 `validateRefundable`，渠道钱已退完，守卫才返回 0 行 → 退款单置 FAIL，资金已出。
  2. 渠道调用与余额退回都在本地事务提交前完成，提交失败则外部副作用无法回滚（余额侧靠 bizNo 流水顺序幂等，但见 P0-1 并发洞）。
- 严重度：P1（超退资金）。
- 修复方向：调整顺序——先 `addRefundedFen` 预留额度（成功才继续），渠道成功后置成功，任一步失败回滚预留；或对 payment 行 `SELECT ... FOR UPDATE` 并把锁移到事务外。
- 备注：`PaymentServiceImpl`/pay-service controller 在 agent B 清单内，退款服务同域，标注**修复中，回归后复核**。

### P1-10 已结算清算单（stage=30）退款只扣保证金，不扣可提现余额，不足即卡死

- 文件：`shop-settlement-service/.../clearing/service/ClearingReverseService.java:100-113` + `account/service/AccountService.java:120-131`
- 问题：售后期长于结算周期时（B 级商户 confirm 后很快到期），清算已 stage=30、待结算已转可提现。退款冲正的商户部分只 `tryDebitPending`（待结算为 0，SQL `WHERE pending_settle_fen >= ?` 返回 0），随后**全额从保证金扣**；保证金不足 `deductForRefund` 抛 `DEPOSIT_NOT_ENOUGH`（`DepositService.java:95-99`），消费回滚无限重试，REFUND_SUCCESS 永远 ACK 不掉。商户经济上虽被保证金顶替，但可提现余额未被追索、保证金阈值联动限提，语义与 design 瀑布不符。
- 严重度：P1。
- 修复方向：瀑布增加「可提现余额」档位（pending → available → deposit），或结算周期 due_date 一律晚于最长售后期；重试链路对 DEPOSIT_NOT_ENOUGH 进入挂起+告警表而非无限重试。

### P1-11 售后 startRefund：本地事务内调用支付域，回滚后残留跨域退款单

- 文件：`shop-aftersale-service/.../aftersale/service/impl/AftersaleServiceImpl.java:717-763,745-758`
- 问题：先 insert 本地退款单 → Feign `payClient.refund`（pay 域独立事务已提交，渠道可能已退款）→ 之后 `change(o, REFUNDING, ...)` 状态 CAS 失败等异常会让整个本地事务回滚；重试时本地记录消失，`selectByAftersaleNo` 为空又生成**新 refundNo** 再调支付域。pay 侧靠累计退款守卫挡住第二次出款（第二次大概率超可退额报错），结果是售后单无法推进、支付域残留孤儿退款单，需人工对账。另外 `:749-752` 退款失败先 `setStatus(FAIL)` 再 throw，FAIL 更新随回滚一并消失。
- 严重度：P1。
- 修复方向：refundNo 由售后域一次生成并持久化（独立事务/先落库再远程），重试复用同号（pay 侧已支持 refundNo 幂等返回，`RefundServiceImpl:70-75`）；失败状态用 `REQUIRES_NEW` 独立事务写。
- 备注：aftersale controller 在 agent B 清单，服务逻辑标注**修复中，回归后复核**。

### P1-12 退款冲正待结算扣减读到提交前旧值，可能多扣保证金

- 文件：`ClearingReverseService.java:101-113,158-166`
- 问题：`pendingBalance` 在分布式锁内读取，但锁在事务提交前释放（P1-3）。两笔同商户并发部分退款：tx1 扣待结算 80 已提交锁已放，tx2 此前读到 pending=100、need=50，`debitPendingGuarded` 因真实余额 20 不足返回 0 行，`tryDebitPending` 直接返回 0（`AccountService:125-130`），50 全部转保证金——可待结算里还有 20 未追索。
- 严重度：P1（金额错配，需与 P1-10 联动修复）。
- 修复方向：单条原子 SQL `SET pending = pending - LEAST(#{need}, pending)` 并回读实际扣减值，再决定保证金差额；不要先 SELECT 再按 SELECT 值做条件更新。

---

## 3. 幂等 / 事务 / 消息（补充发现）

### P2-1 `@Idempotent` key 设计问题集合

- `OrderCreateServiceImpl.java:91`：key 仅 `#request.clientToken`，**不含 userId**；clientToken 为 null/空时所有用户共用键 `idem:order:create:null`（切面 `IdempotentAspect:50-57` 对 null 不报错），一个空 token 请求会挡住全局后续请求。建议 key 加 `#userId` 并在入口强制 clientToken 非空。
- `CouponService.java:34-39`：领券 key=`userId:couponId` 固定 24h，而 `t_user_coupon` UK 为 `(user_id,coupon_id,issue_way,request_no)`、主动领取 requestNo 固定 `claim-{userId}-{couponId}`（`V2__marketing.sql:136`）→ `perUserLimit>1` 的券在代码和 DB 两层都不可能领第二张，配置与实现矛盾。建议 requestNo 追加领取序号/客户端请求号，幂等 key 改短 TTL 或改用请求号。
- `MerchantDepositController.java:45`：key=`#request.amountFen`；`MerchantWithdrawController.java:33`：key=`channelAccount:amountFen`——24h 内两笔**同金额的合法**缴费/提现第二笔被 REPEAT_SUBMIT 拒绝，且不含商户身份。建议改用商户号+客户端 requestNo。修复中（controller 鉴权归 agent B），回归后复核。
- 切面语义：业务抛异常立即删 key 允许重试（`IdempotentAspect:61-64`），对「部分成功+补偿失败」的场景会放行重入（下单补偿是 best-effort catch+log，`OrderCreateServiceImpl:480-519`），依赖各域 bizNo/orderNo 幂等兜底，需在 P0-1 修复后才真正闭环。

### P2-2 渠道回调上的 `@Idempotent` 与服务内 DB 幂等冲突（修复中，回归后复核）

- `ChannelNotifyController.java:31`：成功处理后 24h 内同一 notifyId 的重复通知（渠道对账重发属正常行为）在切面直接被拒（REPEAT_SUBMIT），拿不到服务层本已实现的「重复回调幂等返回成功」（`PaymentServiceImpl:294-304,326-332`）→ 渠道按未 ACK 持续重试、产生无谓告警流。建议去掉该注解，直接用 `t_pay_notify_log` 幂等。

### P2-3 MQ 消费对不可恢复错误无限重试，无错误分类/DLQ 策略

- `shop-framework/.../mq/MqConsumerRegistrar.java:75-86`：任何 Throwable 都返回 FAILURE 触发重投。业务类终态拒绝（如金额不符 `PAY_ERROR`、状态 CONFLICT 已终态）会永久重试。建议区分可恢复（DEPENDENCY_FAIL/超时/5xx）与不可恢复（4xx 业务终态），后者 ACK+告警/死信，避免毒丸消息占满消费线程。

### P2-4 秒杀 Redis 客户端降级/回补语义不严谨

- `SeckillStockClient.java:58-68`：RedissonClient 缺失时返回 `0L`（伪装成「扣减成功且剩 0」），调用方靠 DB 条件更新兜底尚可，但无告警/指标；
- `:71-79`：`release` 对不存在的 key 直接 INCRBY，乱序消息（RELEASE 先于 LOCK 到达、或 key 被 flush）会**凭空造出库存**。建议 release 脚本内先判存在性，键缺失只记日志或按 DB 为准重建。

### P2-5 支付单重复发起一律返回旧单，终态单无法重新支付（修复中，回归后复核）

- `PaymentServiceImpl.java:123-150`：不区分旧支付单状态，CLOSED/FAIL/超时单重复发起时原样返回，用户无法对同一订单再次支付（前端拿到旧单无法拉起渠道）。建议仅 WAIT/PAYING 返回旧单，终态单允许换支付方式新建（配合订单状态与渠道单号规则）。锁+事务内执行渠道下单 HTTP（`:230-243`）造成长事务/长锁，建议渠道交互移到事务外或缩短临界区。

### P2-6 支付超时批一个大事务内多次渠道 HTTP，且 self-invocation

- `PaymentServiceImpl.scanTimeout:444-462`：循环内 `this.activeQuery(...)`（自调用，且二者均 `@Transactional`），实际整批共用一个事务，多次渠道查询 HTTP 全在事务内，任一笔异常整批回滚，已处理结果不落地。建议逐笔独立事务（抽独立 Bean 或 REQUIRES_NEW），查询失败逐笔捕获继续。

### P2-7 限购校验为 TOCTOU，秒杀无用户维度强约束

- `PurchaseLimitChecker.java:38-45` + `OrderCreateServiceImpl:197`：历史量只统计已支付订单，两个并发待支付单同时通过校验，支付后超限。`t_seckill_order` 仅普通索引 `idx_activity_user`（`V2__marketing.sql:200`，非唯一）。秒杀「每人限 1 件」在并发下可破。建议秒杀单改 `UNIQUE(activity_id,user_id)`（agent C DDL 对齐），或限购数用 Redis 原子占位（未支付占位+取消回补）。

### P2-8 售后退款失败状态被同事务回滚白写

- `AftersaleServiceImpl.java:746-753`：catch 中把退款单置 FAIL 后重新抛出，`@Transactional(rollbackFor=Exception.class)` 连 FAIL 更新一起回滚（见 P1-11）。失败原因丢失，只能靠超时任务扫描。建议失败落库用独立事务。

### P2-9 批处理事务/MQ 粒度

- `GroupbuyService.expireGroups:140-163`：整批一个事务且逐成员事务内发事件，一条失败全批回滚；
- `WithdrawService.remitBatch:153-183`：500 行长事务内含 MQ 发送；
- `PayTimeoutListener.onMessage`：监听器事务包裹 Feign 释放调用，远程慢调用会拖住本地行锁。
建议逐单事务、事件 afterCommit 发送（与 P1-1 一并整改）。

### P3（工程/韧性）

1. `IdGenerator.java:36-47`：Redis 不可用时多实例可能由相同本机特征推导出相同 workerId（雪花撞号）；`OrderNoGenerator.nextSequence:61-66` INCR 与 EXPIRE 两条命令非原子（崩溃留永久 key，影响很小）。
2. 全部 5 个 Feign Client 未配置 fallback/fallbackFactory（`shop-api/.../client/*`），下游抖动直接 500；当前靠补偿 Job 兜底，建议查询类加降级。
3. SECKILL_EVENT 当前无任何消费者（全仓仅定义/发送），事务内幽灵事件暂不致命，但未来对账消费者上线前必须先解决 P1-1。
4. 分布式锁 key 前缀风格不统一（`lock:stock:*` / `pay:lock:*` / `lock:settle:*` / `lock:merchant:fund:*`），建议统一命名空间便于监控与迁移。
5. `DepositService.scanAndRefundResigned:128-149` 全表商户无分页扫描；`adminPage:152-159` 手写 offset 且 count/wrapper 双份条件，数据量增大后有性能隐患。

---

## 4. 工程质量（编译/配置静态判断）

- **金额类型**：全仓金额字段/变量均为 `Long ...Fen`（订单/支付/清算/营销试算/账户），未发现 Double/BigDecimal 混用计算，`MoneyUtils.allocate` 最大余数法分摊不差 1 分（`PayEventConsumer.distributeRefund:95-104`）。通过。
- **Result 包装**：controller 均返回 `Result<T>`；Feign 反序列化由各域 `FeignResults.unwrap` 统一拆包，未发现裸返回实体的 controller（抽查支付/结算/订单）。
- **注解完整性**：脚本扫描全部 `*Mapper` 接口均有 `@Mapper`、全部 `*ServiceImpl` 均有 `@Service/@Component`，未见漏注解导致的编译/装配风险。`@MapperScan` 仅 settlement 显式声明，其余靠 mybatis-plus 对 `@Mapper` 的自动扫描，可正常工作。
- **条件更新覆盖率（正面）**：订单 `markPaid/markCancelled/markShipped/markConfirmed`、支付 `markSuccess/addRefundedFen`、渠道流水 `addPaidFen`、库存全部流转、券 0→4→1/0、清算 stage CAS、提现状态 CAS、售后 `updateStatus(no,from,to)`、拼团 join/leave——超卖/超退/状态倒挂的 DB 闸门总体扎实；本次高危发现集中在「跨服务/Redis/MQ 边界」而非库内并发。
- **RocketMQ 配置**：消费者注册（`MqConsumerRegistrar`）按 Bean 注册 group，tag 表达式恒为 TAG 类型但传 `*` 时也用 TAG 枚举（`:54-56`），Broker 端 `*` 作为 TAG 表达式合法，可用；延时消息用 `setDeliveryTimestamp`（需要 RocketMQ 5.x proxy 支持定时），部署侧需确认 proxy 版本，否则所有超时延时消息失效（只剩扫描兜底）。建议在部署文档显式标注。
- 未跑 `mvn`，未做字节码级编译验证；未发现明显的泛型/import 级编译错误（重点文件均通读）。

---

## 5. 「修复中待复核」清单（其他 agent 在改，本报告不作最终结论）

| 编号 | 位置 | 关注点（回归后请重新验证） | 责任 agent |
|---|---|---|---|
| W1 | `PaymentServiceImpl.handleNotify/completeSuccess/publishPaid` | P1-2 重复回调/查询是否仍重复发 ORDER_PAID；事务内发消息（P1-1） | B |
| W2 | `RefundServiceImpl.refund/executeRefund` | P1-9 先打款后守卫的顺序、锁释放早于提交 | B |
| W3 | `ChannelNotifyController:31` | P2-2 切面幂等与 DB 幂等冲突；controller 鉴权 | B |
| W4 | `MerchantDepositController:45`、`MerchantWithdrawController:33` | P2-1 幂等 key 缺商户身份/同金额误杀；controller 鉴权 | B |
| W5 | pay/settlement/aftersale/marketing 各 controller 鉴权 | 未在本次审查范围，鉴权回归后另行验 | B |
| W6 | shop-user-service auth/JWT、shop-framework 鉴权/令牌、`LoginLockService` | 未审 | A |
| W7 | `sql/` 全部 DDL 与实体对齐 | 本报告引用的关键 UK 需 agent C 确认/落地：`t_pay_order.uk(order_no)`、`t_seckill_order` 由 uk_order_no 改 uk(order_no,sku_id)、秒杀用户维度 UK（P2-7）、`t_user_account_flow.uk(biz_no,change_type)` 存在性（P0-1 前提）、`t_marketing_lock.uk(order_no)` | C |
| W8 | `AftersaleServiceImpl.startRefund`（aftersale 域） | P1-11/P2-8 跨服务事务与失败状态持久化 | B（同批改时顺带） |

---

## 6. 实际读过的关键文件

框架/公共：
- `shop-framework/src/main/java/com/shop/framework/idempotent/IdempotentAspect.java`、`Idempotent.java`
- `shop-framework/src/main/java/com/shop/framework/lock/DistributedLockTemplate.java`
- `shop-framework/src/main/java/com/shop/framework/mq/MqProducer.java`、`MqConsumerRegistrar.java`
- `shop-framework/src/main/java/com/shop/framework/id/IdGenerator.java`、`bootstrap/ShopService.java`
- `shop-common/src/main/java/com/shop/common/model/BaseEvent.java`

订单/商品：
- `shop-order-service/.../order/service/impl/OrderCreateServiceImpl.java`、`OrderOperateServiceImpl.java`、`OrderPersister.java`
- `shop-order-service/.../mq/consumer/PayEventConsumer.java`、`mq/PayTimeoutListener.java`、`job/PayTimeoutScanJob.java`、`support/PurchaseLimitChecker.java`
- `shop-order-service/.../idgen/OrderNoGenerator.java`
- `shop-product-service/.../stock/service/impl/StockServiceImpl.java`

营销：
- `shop-marketing-service/.../activity/service/SeckillService.java`、`support/SeckillStockClient.java`、`mapper/SeckillSkuMapper.java`
- `shop-marketing-service/.../inner/MarketingAppService.java`、`mq/OrderCreatedListener.java`、`mq/OrderPaidListener.java`、`mq/MqConsumeTemplate.java`
- `shop-marketing-service/.../coupon/service/CouponService.java`、`coupon/mapper/CouponMapper.java`、`UserCouponMapper.java`
- `shop-marketing-service/.../activity/service/GroupbuyService.java`、`PresaleService.java`、`job/SeckillReconcileJob.java`

支付：
- `shop-pay-service/.../payment/service/impl/PaymentServiceImpl.java`、`controller/ChannelNotifyController.java`
- `shop-pay-service/.../refund/service/impl/RefundServiceImpl.java`
- `shop-pay-service/.../payment/mapper/PaymentMapper.java`、`ChannelFlowMapper.java`
- `shop-pay-service/.../mq/listener/PayCheckDelayListener.java`

用户：
- `shop-user-service/.../account/service/impl/AccountServiceImpl.java`、`GrowthServiceImpl.java`
- `shop-user-service/.../account/mapper/UserAccountMapper.java`
- `shop-user-service/.../mq/service/impl/UserPointsMqServiceImpl.java`、`MqConsumeServiceImpl.java`
- `shop-user-service/.../mq/listener/OrderPaidListener.java`、`RefundSucceededListener.java`

结算/售后：
- `shop-settlement-service/.../clearing/service/ClearingService.java`、`ClearingReverseService.java`
- `shop-settlement-service/.../account/service/AccountService.java`、`deposit/service/DepositService.java`、`withdraw/service/WithdrawService.java`
- `shop-settlement-service/.../statement/service/StatementSettleService.java`、`job/DailySettleJob.java`
- `shop-settlement-service/.../mq/listener/PaymentSucceededListener.java`
- `shop-aftersale-service/.../aftersale/service/impl/AftersaleServiceImpl.java`（startRefund/change/timeout 段）、`aftersale/mapper/OrderItemRefMapper.java`

DDL（核对唯一键）：`sql/settlement/V2__settlement.sql`、`sql/aftersale/V2__aftersale.sql`、`sql/marketing/V2__marketing.sql`（相关表段）

---

> 建议修复顺序：P0-1（用户域入账幂等）→ P1-2/P1-9（支付重复事件与退款顺序，与 agent B 改动合并验证）→ P1-4（结算批事务）→ P1-5/P1-6（秒杀 Redis 泄漏）→ P1-7/P1-10/P1-12（关单竞态与退款瀑布）→ P1-1 全局 outbox 整改 → 其余 P2/P3。

---

## 修复记录（2026-09-16）

### ✅ P0-1 用户域入账幂等 —— 已修复

文件：`shop-user-service/src/main/java/com/shop/user/account/service/impl/AccountServiceImpl.java`

- 新契约 **occupy-flow-first**：所有余额/积分变动先插入 `status=0` 占位流水（依赖 `uk_biz_type` 唯一键），占位成功后才做条件扣款/入账，成功后 `finalizeFlow` 置 `status=1` 回填余额。
  - `occupyFlow(...)`：捕获 `DuplicateKeyException` 返回 null → 调用方按幂等成功直接返回，**绝不动余额/积分**（修复旧实现「先扣款、UK 冲突只 log」导致的重复扣款/重复入账）。
  - `insertFlowStrict(...)`：CAS 获胜路径（deduct/release/expire）唯一键冲突直接上抛，占位随事务回滚，不允许吞掉。
- `debitMoney`/`creditMoney`：getOrCreateAccount → occupy（null 即返回）→ 条件借/贷（0 行抛错，占位回滚）→ finalize。
- `lockPoints`：冻结存在性检查 → occupy → `freezePoints` → freeze 插入（去掉 try/catch 吞错）→ finalize。
- `refundPoints`：occupy 在前，积分入账与批次插入不再吞 UK。
- `grantPoints`：保留每日上限裁剪，clip 后 occupy；占用冲突返回 0L；grant 插入不设防（冲突必传播）。
- 测试：`AccountServiceImplTest` 6 个旧用例（原本固化旧的错误时序）重写为新契约断言；user 模块 **113 tests, 0 failures/errors**（离线 mvn -o）。

### ✅ P1-3 Redisson 看门狗被显式租约禁用 —— 已修复（默认路径）

文件：`shop-framework/src/main/java/com/shop/framework/lock/DistributedLockTemplate.java:25,34`

- 默认 `execute(key, action)` 改用 `WATCHDOG_LEASE = -1L` → `tryLock(3L, -1L, SECONDS)`，启用看门狗自动续期；业务超过 30s（GC/慢 SQL）不再发生「锁已释放、事务未提交」的并发穿越。
- 显式 `execute(key, wait, lease, action)` 保留，供明确需要限时兜底的调用方使用。
- 测试：新增 `DistributedLockTemplateTest`（3 用例：校验 tryLock(3,-1) + finally unlock；异常仍 unlock；抢锁失败 10007 且不 unlock）。framework 模块 **16 tests green**。
- ⚠️ 残留开放项：部分调用点在 `@Transactional` 方法内加锁、锁在事务提交前由 finally 释放（锁-提交窗口）。需逐点改为「锁包事务」（transaction template 嵌套在锁 action 内）或 afterCommit 解锁，列入下一轮。

### ✅ P1-7 支付超时关单竞态 —— 已修复

文件：`shop-order-service/src/main/java/com/shop/order/order/service/impl/OrderOperateServiceImpl.java:80-99`

- `timeoutCancel` 在本域状态判断之外，若订单已有 `payNo`，必须 Feign 反查支付域：
  - 支付单 `SUCCESS` → warn 日志并保留订单（等支付成功事件/对账追平），绝不关单；
  - 未成功 → 正常超时关单；
  - 支付域不可达 → 异常上抛，扫描任务按单捕获、下分钟重试（fail-safe：宁晚关，不错关）。
- 测试：构造器补 `payClient`；新增 3 用例（成功拦截 / WAIT 正常关 / Feign 故障抛出且不 markCancelled）。order 模块 **157 tests, 0 failures/errors**。

### 待合并验证（agent B 交易支付域 lane）

- P1-2 重复 ORDER_PAID 去重、P1-9 退款顺序（支付/退款状态机）以 agent B 产出为准，合并后重跑 pay/settlement/aftersale 全量测试并做全 reactor `mvn clean verify`。
- P1-4/P1-5/P1-6/P1-10/P1-11/P1-12/P1-1 未动，按上节建议顺序继续。

---

## 修复记录（2026-09-16 第二轮：P1 全部清零 + 安全阻断项闭环）

### 执行链路
- 框架层（主控）：Transactional Outbox（`shop-framework/.../outbox/`，`t_mq_outbox` 七库已建）、幂等空键拦截、毒消息终态分类（MqErrorPolicy/MqConsumerRegistrar）。
- Agent D（FIXES_D.md）：pay/aftersale 的 P1-1（8 发送点全部 outbox 化）、P1-2（completeSuccess CAS boolean，重复回调仅获胜方发事件）、P1-11（售后退款号先落库 + REQUIRES_NEW FAIL 持久化，AftersaleRefundStore）、P2-2（回调 @Idempotent 删除）、P2-5/P2-8 补测试固化。
- Agent E（FIXES_E.md）：settlement 的 P1-1（5 发送点）、P1-4（逐单调代理 Bean，失败隔离 stage=20 自愈）、P1-10（退款瀑布 待结算→可提现→保证金 + shortfall 挂起 REFUND_SHORTFALL）、P1-12（FOR UPDATE + LEAST 原子扣减，并发实证）。
- Agent F（FIXES_F.md）：marketing/user/product 的 P1-1（7 发送点）、P1-5（多 SKU 单 Lua 批量预占 + 失败逐笔回补）、P1-6（锁包事务，提交后释放）、P2-4（回补键缺失按 DB 量重建）、P2-7（uk(activity_id,user_id,deleted)，V3__seckill_user_uk.sql 已上活库）、P2-1（领券 requestNo）。
- Agent G（FIXES_G.md）：P2-5 终态支付单重新支付——active_slot 墓碑槽位（sql/pay/V4__pay_multi_attempt.sql 已上活库），FAIL/CLOSED 旧单入槽后新建支付单，退款显式定位成功行；+8 测试（pay 79 green）。
- DDL 专项（DDL_REVIEW.md 修复记录）：cart/favorite/brand/sku deleted BIGINT 墓碑化（P0-1 UK 冲突）、pay 8 表 COLLATE、索引补齐、71 表零漂移。

### 测试基线（全 reactor verify 以当次结果为准）
framework 42（含 outbox 8、mq 9、限流 9）、user 113、product 148、marketing 109、order 158、pay 79、settlement 126、aftersale 62。

### 安全阻断项
SECURITY_REVIEW.md 12 项 release blocker 全部闭环，逐项证据见 SECURITY_FIXES_LEDGER.md（含网关归一化 404、内部 token、注册/支付/退款鉴权、平台 userType=2、售后 IDOR、JWT/渠道密钥 prod fail-fast、Swagger/actuator 收敛、AES-GCM 加密与脱敏、Redis 滑动窗口 @RateLimit 四落点 + 登录失败锁定、TLS Ingress）。

### 仍开放（不阻断验收）
1. 锁-提交窗口的其余调用点（P1-3 第二轮，marketing 已由 F 修正；逐点审计继续）。
2. 提现历史明文行加密迁移、Sentinel 全局 QPS 规则、验证码、JWT refresh/吊销、mTLS、依赖补丁升级（见 SECURITY_FIXES_LEDGER.md 遗留清单）。
3. 过期 WAIT 支付单重新支付需等 15 分钟核查置 CLOSED（FIXES_G 有意取舍）；退款 REQUIRES_NEW FAIL 路径超时扫描。
4. 秒杀多 SKU t_seckill_order 仅首行持久化、SeckillReconcileJob 仅 warn（FIXES_F 残留）。

---

## 修复记录（2026-09-17 第三轮：中间件高可用加固 + Spring AOP 绑定崩溃修复）

### H-1 RocketMQ 客户端 5.0.3 → 5.0.7（生产级 HA 阻断项）

- **现象**：chaos 演练中 Broker 停机 45s 再恢复后，低频生产者（user/product/marketing/settlement/aftersale）永久不可用，报
  `IllegalStateException: Stream is already completed, no further calls are allowed`（ClientSessionImpl 复用了 broker 重启前已完成的 gRPC telemetry 流）；高频的 order/pay 因不断建连恰好绕过。
- **修复**：根 pom `rocketmq-client.version` 5.0.3→5.0.7（新版重建 telemetry 会话）。
- **配套 1——gRPC 超时可调**：5.x 客户端 settings 首轮同步超时硬编码 3s（`SETTINGS_INITIALIZATION_TIMEOUT`）+ `ClientConfiguration.requestTimeout`。单机 Docker 端口转发抖动/Proxy 重启/高负载下消费者批量 `CancellationException: Task was cancelled`。新增 `shop.mq.request-timeout-seconds=10`（MqProperties），MqProducer/MqConsumerRegistrar 构造 ClientConfiguration 时统一注入。
- **配套 2——注册重试指数退避**：字节码核实 `PushConsumerBuilderImpl.build()` 启动失败路径**不关闭** ClientImpl/gRPC 资源，固定 3s 风暴重试会持续泄漏事件循环。改为 base 3s、`base·2^(n-1)` 封顶 30s + ±20% 抖动、无上限重试（`shop.mq.register-backoff-millis` 可调），单消费者泄漏速率压到约 2 次/分钟，恢复后一次成功即停止。
- **配套 3——topic 预建**：RocketMQ 5.x gRPC Proxy 不自动建 topic（TBW102 不生效），chaos.sh 探测 topic `shop_chaos_probe` 改为脚本内幂等 `mqadmin updateTopic`（经 shop-rmq-namesrv 容器执行）。
- **证据**：`chaos.sh` 全绿（Redis 停 20s + 稳态 30s 零业务失败；RocketMQ 停 45s 期间 7 库 outbox 并行排空；恢复后 30s 零失败；慢车道 status=2 探针 7/7 在 120s 内重放成功 status=1，零死信）。日志 `/tmp/chaos5.log`：`PASS=25 FAIL=0`。

### H-2 Spring AOP「JoinPointMatch was NOT bound」崩溃（E2E 28/59 回归根因）

- **现象**：Controller `@RateLimit` 包裹 Service `@Idempotent` 的下单链路，内部 advice 抛
  `IllegalStateException: Required to bind 2 arguments, but only bound 1 (JoinPointMatch was NOT bound in invocation)`，全部下单接口 10009。
- **根因链**（经 spring-aop 6.1.6 字节码逐级核实 + 启动期代理链转储实证）：
  1. `AspectJProxyUtils.makeAdvisorChainAspectJCapableIfNecessary` 把 `ExposeInvocationInterceptor.ADVISOR` 插到顾问链 index 0，但随后 `AspectJAwareAdvisorAutoProxyCreator.sortAdvisors` 用 AspectJ `PartialOrder` 按 order 重排；
  2. Expose 顾问是内部类 `DefaultPointcutAdvisor`，**不实现 Ordered**（等效 LOWEST_PRECEDENCE），而 `IdempotentAspect` 显式 `@Order(HIGHEST_PRECEDENCE)` → 排序后链路为 `[幂等 advice, Expose]`（无 @Order 的限流切面所在 Controller 代理则为 `[Expose, 限流]`）；
  3. 参数绑定型 advice（`@Around("@annotation(x)")` + 方法形参 X）的 JoinPointMatch 由 `AspectJExpressionPointcut.matches(method,cls,args)` 在 isRuntime 动态匹配时暂存，暂存前有 `ExposeInvocationInterceptor.currentInvocation().getMethod() == 匹配方法` 的恒等校验；
  4. 幂等 advice 执行时链上 Expose 尚未（对本代理而言永不）运行，ThreadLocal 里是**外层 Controller 代理**的 invocation，方法不匹配 → 不暂存 → advice 形参绑定只剩 JoinPoint 1 个实参 → 崩溃。
  即：**任何「形参绑定 + order 严于 LOWEST_PRECEDENCE」的切面，在嵌套代理调用中必崩**；单层代理恰好不触发，故此前 59/59 绿。
- **修复**：两个切面统一切点写法（对齐阿里 SentinelResourceAspect 的生产实践）：
  - 切点改全限定静态形式 `@annotation(com.shop.framework.idempotent.Idempotent)` / `...RateLimit)`，advice 方法删除注解形参——静态匹配 `isRuntime=false`，JoinPointMatch 不再参与形参绑定；
  - 注解实例改在 advice 内用 `AopUtils.getMostSpecificMethod` + `AnnotatedElementUtils.findMergedAnnotation` 反射获取（支持接口方法/元注解）。
  - 保留 IdempotentAspect 的 HIGHEST_PRECEDENCE（H3 锁-提交顺序语义不变）。
- 同步修正 3 个单测类的直接调用签名（framework/order/settlement），并修复 product `StockServiceImplTest` 9 处 Mockito matcher/裸值混用法（新 Mockito 严格校验下暴露）。
- 全 reactor 885 单测 0 失败。

---

## 修复记录（2026-09-18/19 第四轮：W7 最终验收实证缺口修复波）

本轮全部由 final-acceptance.sh 在**同一套最终产物**上的实证失败驱动，非静态推测；每条均附黑盒复现与回归证据。

### R4-1 Feign 子上下文装配缺陷：自定义 feign.Client 压制 LB 包装（本轮最大实证缺陷）

- **现象**：同一套产物 E2E 59 例中 33→20 例失败，全部 10008「下游服务不可用」；保留根因链后日志暴露
  `UnknownHostException: shop-product-service`——服务名被直做 DNS，负载均衡根本没生效（手工购物车链路复现）。
- **根因链**（Spring Cloud OpenFeign 4.1.1 字节码核实）：
  1. `HttpClient5FeignLoadBalancerConfiguration.feignClient(LoadBalancerClient, HttpClient, LoadBalancerClientFactory, List<transformer>)`
     在**每个 @FeignClient 的 NamedContextFactory 子上下文**以 `@ConditionalOnMissingBean(feign.Client.class)` 注册
     `FeignBlockingLoadBalancerClient(new ApacheHttp5Client(httpClient), …)`；
  2. 框架旧实现 `FeignResilienceConfiguration` 在主上下文暴露了自定义 `feign.Client`（ApacheHttp5Client），
     父上下文 bean 存在即抑制子上下文整个 LB 包装的注册 → client 直拿服务名拨号 DNS → UnknownHostException；
  3. 该异常被 Resilience4jCapability 统一归一成 10008（见 R4-2），表面看像「依赖挂了」，极具误导性。
- **修复**：删除自定义 `feign.Client` bean，只暴露官方扩展点——HC5
  `org.apache.hc.client5.http.classic.HttpClient`（`@ConditionalOnMissingBean`，由 core 的
  HttpClient5FeignConfiguration 消费，受 `spring.cloud.openfeign.httpclient.hc5.enabled=true` 控制）：
  连接池（total/per-route/TTL/evictExpiredConnections）全部在该 HttpClient 上配置，LB 包装按官方机制正常装配。
  新增 `FeignResilienceConfigurationTest` 手工上下文断言「HttpClient bean 存在且容器内无 feign.Client bean」锁死回归。

### R4-2 10008 根因链可观测性：Resilience4j 归一化时必须保留 cause

- **现象**：R4-1 排查初期，10008 只剩「下游服务不可用: clientName」，无法区分「No servers available」、Nacos 订阅空、
  NIO 死通道三种完全不同的故障。
- **修复**：`Resilience4jCapability` catch 分支（CallNotPermitted/BulkheadFull/Retryable/IOException）
  改三参构造把原异常作为 cause 保留；GlobalExceptionHandler 对 DEPENDENCY_FAIL/DEPENDENCY_TIMEOUT
  用三参 `log.warn(msg, throwable)` 打全链。新增 3 例单测（IO 重试耗尽/熔断 open/非业务异常计分）断言 cause 链。

### R4-3 网关 429 空体：自定义限流工厂在 deny 决策点直写包裹体（CHAOS 实证）

- **现象**：CHAOS 3.5 关 Redis 后 23 项断言 FAIL：限流 fail-closed 触发 429，但响应 **content-length=0**，
  黑盒拿不到 R-B4 要求的 `{code:10007,…}` 包裹体。
- **根因**：官方 `RequestRateLimiter` deny 时只 `setStatusCode(429)+setComplete()`；
  先前用 `ServerHttpResponseDecorator` 重写 setComplete 补体，reactor-netty 下补体被吞。
- **修复**：新建 `ShopRequestRateLimiterGatewayFilterFactory`（@Component），在 deny 决策点直接
  `response.writeWith(10007 envelope JSON)` + 429 + application/json；放行路径回写 X-RateLimit-* 头
  （常量取自 `RedisRateLimiter.*_HEADER`，RateLimiter 接口上没有这些常量）；空限流键（认证前路径）放行不查限流器。
  注意 @SuppressWarnings 必须注在方法上（注在语句上 javac 报 illegal start of expression）。3 例单测锁三路径。
  GatewayRequestPrepareFilter 同步删除被证伪的 decorator 补体逻辑。

### R4-4 宿主应用幂等重启 + JVM 退出可观测

- 旧 shop JVM 对两次 SIGTERM 无响应（shutdown hook 被损坏 NIO/Redisson 通道卡死），最终 jar 与运行进程不一致曾导致
  新修复「看似不生效」。start-apps.sh 启动前增加「pidfile TERM→等 30s→残留 shop jar KILL -9」幂等块；
  stop-apps.sh 同语义重写；start_jvm wrapper 记录 `[JVM_EXIT] svc 信号/退出码 时间`，外部回收不再无痕。
  修复编辑中一处 `done` 误写成 `fi` 的语法错（bash -n 门拦截）。

### R4-5 CHAOS 自助准备：身份/地址/SKU 不再依赖引导假设

- 旧 chaos.sh 用买家 load_1/load_50（fresh 库不存在，E2E 引导 USER_POOL=0）、地址缺失即跳过写探针、
  WSKU 从列表接口取（列表只有 spuId，**永远取空 → 所有真实下单探针被静默跳过=假覆盖**）。
- 修复：段首幂等 `ensure_buyer 1/50`（注册+登录取 token）；地址缺失自动 POST；
  WSKU 改走 `/products/{spuId}/skus` 取真实 skuId。

### R4-6 E2E 保证金用例对齐 B10 三段式真实资金链路

- `DepositPayRequest` 早已要求 payMethod/terminal（payScene=4 真实支付单，到账以 ORDER_PAID 事件 CAS 10→20 入账），
  但 E2E helper 仍按旧「一步直充」只发 amountFen → 10001。World 新增 startDeposit / mock 渠道回调 / Poller 轮询入账，
  与订单支付路径同构。

### R4-7 验收链路/运维件

- final-acceptance.sh：brokerIP1 提取加 `tr -d '[:space:]'`（broker.conf 实为 `brokerIP1 = 127.0.0.1` 带空格，
  不 trim 则 kind 前按 LAN IP 自动重建静默失效）。
- RUNBOOK 新增 §4.1：broker 磁盘水位 0.90（含等于）拒写的识别与处置（见下条实证）。
- **磁盘水位实战**：RocketMQ broker 在宿主盘 92% 时拒全部 PUT（50001 disk full），支付 fanout 全链路 E2E 失败；
  安全回收 Docker build cache 4.63GB + 悬空镜像 0.9GB 后比例降到 0.90 仍被拒（阈值含等于），继续降至 0.89 +
  重启 broker 强制重采样后恢复，outbox 到期待投递行全部 status=0→1（pay/order 两路 relay 静默排空，新支付回调秒级入账）；
  剩余 status=0 行经核对全部为 deliver_at 在未来的延迟消息（支付超时 15min / 自动确认 10d / 售后窗口），非积压。
  生产残留：broker 卷容量/水位监控告警（见残留清单）。

### R4-8 Feign 2xx 业务码被 DecodeException 二次包装（CHAOS 恢复探针实证）

- **现象**：Redis 宕机恢复后的支付探针（假单号）期望拿到业务语义码，实际返回 **10009 系统繁忙**；
  pay 日志：`feign.codec.DecodeException: 订单不存在 … Caused by: BizException: 订单不存在`。
- **根因**：`ShopResultDecoder` 对 2xx + `Result{code!=0}` 正确抛 BizException，但 Feign
  `SynchronousMethodHandler` 把解码器抛出的一切 RuntimeException 统一包成 `DecodeException`。
  旧 InvocationHandler 只在出熔断边界后识别裸 BizException：包装后①熔断 ignore 谓词看到
  DecodeException 把业务失败计入失败率（可误熔断）；②GlobalExceptionHandler 兜底成 10009，
  业务码（订单不存在等）整体丢失。
- **修复**：`Resilience4jCapability` 的 callable 内在熔断器统计**之前**拆包
  `DecodeException(cause instanceof BizException)` → 直接抛 cause；非 BizException 包裹维持原样。
  新增单测打满 100 次滑窗断言「调用方收到裸 BizException(50001) + 熔断器 CLOSED + failedCalls=0」。

### R4-9 chaos.sh 执行环境健壮性

- macOS 后台 shell 的 `LC_CTYPE=UTF-8` 是无效值（回落 C）且优先于 LANG，导致 `"$PAYCODE，…"`
  变量名吞入全角逗号字节报 `unbound variable` 且脚本中途死亡（恢复后下单探针根本没跑到=假覆盖）。
  先改为脚本顶部无条件 `LC_ALL=en_US.UTF-8`（缺失回落 C.UTF-8）。
- **追加实证（同日第四次全门复跑，CHAOS 再次死于同类 `line 355: PAYCODE…: unbound variable`）**：
  `/bin/bash 3.2.57` 在 macOS 上即便 `LC_ALL=en_US.UTF-8` 仍把全角字节吞进变量名
  （`/bin/bash -c 'set -u;A=1;echo "$A，x"'` 实测复现，locale 方案证伪）。真修复是
  **变量与多字节字符相邻一律写 `${VAR}` 花括号定界**；全量扫描 `deploy/**/*.sh`，
  共修 11 处可执行位点（chaos ×2、ha-check ×3、final-acceptance/apply-sql/seed/
  start-apps ×2/10-apply-sql/create-secrets 各 1；注释 2 处不动），8 个脚本 `/bin/bash -n` 全过，
  花括号形态在 bash 3.2 实测输出正确。locale 导出保留（仍利于 sed/awk 等工具字符类）。
- 恢复断言（登录/支付/下单）从 PONG 后单次探测改为最多 20~30s 轮询：redis-cli PONG 只代表进程存活，
  网关 Lettuce 与应用侧 Redisson 死通道排空重建实测滞后（`WriteRedisConnectionException` 窗口），
  单次探测会把「自愈中」误判为「未自愈」。

### R4-10 CHAOS 门正则误抄 kind HA 产物串（假 FATAL）

- 花括号修复后宿主混沌全量跑完：五小节全 PASS，`混沌结果: PASS=30 FAIL=0`，
  但 final-acceptance.sh 仍 `FATAL: CHAOS`。
- 根因：第 6 阶段门沿用了第 9 阶段（`ha-check.sh`）的产物串 `HA 结果: PASS=…`，
  而 `chaos.sh` 有史以来输出的是 `混沌结果: PASS=…`（2026-09-17 历史证据同串，
  即该门从未真正绿过，W7 链路此前从未越过 CHAOS）。
- 修复：第 6 阶段门改 grep `混沌结果: PASS=.* FAIL=0`，并在脚本中加注释锁定两门串不可混用；
  修复后在同一套运行中最终 jar 上重跑取证（实测 `混沌结果: PASS=30 FAIL=0`）。

### R4-11 镜像非数字 USER 与 runAsNonRoot 不兼容（KIND_DEPLOY 全业务 Pod CreateContainerConfigError）

- 现象：最终镜像首次在 kind 拉起，7 个业务 Deployment 的首副本全部
  `CreateContainerConfigError: container has runAsNonRoot and image has non-numeric user (app),
  cannot verify user is non-root`。
- 根因：Dockerfile 用命名 `USER app`（且 `useradd -r` 系统 UID 随基础镜像漂移），
  而 podSecurityContext 钉了 `runAsNonRoot: true` + `fsGroup: 10001`；kubelet 无法静态验证
  命名用户非 root。此前旧镜像是 W1-C 硬化前构建，硬化后从未真正用新镜像验证过拉起。
- 修复：Dockerfile 显式 `groupadd -g 10001` / `useradd -u 10001`，
  `COPY --chown=10001:10001`、`USER 10001:10001`，与 fsGroup 对齐并写入注释锁定。

### R4-12 K8s Service 环境变量碰撞网关端口（gateway Pod 启动即 NumberFormatException 崩溃）

- 现象：gateway Pod 启动 5 次 crashloop，日志
  `NumberFormatException: For input string: "tcp://10.96.36.185:80"`（Spring 解析 management/server port）。
- 根因：namespace 内 Service 名 `shop-gateway`，kubelet 默认注入 Docker link 风格变量
  `SHOP_GATEWAY_PORT=tcp://10.96.x.x:80`；网关 `application.yml` 恰用
  `server.port: ${SHOP_GATEWAY_PORT:8080}`（宿主形态避让 8080 的开关），K8s 下被脏值覆盖。
- 修复：gateway Deployment podSpec 加 `enableServiceLinks: false`，并显式 env
  `SHOP_GATEWAY_PORT="8080"`（双保险，K8s 形态 Service 80→targetPort 8080 固定）。
  7 个业务服务端口为硬编码（8081-8087），grep 确认无同类变量碰撞，不改。
- 同步关闭 W1-C 残留：借数字 UID（R4-11）把网关补齐与 7 业务工作负载同款硬化
  （pod 级 runAsNonRoot/fsGroup=10001/seccomp RuntimeDefault + 容器级
  allowPrivilegeEscalation=false/readOnlyRootFilesystem/drop ALL + /tmp、/tmp/heapdump、
  /app/logs emptyDir + imagePullPolicy=IfNotPresent）。

### R4-13 kind Secret 仍用 root/root，业务 Pod prod fail-fast 全部拒绝启动（R-Z2 首次在 K8s 被真正验证）

- 现象：12 个业务 Pod 统一 crashloop：
  `检测到生产安全配置缺失：spring.datasource.password（环境变量 SHOP_DB_PASSWORD）为空、
  仍为弱口令 root、或长度少于 8 位`——框架 fail-fast（W1 R-Z2）按设计工作，
  是 kind/05-kind-secret.yaml 长期带着 `mysql-username/password=root/root` 从未在
  「prod profile + 新镜像」组合下启动过。
- 修复（不放松红线、不硬编码生产密钥）：
  1. 新增 `deploy/mysql/20-kind-app-user.sql`：幂等建应用账号 `shop_app`（强口令，
     16+ 位混合），仅授七个 `shop_*` 库权限；root 保留给本地运维/迁移。
  2. compose 挂载该文件进 initdb.d（fresh 初始化自动执行）；final-acceptance MIDDLEWARE
     对存量数据卷幂等补执行并验证登录。
  3. kind/05-kind-secret.yaml 改 `shop_app` + 强口令（文件头明确：仅 kind 开发值，
     生产仍由 create-secrets.sh 经环境变量/KMS 注入）。
- 部署期操作：live Secret 轮换后 rollout restart；手动 restart 产生新 RS 与 ha-check
  已缩 0 的旧 RS 叠加，在 16Gi 单节点上 `maxUnavailable=0` 无法 surge（Insufficient
  cpu/memory），复用 ha-check 的旧 RS 缩 0 手法释放资源后收敛。

### R4-14 网关 Deployment 漏注入 Redis 主机 → 限流 fail-closed 把全部请求判 429（HA 第五节入口探测 FATAL）

- 现象：ha-check 第 5 节 `pick_https_port` 对 `/api/product/products` 探测，443 拿到
  429（header `x-ratelimit-burst-capacity:200/replenish-rate:100`，remaining=0），
  严格判 200 的探测两候选端口全失败 → exit 8；网关日志
  `RedisConnectionException: Unable to connect to localhost/<unresolved>:6379 ...
  failOpen=false`。
- 根因：网关 Deployment（00-namespace-config.yaml）env 列表从未注入
  `SPRING_DATA_REDIS_HOST`，`application.yml` 占位 `${SHOP_REDIS_HOST:localhost}` 落到
  容器内 localhost；Lettuce 连不上，限流器按 R-B4 fail-closed 红线拒绝一切。宿主形态由
  start-apps.sh 提供该环境变量所以从未暴露；7 个业务工作负载在 10-services.yaml 早已
  注入同一 ConfigMap 键 REDIS_HOST，唯独网关漏了（W1-D 领地跨文件不一致）。
- 修复：网关容器 env 补 `SPRING_DATA_REDIS_HOST` configMapKeyRef（shop-infra-config/
  REDIS_HOST），与 7 业务工作负载同源；加注释锁定 fail-closed 语义。
- 探针健壮性：`pick_https_port` 的可达判据从「必须 200」改为「任意非 000 HTTP 码」
  （429/503 都证明 TLS→ingress→Service 链路在，仅 000 为不通），并对候选端口各重试
  15 次（~45s）覆盖滚动收敛/令牌窗口；真正的 200 率硬判定仍由随后 4 分钟流量线程
  （基线 10 连绿 + 全程失败率 <2%）负责，判定强度不减。

### R4-15 单节点 HPA 在滚动风暴中扩出不可调度副本，污染 HA 资源/PDB 快照断言

- 现象：连续 rollout restart 的 CPU 尖峰把 HPA desired 推到 aftersale=3/marketing=4/
  pay=5/product=3，单节点 16Gi 无法调度（Insufficient cpu/memory，4 副本长期 Pending）；
  第 3 节 replicas=2 与第 4 节「product 快照 Pod 数=2」前置断言因此 FAIL。
- 修复：ha-check 单节点分支在调度补丁后自动把 8 个 HPA live-cap 到 [2,2] 并钉
  Deployment=2（PERF_REPORT §7.5 原要求人工操作，现门内自动；HPA 对象保留故第 3 节
  HPA 存在性校验仍过；多节点容量验证走独立 perf 集群，不执行该段）。

### R4-16 seed-oversell.sh 把 data 对象整体当 SPU/活动 id（产品创建响应已包成 {spuId,warnings}）

- 现象：K6_SEED 第二阶段 FATAL `SPU { "spuId": 2101309445349228545, "warnings": [] }
  提交审核失败`——整段 JSON 被拼进 `/products/{id}/submit` URL。
- 根因：脚本 `jq -r '.data // empty'` 假定 data 是裸标量；商品创建接口现返回对象
  `{"spuId":...,"warnings":[...]}`（多了 warning 通道），标量假设失效。历史干净库跑
  超卖时响应形态更窄或已随 TRADE 接口演进，此脚本长期未在 KIND 链回归。
- 修复：SPU/ACTID 两处 jq 改为按类型归一——对象取 `.spuId` / `.activityId //
  .seckillActivityId // .id`，标量直取；兼容新旧两形态。seed.sh 主用户/SKU 链路本次
  已实际通过（2000 用户幂等、1000 SKU 已存在跳过），不改。

### R4-17 K6_SMOKE 20 TPS 大面积 10008/10010：宿主 MySQL 连接上限 + RocketMQ 磁盘水位拒写（同一负载下两个独立容量缺陷）

- 现象：R4-16 修复后 K6_SMOKE 跑到 ~9s 起出现全量用户 `unexpected order code:
  code=10008 下游服务不可用: shop-product-service / shop-user-service` 与 504/10010
  依赖超时；3 分钟 3600 次迭代 order_success_rate 与 p95(3948ms) 阈值双双 crossed。
- 根因一（连接池打满）：kind 16 个业务 Pod × Druid 基线 maxActive（order 30/其余
  20）理论上限 ~400，宿主 MySQL 出厂 `max_connections=151`；压测实测
  Threads_connected=146、Max_used=152，product 日志 191 次 `errorCode 1040 Too many
  connections` → Feign 连锁 10008/10010（R4-1/R4-8 的归一化工作正常，是真实容量
  故障而非误报）。
- 修复一：(1) 宿主 docker-compose MySQL 加 `--max-connections=600`（本地开发中间件；
  生产云托管 MySQL 配额由实例规格保证，K8S §A 残留）；(2) ha-check 对 7 个业务
  Deployment live-cap `SHOP_DATASOURCE_TUNING_MAXACTIVE=10`（14 池×10=140，复用
  DataSourceTuningProperties 既有覆盖键；只打 kind 集群，生产清单 30/20 分档不改）。
- 根因二（MQ 拒写拖垮订单链路）：order outbox 重试全部 `50001 broker's disk is full
  [CL/CQ/INDEX=0.92]`；macOS Docker Desktop VM 盘 461G 仅 ~45G 可用，RocketMQ
  5.3.1 写门 `diskSpaceWarningLevelRatio` 源码硬上限 0.90（核对官方源码
  DefaultMessageStore$CleanCommitLogService，broker.conf 与 -D sysprop 均无法抬高），
  且 RunningFlags 有迟滞——标记 full 后须回落 0.85 才恢复，0.898 仍被拒。
- 修复二：运维侧安全回收（build cache 2.1G、dangling/停用的监控镜像、orphan volume
  0.65G、broker otherdays 日志 2.6G；**未动任何 shop 数据卷与在用镜像**）把 VM 盘压到
  0.8979，新 broker 冷启动即在水位下；broker.conf.tpl 关闭 commitlog 1GB 预分配/
  预热（`preAllocateMappedFileEnable=false`/`warmMapedFileEnable=false`）防止滚动
  新文件瞬时打穿水位，`fileReservedTime=6`、`diskMaxUsedSpaceRatio=95`（清理阈值，
  非写门）；重建后 brokerIP1 按当前 LAN 192.168.1.103 渲染，mqadmin 实测
  `SEND_OK`、kind Pod 经 LAN:18081 回连不变。生产 MQ 云托管，水位/扩容属 K8S §A。

### R4-18 K6_SMOKE 紧接 HA 扰动后冷启动，p95 被冷 JVM 窗口毒化（成功率已达标、p95=2.3s crossed）

- 现象：R4-17 修复后重跑，order_success_rate 99.36%（3578/3601，过 0.99 阈值），
  10008/10010 仅 23 次且全部集中在开始后 25s 内（随后 ~2m35s 零错误），但
  p95=2296ms 越过 800ms 阈值，K6_SMOKE FATAL。
- 根因：HA 第 5 节刚做 pod-kill + gateway/order 两次 rollout restart，K6_SEED 只有
  GET/登录/建品轻流量，到 K6_SMOKE 时新 JVM 的 JIT、Druid 物理连接（cap=10 冷启动
  逐建）、Nacos 推送路由缓存全冷；首批写请求遭遇 Feign read timeout（冷态真实行为，
  非缺陷——稳态实测零错误、avg 382ms）。测量窗把冷启动计入 p95 属方法学问题。
- 修复：k6-order.js smoke 增加 `warmup` 场景（SMOKE_WARMUP 默认 90s 同速恒定到达、
  startTime=0s），测量场景 smoke 在 warmup 后开始；阈值改用 k6 自动 scenario 标签
  `order_success_rate{scenario:smoke}` / `order_create_latency_ms{scenario:smoke}`，
  预热样本不参与成败判定（SMOKE_WARMUP=0s 可关）。30/40 TPS 复扫共用同模式，拐点
  数据同样排除冷启动。

### R4-19 全链 E2E 25 例失败：broker store bind 挂载采样 Mac 宿主盘 + kind/宿主双形态共用中间件串扰（附 create-topics GROUPS 保留数组潜伏缺陷）

- 现象：R4-18 后首次十二门全链在 E2E 阶段 FATAL（59 例 Failures=25）：
  AftersaleE2ETest 6/6「等待超时(20000ms)：订单…状态=20」（支付扇出饿死）、
  OrderCreateE2ETest 4 例 `40001 下游服务不可用: shop-marketing-service`、
  Pay/Concurrency/Settlement 共 15 例连带失败；同期宿主 order 日志 27,066 次
  `MQ 同步发送失败 … 50001`（07:40–07:55）。
- 根因一（bind mount 采样错位，R4-17 的遗漏面）：R4-17 的回收只在「store 落在
  Docker VM 盘」前提下有效；compose 实际把宿主目录 `./rocketmq/data` bind 到
  `/home/rocketmq/store`，broker `df` 经 virtiofs0 采样的是 **Mac 宿主系统盘**
  （460Gi，开发机自身数据 369Gi，可用常年 ~45Gi，水位 90–91%），不是容器 overlay
  （59G/34%）。宿主盘数据不可删，安全回收最多回收数 GB，**结构上不可能降到 0.85**，
  mqadmin sendMessage 实测 `CODE:14 disk is full [CL:0.91 CQ:0.91]`。修复：compose
  store 改 Docker 命名卷 `shop-rmq-data`（落 VM overlay，重建后实测 31.8%、
  SEND_OK），旧 bind 目录 `deploy/rocketmq/data`（1.6G）保留不删不再挂载；
  MIDDLEWARE 守卫注释同步说明新采样口径。
- 根因一附属（命名卷权限）：Docker 新建命名卷属主 root:root，broker 进程 uid 3000，
  直接启动 `FileNotFoundException: store/lock (Permission denied)` 崩溃循环
  （bind 时代靠 virtiofs 权限映射未暴露）。修复：compose entrypoint 以 root 先
  `mkdir/chown -R rocketmq:rocketmq store` 再 `runuser -u rocketmq` 降权启动，
  broker 本体始终非 root。
- 根因二（双形态串扰）：上一轮 KIND 验收的 16 Pod 未销毁，与宿主 HOSTAPPS 共用同一套
  MySQL/Redis/RocketMQ。Nacos 靠 group 隔离注册（kind=shop-ha-kind 注册 10.244.x，
  宿主=DEFAULT_GROUP 注册 LAN IP），但 **MQ 消费组同名共享**，残留 kind 消费者偷走
  宿主 E2E 的支付/订单事件；网络还不对称（宿主→kind Pod IP 超时不可达，反向可达），
  Feign 偶发拨到 10.244.x 即 10008。修复：final-acceptance 在 HOSTAPPS 前置静默
  （先删 8 HPA 防拉回，再 scale 全部 deploy/statefulset=0，等 Pod 清零；幂等、
  无 shop 命名空间时跳过），并在 KIND_IMAGE 后/KIND_DEPLOY 前用 stop-apps.sh 停止
  宿主 8 应用——保证「同一时刻只有一个形态在线」，HPA 随 10-services.yaml 重建。
- 根因三（潜伏脚本缺陷，本次借空 store 暴露）：create-topics.sh 用 bash **只读保留
  数组名 `GROUPS`**（当前用户 OS GID 列表）命名业务消费组数组，赋值被静默丢弃，
  循环实际拿 16/17 个数字 GID 建组——脚本常年打印「共 16 个消费组」无人核对
  （W0 后应为 34 组）。34 个真实业务组历史上全靠 broker
  `autoCreateSubscriptionGroup=true` 兜底自动建成，`-r 16 -q 1` 重试/DLQ 参数从未
  经脚本声明（本地 broker 恰好也开了 autoCreate，故 W3 以来未炸；生产关闭 autoCreate
  后此脚本会建出 16 个数字垃圾组、真实组全部缺失）。修复：改名 `MQ_GROUPS`，
  34 组全部带 -r 16 -q 1 创建成功（26 topics/34 groups 全 OK、DLQ 空），
  并删除本次误建的 16 个数字 GID 垃圾组。
- 验证：同一套最终源码 → jar → 镜像重跑 final-acceptance.sh 十二门（证据目录
  `deploy/loadtest/evidence/final-20260920-*`）；mqadmin 探活 SEND_OK、
  `df store`=31.8%；host 与 kind 形态互斥由脚本两道前置保证。

### R4-20 MQ 消费者冷启动宽限：Nacos 15s 空窗 + broker 积压风暴在冷 JVM 上打熔断（E2E 13 例 10008 的根因）

- 现象：R4-19 修复后全链 E2E 从 25 例降到 13 例失败，全部是 order 侧
  `40001 下游服务不可用: shop-marketing-service`（10008）与 Aftersale/Pay/Settlement
  等待订单状态超时；8 应用 actuator 健康全 200、marketing 自身无错，order 日志却满是
  Resilience4j `CircuitBreaker 'shop-marketing-service' is OPEN`。
- 时间线取证（宿主形态）：order JVM 08:23:14 Started，Nacos 首次 marketing 实例列表
  推送 **08:23:29 才到达（Started 后 15s 空窗）**；与此同时 broker 积压（陈旧支付
  超时关单消息）在 PushConsumer 一注册时即被约 200 个 RocketmqMessageConsumption
  线程并发拉取（08:23–25 共 3,191 条「非待付款忽略」、1,083 次释放营销资源失败，
  每分钟 621/282/180/71 递减），全部打在 JIT/Druid 物理连接/Ribbon 路由缓存皆冷的
  marketing 上。Resilience4j 默认 COUNT_BASED 滑窗（100 次/失败率 50%/慢调用率 60%
  /慢阈 2s/minimumNumberOfCalls=10/舱壁 30）被十几次冷调用打到 OPEN，wait=10s 后的
  半开探针又落在风暴尾部反复失败，CB 持续 OPEN 至 08:25:59——整个 E2E 窗都在熔断。
- 修复（shop-framework 框架件，所有服务继承）：
  1. `MqProperties.consumeStartDelayMillis`（默认 20,000ms，设 0 关闭）；
  2. `MqConsumerRegistrar` 消费者注册不再由 SmartLifecycle.start() 立即触发，
     改由 `@EventListener(ApplicationReadyEvent.class)` + 启动宽限门控
     （readyEventFired/registrationScheduled/graceUntilMillis 三标志 +
     start() 末尾 onAfterStart() 兜底防事件早到，守护线程 sleep 满宽限才
     registerAllLoop；stop() 复位）。宽限覆盖两个冷启动竞态：Nacos 首推 15s 空窗、
     broker 积压并发重放；
  3. 宽限期消息留 broker（outbox 同事务持久不丢），滚动发布由同组在线实例承担消费；
  4. `MqConsumerHealthIndicator` 宽限期返回 OUT_OF_SERVICE（phase=startup-grace，
     consumers n/total）而非 DOWN——语义是「尚未接管消费」非故障，探针组未含
     mqConsumers，不影响 K8s rollout。
- 验证（同一套最终产物 final-20260920-102225）：framework 单测 6/6（新增
  「就绪事件后宽限期内不注册消费者」「宽限关闭时不进入宽限态」2 例）；宿主 order
  10:26:16 打「MQ 启动宽限开始：20000ms」、10:26:36–38 才注册全部 7 个消费者；
  宽限后陈旧消息处理仅 138 条（风暴主体已被前序形态消化/幂等忽略），order/
  marketing/pay/settlement 自 10:26:36 起 **0 次 is OPEN**；**E2E 59 例
  Failures=0 Errors=0 Skipped=4，CHAOS PASS=30 FAIL=0**；kind 形态 16 Pod 同样
  观测到宽限日志且 0 次 OPEN，HA 六节 PASS=74 FAIL=0。
- 注：本次十二门链在 KIND_DEPLOY apply 完成后的 rollout 观测循环被 macOS
  低内存回收 kill（非验收失败），final-acceptance.sh 增 EVIDENCE_DIR 可复用证据
  目录，以 ONLY_STAGES=HA,K6_SEED,K6_SMOKE,K6_OVERSELL 在同一证据目录、同一套
  源码→jar→镜像续跑（见 08-RESUME-NOTE.txt）。

### R4-21 K6_SMOKE 压测负载模型缺陷：每迭代轮换 uid 触发 BCrypt 登录风暴 + 中毒 VU 正反馈雪崩（成功率 0.735→1.0，p95 四轮 6524→161ms）

- 现象：R4-20 全链绿后 K6_SMOKE 在 20 TPS 恒定到达下大面积 504/10010/10008；
  定位到 user-service cgroup 持续被 CFS 节流（nr_throttled 2213、throttled 466s，
  实测需求 2.16 core 超过 2 核 limit），但稳态在飞迭代仅需几十个 VU——容量并非真不足。
- 根因（压测模型，非被测系统）：constant-arrival-rate 下脚本用
  `uid=(iterationInInstance % USER_POOL)`，每个迭代轮换用户 → tokenCache 永不命中 →
  恒定 ~20 次/s BCrypt（hutool，cost=10，空闲登录 90ms，生产正确设置不可降级）打满
  user-service；失败迭代不缓存 token，中毒 VU 每迭代重新验密；慢迭代导致 k6 扩 VU
  （200→232），每个新 VU 首迭代再发一次登录——「换 uid→验密→节流→超时→扩 VU→
  更多首登」正反馈，marketing CB 被打 OPEN 573 次。
- 修复（deploy/loadtest/k6-order.js + final-acceptance.sh）：
  1. uid 改为 VU 生命周期固定 `((vuIdInTest-1)%USER_POOL)+1`，迭代间复用 token；
  2. warmup/smoke 合为单场景（VU 运行时/tokenCache 跨段共享），{phase:warm|measure}
     分段，阈值只统计 measure：order_success_rate{phase:measure}>0.99、
     order_create_latency_ms{phase:measure} p95<800；
  3. setup() 受控预登录（http.batch 每批 8、批间 300ms≈27 登录/s、失败批补一轮、
     校验 ≥90%，SMOKE_PRELOGIN_USERS 默认 160）+ 预建收货地址（存量用户达 20 个
     地址上限时走列表首条兜底，非缺陷）；
  4. loginToken 退避重试 [0,0.2,0.8,2]s，401/429 不重试，无永久负缓存；
  5. 验收门 preAlloc/maxVUs 收敛 120/160（预热池以内），VU 超池会复活首登风暴。
- 四轮实证（同一 20 TPS 口径）：成功率 0.735→（v2）登录失败 348→（v3）预登录
  160/160、成功率过线但 p95=1725→（v4）p95=1167、3/1750 失败→（v6 清洁环境）
  p95=161.5ms、max=984ms、0/5400 失败、实际到达 19.26/s。
- 尾延迟归因（关键反例）：v4/v5 的 p95 1.1–2.8s **不是容量问题**。v5 期间
  order 服务端 POST /orders 全窗口 max=0.70s（5400 次，无慢 SQL、Threads_running
  常态 2–6、GC max 95ms 无 major），尾延迟来自：①Docker VM 被 macOS 内存回收
  重启后 16 Pod 全冷启动 + broker 积压（v5 单 pod marketing is OPEN 936 次、
  12:22–24 releaseMarketing 失败 1060 次）；②本会话反复诊断跑积累的数千笔订单
  15 分钟超时取消波（秒杀单 orderType=4 超时 15 分钟，见 PayTimeoutPolicy）在
  测量窗内扇出 3 倍释放调用。规范十二门链中 K6_SMOKE 只跑一次且其订单在
  窗口结束后才到期，结构上免疫；重复复跑须先等取消波排空（本次以
  outbox due=0 + 近 2 分钟 0 次释放失败为静寂判据）。
- 生产侧残留登记：下单路径含约 10 次串行 Feign 调用，尾延迟对下游任何降级高度
  敏感（CB OPEN 即 5%+ 尾）；整集群同时冷启动（全量宕机恢复）的重放风暴
  R4-20 的单 Pod 宽限不覆盖，列入残留（多可用区滚动/集群级流量预热）。

### R4-22 k8s JDBC URL 缺 allowPublicKeyRetrieval=true：MySQL 重启后七服务全部 CrashLoopBackOff（Docker VM 内存回收重启实证）

- 现象：Docker Desktop VM 被 macOS 内存回收重启（容器自拉起），MySQL 重启后
  7 个业务 Deployment 全部 CrashLoopBackOff，根因异常
  `caching_sha2_password` → `Public Key Retrieval is not allowed`；
  首跑 K8s 部署之所以侥幸成功，是 MySQL 内存态 fast-auth 缓存尚存。
- 根因：10-services.yaml 的 7 条 SPRING_DATASOURCE_URL 只带 useSSL=false，
  缺 allowPublicKeyRetrieval=true；MySQL 8 默认 caching_sha2_password 在
  非 TLS 首连/服务端重启丢失快认证缓存后必须走 RSA 全量认证。生产 useSSL=true
  走 TLS 时该参数无副作用，因此无条件追加是安全的。
- 修复：7 条 URL 统一追加 `&allowPublicKeyRetrieval=true`（10-services.yaml），
  注释说明缓存失效机理；重新 apply + 滚动重启，七服务全部 Running。
- 验证：MySQL 已重启的前提下全量恢复，ha-check.sh 再跑 **HA PASS=74 FAIL=0**；
  同套证据目录续跑 KIND_DEPLOY 阶段固化本清单（幂等 apply 无二次滚动）。

### R4-23 两个内部控制器漏标 @Anonymous：MQ/调度线程调 /inner 恒 401，营销资源永不释放 + 毒化共享熔断（资金/库存正确性）

- 现象：R4-21 尾延迟取证中发现取消波（数千笔秒杀单 15 分钟超时）期间 order 两
  Pod 13:00–13:02 共 1,504 次 `CircuitBreaker 'shop-marketing-service' is OPEN`、
  4,662 次 OrderResourceReleaser 失败，异常栈
  `BizException: 下游服务不可用: shop-marketing-service at …OrderResourceReleaser.releaseAll(:45)
  ← OrderOperateServiceImpl.doCancel(:127) ← timeoutCancel(:79) ← PayTimeoutListener.onMessage(:44)`；
  而 marketing 自身日志 0 ERROR——其 prometheus 指标给出实锤：
  `http_server_requests_seconds_count{uri="/inner/marketing/release",status="401"}=560.0`
  （同控制器 /lock 5,250 次、/calculate 5,390 次全 200）。
- 根因（双层内部鉴权的标注缺口）：框架两层拦截器——InternalTokenInterceptor
  （order=0，仅拦 /inner/**，校验 X-Internal-Token）+ AuthInterceptor（order=1，
  拦 /**，非 @Anonymous 必须有 X-User-Id）。前台线程经 Feign 调用时
  FeignRequestInterceptor 的 loginUserPropagationInterceptor 注入 X-User-*；
  但 MQ 消费/调度线程 UserContext 为空，只注入 X-Internal-Token，于是通过
  内部令牌层后仍被 AuthInterceptor 判 401。六个内部控制器中四个
  （ProductInnerController/InnerUserController/InnerOrderController/InnerAftersaleController）
  已类级 @Anonymous，**InnerMarketingController 与 PayInnerController 漏标**——
  401 不是偶发而是必然，只是前台流量从不走这两个调用点才长期未暴露。
- 后果（生产正确性，非仅观测噪声）：
  1. 超时取消单的营销资源（优惠券归还、促销预占释放）**永不释放**——券被永久
     锁死、促销预占库存失真，属资金/库存正确性缺陷；
  2. 每次 401 都计入按 Feign client 名共享的 Resilience4j 熔断器，取消波扇出
     （每单 product/marketing/user 三次释放）在分钟级内把 marketing CB 打 OPEN，
     连坐前台下单的 calculate/lockPromotion 调用，制造 5%+ 尾延迟；
  3. PayInnerController 同病：拼团失败已支付退款由 GroupbuyOrderFlowServiceImpl:197
     在 MQ 消费线程调 `payClient.refund`，恒 401 即退款发不出去。
- 修复（两处类级注解 + import，与其余四个内部控制器同款）：
  `InnerMarketingController`、`PayInnerController` 类上加 `@Anonymous`
  （`com.shop.framework.web.Anonymous`）。**鉴权纵深不降级**：
  InternalTokenInterceptor 仍对 /inner/** 强制校验 X-Internal-Token
  （shop.internal.token，prod 经环境注入），@Anonymous 只豁免用户登录层；
  @AuditLog 切面在无用户上下文时本来就降级为 userType=SYSTEM/userId=0
  （AuditLogAspect:100-109），MQ 退款审计行语义不变。
- 回归测试（5 个新断言全绿）：marketing/pay 各一个注解存在性测试
  （InnerMarketingControllerAnonymousTest / PayInnerControllerAnonymousTest）；
  framework 新增 AuthInterceptorTest 三例锁死「类级 @Anonymous 无 X-User-Id 放行 /
  非匿名无用户头 401 / 非匿名有用户头放行」，防拦截器判定与标注两侧任一回归。
- 全量清查：6 个 @FeignClient（order/pay/aftersale/user/product/marketing）
  path 全部是 /inner/**，对应 6 个内部控制器现已全部 @Anonymous；
  settlement 无入站内部控制器（仅 MQ 消费），无同类缺口。
- 验证：同一套最终源码 → jar → 镜像重跑 final-acceptance.sh 十二门
  （EVIDENCE_DIR=deploy/loadtest/evidence/final-20260920-102225）；
  修复有效性实证：重跑后 marketing /inner/marketing/release 出现 status=200、
  取消波期间 order 0 次 marketing is OPEN。

---

## R4-24　MQ 消费幂等表单列 event_id 唯一键 → 同 topic 多消费组扇出静默吞保证金到账（资金正确性）

**级别：生产级资金正确性缺陷（静默丢失，无错误痕迹）。** 发现于 R4-23 修复后同一套
最终产物十二门复跑：E2E 59 例中唯一失败
`SettlementE2ETest.depositPayAndRecords:56 等待超时(20000ms)：保证金余额增加 100000 分`。

### 现象与事实链（2026-09-20 13:30 窗口）

- 支付侧正常：mock 回调验签落单，ORDER_PAID 事件
  `ec1c2930a61f4007b773627bd83b26b3`（payNo=P26092000000000075，payScene=4，
  orderNo=DP2609209523040256，100000 分）发出。
- cg_sett_paid（ClearingService）13:30:17 收到并按设计登记后直接 ACK
  （日志「保证金缴费支付事件 payScene=4，清算域直接ACK不落清算单」）。
- cg_sett_deposit_pay 一侧：broker stats.log 实证该组同分钟 12 次 GET/8476B/**12 次
  ACK**，但应用日志零分发（jstack pool-3 20 线程总 CPU 26.48ms 全空闲）、零
  mq_consume 流水、deposit_log 停留 status=10、商户保证金余额不增、零任何 ERROR。
- 稳态隔离复跑却通过（13:49 新事件正常入账），表现为偶发 flake。

### 错误假设的排除（取证过程）

1. 非 R4-23 引入（仅放宽 /inner 鉴权；settlement 无入站 inner 控制器）。
2. 非消费者未注册：broker 侧连接/订阅/心跳/GET/ACK 统计齐全。
3. 非业务异常/死锁：零 ERROR、jstack 无 BLOCKED。
4. **曾重点怀疑 rocketmq-client-java 5.0.7 POP 消费在启动追赶/assignment 抖动窗口
   ACK 但不回调**——下载 5.0.7 sources 逐行核实：标准消费路径
   `ProcessQueueImpl.onReceiveMessageResult → StandardConsumeService.consume →
   ConsumeService.consume 向 RocketmqMessageConsumption 线程池 submit ConsumeTask →
   ConsumeTask.call 调 messageListener.consume → 成功后 onSuccess → eraseMessage →
   ackMessage`；丢弃路径 discardMessage 走 **nack**（重试非 ack），ProcessQueue.drop
   只停新接收不 ack 缓存消息。即 broker 侧 ACK 只可能来自 listener 已返回 SUCCESS。
5. 排除「同组第二个 JVM 偷消费」：ps 实证窗口内 settlement 仅一个 JVM（pid 79992）。

结论收窄：listener **确实执行并返回了 SUCCESS，但业务在落任何痕迹前静默 return**。

### 根因

`shop_order_paid` 被两个独立消费组订阅（设计上的扇出）：

| 消费组 | 服务 | scene=4 行为 |
|---|---|---|
| cg_sett_paid | ClearingService:73 | **无条件先 tryRecord**，随后守卫直接 ACK |
| cg_sett_deposit_pay | DepositPaySettlementService | tryRecord 后做 CAS 10→20 + 保证金入账 |

两组共用 settlement 库 `t_sett_mq_consume`，而表唯一键是
**`uk_event_id(event_id)` 单列**。`MqConsumeMapper.insertIgnore` 对任一键冲突都返回
0：cg_sett_paid 先提交后，cg_sett_deposit_pay 的 INSERT IGNORE 命中同一 event_id
返回 0 → `tryRecord` 判重 → listener `return` → 框架 ACK。保证金缴费单永久停留
status=10、余额不增、无流水无日志、broker 不重投。两组谁先落库纯竞态（启动追赶期
概率更高），故 E2E 时好时坏；**HA 每次滚动发布/扩容都可能让商户已缴保证金静默不入账**。

### 修复（两道，纵深）

1. **幂等粒度修正**——`sql/settlement/V7__settlement_r4_24.sql`（information_schema
   守卫、幂等可重复执行；V6 版本号被 sql/common 七库 outbox UK 脚本占用故取 V7）：
   DROP `uk_event_id`，ADD `uk_event_group(event_id, consumer_group)`。同一事件扇出
   多组各自独立登记，组内重投仍被同一行挡住。全量清查其余六服务：各自 mq_consume
   表所在库内同一 topic 均只有一个消费组，不存在同类碰撞，无需迁移。
2. **对账兜底 Job**——`DepositPayRecoveryJob`（@Scheduled 60s fixedDelay +
   @SchedulerLock 集群单节点；参数 shop.settle.deposit.pay-recovery-*-* 可调）：
   扫描「建单超 90s 在途宽限、pay_no 已回写、仍 status=10」的缴费单，以**支付域
   status=30 为唯一资金事实源**回查，orderNo 与金额分毫不差才合成确定性
   eventId=`rec-<payNo>` 的 scene=4 到账事件，直接驱动原
   DepositPaySettlementService 入账事务（mq_consume/CAS/流水 UK 三重幂等、余额变更、
   P0-1 补扣钩子全部复用，路径与正常 MQ 完全同构）；支付未成功→touch
   last_query_time 降频复查（废弃支付意向不占满批次、不阻塞新单）；orderNo/金额
   不一致→ERROR 挂起人工核对、**永不自动补账**；Feign/补账异常不 touch，下轮重试。
   把「静默丢失」从语义上彻底变为「最终一致」，RTO ≤ 在途宽限 + 定频间隔。

### 验证（同一套产物，2026-09-20）

- 迁移：apply-sql.sh 幂等执行 V7 成功，`SHOW INDEX` 实证 uk_event_id 已删、
  uk_event_group(event_id,consumer_group) 已建。
- **历史数据自愈实证**：新 settlement jar（R4-24）启动后 Job 首轮（14:42:13）即自动
  补做 **3 笔**历史被静默吞掉的保证金（DP…0816/P…018、DP…7104/P…031、本次 E2E 的
  DP…0256/P…075），三笔均有「保证金缴费到账入账成功」日志、status 全部 10→20、
  商户余额 1014415 → 1314415（+300000 分毫不差）、rec- 幂等行齐全、窗口零 ERROR；
  复跑第二轮无 stuck 行（扫描结果 0）。
- **正常链路实证**：全新保证金缴费（14:47:24，event 2d7cc6cf…）两组
  mq_consume 行并存（cg_sett_paid + cg_sett_deposit_pay），RocketMQ 消费线程正常
  打印入账成功。
- 测试：新增 DepositPayRecoveryJobTest 7 例（成功补账且事件字段/eventId 断言、
  未成功 touch 不补账、orderNo 不一致/金额不一致 挂起、RPC 异常不 touch、
  补账事务抛错不 touch、空 Result 不 touch）；DepositPaySettlementServiceTest 5 例、
  MqConsumeServiceTest 2 例全绿；全量 shop-e2e **59 例 0 Failures 0 Errors 4 Skipped**。
- 收口：同一套最终源码 → jar → 镜像重跑 final-acceptance.sh 十二门
  （EVIDENCE_DIR=deploy/loadtest/evidence/final-20260920-102225，网关 8090）。

---

## R4-25　七库 outbox 统一 UK 下「同单号多次同 topic/tag 事件」一族五项碰撞（事务回滚 / 静默丢失 / 重复告警）

### 统一机理

`sql/common/V6` 给七库 t_mq_outbox 建了统一 UK `uk_topic_tag_bizkey(topic, tag, biz_key)`。
而 OutboxPublisher 在业务事务内做的是**纯 INSERT**（撞 UK 抛 DuplicateKeyException，
整个外层事务回滚），outbox relay 投递成功后只把 status 置 1、**行永不删除**。于是任何
「同一笔业务单在其生命周期内多次发出同 topic/tag 事件」的路径，第二次起必然撞 UK：

- 调用方未 catch → 资金/库存主事务整体回滚（数据正确性事故）；
- 个别发布点被宽泛 catch → 事件静默丢失、旁路动作（告警/通知）永不发出；
- 定时重扫类发布每天/每轮重复撞键，告警风暴或日志噪声，且多节点竞争时行为随机。

修复范式（全族统一）：**bizKey 追加业务维度后缀使每行天然唯一；消息体 bizNo 保持
裸业务单号不变（消费侧幂等口径与下游契约不动）；消费侧按消息体随机 eventId 或业务
字段幂等**。重复告警类再配持久 CAS 边沿闸门，资金发布点再兜 DuplicateKeyException 降级。

### 五项缺陷与修复

1. **积分变更（shop-user）——同单多阶段事件撞键回滚积分事务**。冻结、扣减、退款退回
   对同一订单各发一条 POINTS_CHANGED，bizKey 都是裸 orderNo。改为 `bizNo#ct{changeType}`；
   到期积分聚合按用户/日发一条，同用户同日超过 BATCH_SIZE=200 笔 grant 跨分页时再追加
   `#p{pageCursor}`（最后一条 grant id 游标）。
2. **秒杀多 SKU（shop-marketing）——一个订单含多个秒杀 SKU 时 LOCK/DEDUCT/RELEASE
   每阶段 N 条事件全撞裸 orderNo，且限购按订单计数在多 SKU 模型下语义错误**。
   V9__seckill_multisku.sql 重设计：t_seckill_order 每 (order_no, sku_id) 一行
   （uk_order_sku，存量旧单列 UK 经 information_schema 守卫替换）；限购迁到新表
   t_seckill_user_buy：`claim` 行锁条件占件（total_qty+? <= per_user_limit），首单
   行不存在时 insert，并发负方撞 UK 后重试 claim；取消释放 `releaseQty` 用
   GREATEST(total_qty-?,0) 回减。服务侧锁定走一次 Lua 批量预占、逐 SKU 锁 DB/落单行，
   任一行失败回补全部已占 Redis；支付扣减/取消释放逐 SKU 执行；事件键
   `orderNo#sku{skuId}`。订单行 UK 冲突按「重复提交」友好报错并回补 Redis。
3. **评价行为（shop-product 域评价链路）——同一笔订单/商品产生多种评价行为事件**。
   bizKey 改 `commentNo#b{behaviorType}`，消息体仍为裸 commentNo。
4. **库存预警（shop-product）——同一 SKU 低于阈值期间被定时任务/多节点重复 fire，
   第二次起撞 UK 且每次库存微动都重复告警**。V7__stock_warning_dedup.sql 加
   t_product_sku.low_stock_alerted；StockServiceImpl.afterStockChanged 边沿触发：
   available<=threshold 时 `casLowStockAlertOn(skuId)==1`（0→1）才 fireStockWarning，
   恢复到阈值以上 casLowStockAlertOff 复位（下次跌破可再告一次）；事件键
   `skuId:warningId`，event.bizNo 仍裸 skuId（运营侧订阅口径不变）。
5. **保证金告警 DEPOSIT_ALERT（shop-settlement）——四路告警（罚款 FINE、扣款赔付
   CLAW、清退人工挂起 HANG、余额查询失败 FAIL）bizKey 全用裸 merchantId，商户终生
   只可能成功发出第一条；HANG 还会被每日 03:00 清退重扫与多节点竞争重复触发**。
   V9__settlement_r4_25_deposit_alert_dedup.sql 加 t_sett_deposit_log.hang_alerted；
   四路改实例级键 `FINE:{fineLogNo}` / `CLAW:{refundNo}` / `HANG:{log40LogNo}` /
   `FAIL:{refundLogNo}`（前缀与历史裸键天然隔离，存量行无需回填）；HANG 发布前
   `casHangAlerted`（WHERE … AND hang_alerted=0）持久 CAS，输掉即静默跳过——每笔
   log40 终生只告一次，同时治每日重扫与多节点竞争；DepositAlertEvent 增
   alertType/refNo 运营维度；publishAlert 最外层 catch DuplicateKeyException 仅
   error 日志，保证单条 outbox INSERT 失败绝不回滚资金主事务。

### 连带修复（前窗口改主代码后未同步的测试，mock 下长期假绿/未编译）

- AccountServiceImplTest：3 处旧裸键断言改 `#ct3/#ct2/#ct5`，新增同用户跨分页聚合
  事件键 `#p0/#p200` 回归。
- GroupbuyOrderFlowServiceImplTest：拼团续期 publishDelay 3 处键对齐 `#gbrenew`。
- PresaleServiceTest：预售尾款超时键对齐 `#final`；新增 PresaleEventListenerTest
  5 例——非 CANCEL 忽略、body eventId 优先、**body 缺 eventId 时回退
  MqConsumeContext.currentEventId() 归一化上下文**（延时行键含 #final 也能各自幂等）、
  重复不执行 cancelByOrderNo、descriptor 三断言。
- 售后两测试类：AftersaleEventPublisher.publish 已为 6 参（末参 transitionLogId），
  verify 改 anyLong()；StatusLog mock insert 不回填 id 致 29 处 NPE，加 doAnswer
  雪花顺序回填（严格桩模式 @BeforeEach 通用桩必须 lenient）。注意 StatusLog 物理目录
  与包名错位（实体在 com.shop.aftersale.aftersale.entity），按包声明引用。
- SeckillServiceTest 全面重写为多 SKU 模型（批量 Lua 一次、逐行落库、逐键事件、
  计数占件/UK 冲突重试/超限回补、释放总件数回减、键缺失按 DB 重建）。

### 验证（同一套产物，2026-09-20）

- 迁移：apply-sql.sh 幂等收敛，information_schema 实证 t_seckill_order 仅
  PRIMARY + uk_order_sku(order_no,sku_id)、t_seckill_user_buy 存在、
  t_product_sku.low_stock_alerted / t_sett_deposit_log.hang_alerted 均为 tinyint。
- 单测：全 reactor per-class 合计 **2530** 全绿，含 SettlementRepairRealDbTest
  真实库 5 例（中间件 Up 后）；保证金 DepositServiceTest 24 例（新增 CAS 输掉静默、
  两笔罚款键共存、撞 UK 主事务存活三回归），售后 110、营销 265。
- 收口：同一套最终源码 → jar → 镜像重跑 final-acceptance.sh 十二门
  （EVIDENCE_DIR=deploy/loadtest/evidence/final-20260920-1800-r425，网关 8090；
  旧目录 final-20260920-102225 十二门全绿证据保留归档）。
- **结果（2026-09-20 18:32）**：十二门一次全绿——UNIT 2530（含
  SettlementRepairRealDbTest 5/5）、PACKAGE/MIDDLEWARE（迁移幂等）/HOSTAPPS、
  E2E 59/0/0/4、CHAOS PASS=30 FAIL=0、KIND_IMAGE/KIND_DEPLOY、HA PASS=74 FAIL=0、
  K6_SEED、K6_SMOKE 5400 迭代 0 失败 order_created=5376 avg=168.5ms p95=389.3ms
  （阈值 800ms）、K6_OVERSELL 40 尝试 winners=losers=paidConfirmed=20
  「oversell assertion PASSED」+ DB 对账 orders_status20=20 / pay_status30=20。

---

## R4-26　R4-25 同族收口后的两处预防缺陷：售后跨轮旧消息时效越权 + product 消费 eventId 空键

R4-25 十二门全绿后，对「重复/乱序消息」主题做收尾审查时发现两项；均为真实代码路径，
非风格项。R4-25 轮次键解决了 outbox 写入侧的碰撞与多轮消息的**登记/投递/幂等独立性**，
但消费侧只做了消息级幂等，缺少「消息是否仍属于当前轮次」的归属守卫——本项补齐。

### R4-26a 售后旧轮超时消息可在第二轮窗口提前自动流转（时效正确性）

**可达场景**：买家申请售后进入待审核 10（审核窗 2 天），登记 audit 延时消息 R1 与
60s 扫表双保险。商家拒绝（55），买家在 R1 到期前修改重提，售后单回到 10 并获得新的
审核截止点 auditDeadline=R2，登记 R2 延时消息。此时 R1 消息若因 broker 投递重试
（最多 16 次退避）、消费者停机追赶、消费积压等原因在第二轮窗口内才到达
AftersaleTimeoutListener：

1. R4-25 的消息级幂等挡不住——R1 与 R2 的确定性 eventId/outbox 键本就不同；
2. autoApprove 的状态守卫（`status != 10 即返回`）也挡不住——售后单此刻恰好在 10；
3. 于是系统在买家**第二轮审核窗刚开启**时即自动同意（甚至走到退款），买家被提前
   最多 2 天（receive 3 天、exchange_ship 5 天同理）剥夺等待商家处理的时限。

扫描作业路径无此问题：job 每轮用数据库当前 deadline 现算 roundKey，与当前轮天然一致。

**修复**（AftersaleTimeoutServiceImpl.dispatchTracked，落消费流水之前）：

- audit/receive/exchange_ship 三类有轮次维度的消息，按 aftersaleNo 查当前售后单，
  取对应 deadline 现算 roundKey，与 msg.roundKey 不等 → warn 后直接 return（ACK 丢弃，
  不写流水、不流转）；
- 当前 deadline 列为 null（状态已推进、该等待窗不存在）而消息带轮次键 → 必然旧轮，丢弃；
- insurance（每单一次）/ evidence（单次举证）无轮次语义，不校验、不查售后单；
- 消息无 roundKey（R4-25 前登记的历史存量延时行）：无法判别归属，保持 R4-26 前语义
  放行，继续依赖状态机守卫（只可能在「部署前登记 + 部署后恰好重提」的窄窗并存，
  且 R4-25 后新登记消息全部带键）；
- 售后单查不到：放行交业务 requireAftersale 既有异常→重试/DLQ 路径，不吞异常。

测试：AftersaleTimeoutServiceImplTest 15→19 例（旧轮 audit 迟到丢弃且零落库零流转、
当前轮正常落库且 eventId 带 -R 后缀、当前 deadline 清空旧 receive 丢弃、
evidence 不查售后单）；构造器新增 AftersaleOrderMapper 依赖（唯一调用点同步更新）。

### R4-26b product 消费幂等 eventId 空白键（防御加固）

StockServiceImpl 五个 topic 消费入口统一 tryRecord(event.getEventId(), …)，eventId
来自消息体。框架 EventNormalizer 已保证「header → body → noid 合成」的归一化
eventId 并在 MqConsumerRegistrar:308 对所有 listener 绑定 MqConsumeContext，但 product
私有 tryRecord 只读消息体、不读上下文：正常路径（BaseEvent 构造即随机 UUID）恒非空，
历史无信封/非 JSON 消息体场景下 eventId 为空白，会以 null/空串写消费记录表。
修复：body eventId 空白时回退 MqConsumeContext.currentEventId()；两者皆空（非 MQ
线程误调用）fail-fast 抛 IllegalStateException，不用空键污染幂等表。

测试：StockServiceImplTest 新增 2 例（空白 body eventId + 绑定 noid 上下文 → 以上下文
id 落库并正常锁库存；空白且无上下文 → IllegalStateException 且不写库）。

### 验证与收口

- shop-aftersale（114 全绿）、shop-product（232 全绿）及 -am 上游模块 mvn test 通过；
  验收 UNIT 门全 reactor 13 模块 BUILD SUCCESS（2536 例 0 failure 0 error）。
- **同一套最终源码 → jar → 镜像 final-acceptance.sh 十二门全绿（2026-09-20 19:24:38，
  EVIDENCE_DIR=deploy/loadtest/evidence/final-20260920-1840-r426，网关 8090）**：
  E2E 59/0/0/4、CHAOS PASS=30 FAIL=0、HA PASS=74 FAIL=0、K6_SMOKE 测量窗 0 失败
  exit=0（warmup 50 条 10008 冷启动噪声，全量聚合 p95=1865ms 非阈值口径，
  详见 PERF_REPORT §2.0b）、K6_OVERSELL winners=losers=paid=20、DB 对账 20/20。

## R4-27　七域消费幂等 eventId 归一化收口（R4-26b 同族缺陷的全库清查与统一）

R4-26b 只修了 product 一处「消费落库只信消息体 eventId」。本轮对**全部七个业务库的所有
消费幂等落库点**做逐点清查，结论：框架侧归一化（EventNormalizer header→body→noid 合成、
MqConsumerRegistrar 对所有 listener 绑定 MqConsumeContext）早已统一，但消费侧采用不一致——

| 域 | 落库收口点 | R4-27 前 |
|---|---|---|
| order | MqConsumeService:31 | 已用 `MqConsumeContext.currentEventId()`，合规 |
| user | MqConsumeServiceImpl:28 | 已用上下文，合规 |
| product | StockServiceImpl.tryRecord | R4-26b 已修，合规 |
| marketing | MqConsumeTemplate.runOnce | **只信 body** |
| settlement | MqConsumeService.tryRecord（6 调用点共用） | **只信 body** |
| pay | MqConsumeSupport.firstConsume | **只信 body** |
| aftersale | AftersaleMqServiceImpl 三站点（ORDER_SHIPPED / ORDER_CONFIRMED / REFUND_SUCCESS） | **只信 body** |

**风险（为何不是纯风格项）**：消费记录表 event_id 为唯一键去重列。历史无信封消息、跨版本
反序列化丢字段、或非 JSON 老格式消息到达时，body eventId 为 null/空白：(1) 空白键行的去重
语义依赖各库列约束（NULL 不撞 UK → 重复消费；空串撞 UK → 同 topic 多条不同无键消息互相
碰撞，后到者被静默 ACK 吞掉，与 R4-24「多组共表」同族的静默丢失形态）；(2) 框架已经把
正确的归一化 eventId（含确定性 noid 合成，保证同消息重投合成结果一致、命中同一流水行）
放进 ThreadLocal，消费侧不用等于框架能力在四域空转。

**统一范式（四点完全一致）**：body eventId 非空白 → 用 body（活路径 BaseEvent 构造即
随机 UUID，子类 @Builder 经隐式 super() 仍执行字段初始化器，已由既有 17 个 aftersale
用例不传 eventId 全绿实证）；空白 → `MqConsumeContext.currentEventId()` 回退；两源皆空
（只可能是非 MQ 线程误调用的编程错误）→ 抛 IllegalStateException fail-fast，不写库。

四处实现：

1. marketing `MqConsumeTemplate`：新增 static `resolveEventId(bodyEventId, topic)`，
   runOnce 的 insertIgnore 第 2 参改走它。该模板是 marketing 全部 listener 的唯一幂等
   收口点（券/秒杀/拼团/预售/抽奖/新人礼/注册事件等），一处覆盖全域。
2. settlement `MqConsumeService`：同款 static resolveEventId 包进 tryRecord，一处覆盖
   6 个调用点（DepositPaySettlementService、ClearingReverseService、ClearingService×3、
   ShortfallWorkOrderServiceImpl；6 点实参全为 BaseEvent.getEventId() 不引入 NPE）。
   shop_order_paid 双消费组已由 R4-24 的 uk_event_group(event_id,consumer_group) 隔离。
3. pay `MqConsumeSupport.firstConsume`：body 空白时取上下文并 **event.setEventId(ctx)
   回填事件对象**，使随后构造的 MqConsumeLog.payload JSON 与落库 eventId 一致；双空 fail-fast。
4. aftersale `AftersaleMqServiceImpl`：新增 private static resolveEventId（复用类内既有
   hasText），三个消费入口的 insertIgnore 第 1 参全部改调。

**测试（新增 14 例，全绿）**：

- marketing MqConsumeTemplateTest 新建 4 例：body 非空直接用并执行业务、空白+绑定 noid
  上下文则以上下文 id 落库、rows=0 重复跳过不执行、双空 IllegalStateException 且不写库；
- settlement MqConsumeServiceTest 2→5 例：空白回退落库、双空异常（消息含 eventId 文案）、
  body 与上下文并存时 body 优先；
- pay MqConsumeSupportTest 新建 4 例：body 优先落库、重复 false、空白回退并断言事件体被
  回填为上下文 id、双空 fail-fast 不写库；
- aftersale AftersaleMqServiceImplTest 17→20 例：onOrderShipped 空白回退上下文落库、
  onRefundSuccess 空白（eventId=null）回退并正常走完成事务、onOrderConfirmed 双空
  fail-fast 且不触达窗口表；@AfterEach 统一 MqConsumeContext.clear() 防 ThreadLocal 泄漏。

四模块 -am mvn test BUILD SUCCESS：marketing 274 / pay 154 / settlement 202 /
aftersale 117，0 failure 0 error。

### 验证与收口

- 同一套最终源码 → jar → 镜像 final-acceptance.sh 十二门复跑
  （EVIDENCE_DIR=deploy/loadtest/evidence/final-20260920-1943-r427，网关 8090）：结果回填。

## R4-28　背靠背复跑时上轮 outbox 积压补投致 E2E 假失败（验收设施缺陷）

### 现象（r427 首跑 E2E 门，唯一失败例）

`SettlementE2ETest.cycleS_dueDateAfterConfirm`：确认收货后轮询清算 stage=20，20s 超时；
59 例仅 1 失败。失败订单 260920012000040600 的 stage=20 在超时后 **98s（19:55:08）实际
达成**——系统最终一致，不是业务缺陷。settlement 消费侧全程零异常。

### 取证（DB 时间线，非推测）

shop_order.t_mq_outbox 该单六行事件：

| topic | create_time | update_time（relay 置位） |
|---|---|---|
| order_created | 19:52:49.571 | **19:55:06.390（+137s）** |
| order_shipped | 19:52:50.667 | 19:55:08.846 |
| order_confirmed | 19:52:50.687 | 19:55:08.851 |

按秒聚合 19:50–19:56 的 relay 置位批次：宿主应用 19:48 启动后，relay 以每 1–3s
一批（30–100 行）的速度连续补投 **5363 行 create_time 早于 19:48 的旧行**
（最早 19:20，来自上一轮 r426 kind 形态 K6/E2E 窗口登记、kind 静默时仍 status=0
的事件；含 deliver_at 在启动后才到期的延时行）。OutboxRelayJob 为 2s/轮、
LIMIT 100、同步 FIFO 发送，E2E 新事件排在 5k 积压波之后——消息在 order 侧延迟
137s 才进入 broker，settlement 收到后毫秒级处理完成（19:55:08 登记/分账日志）。

### 判定

- 系统行为正确：R4-19 形态互斥停应用期间 outbox 行保留 status=0，应用重启后
  至少一次语义完整补投，零丢失、最终一致；
- 缺陷在验收设施：final-acceptance.sh 的 HOSTAPPS 就绪判据只有探针 UP，缺
  「消息子系统起始积压已排空」这一就绪维度，冷启动/背靠背场景 E2E 会与
  relay 补投波竞争，20s 业务轮询窗假失败；
- 与 R4-20（消费者冷启动宽限）同族：均为「启动追赶期」环境问题，一个在消费侧、
  一个在投递侧。

### 修复（仅验收脚本，无业务代码变更）

final-acceptance.sh 在 E2E 身份引导之前新增 **R4-28 七库 outbox 到期积压排空闲忙门**：

- 每 10s 汇总七库 `status=0 AND deliver_at<=NOW()` 行数，全 0 才放行（04b-outbox-drain.log
  每轮留证）；90 轮（15 分钟）未排空 die，判环境未恢复；
- 只拦到期行：deliver_at 在未来的延时消息（关单/自动确认等）不算积压，不拦正常业务；
- 首跑实证：自然排空后七库 due_pending=0 一次通过，E2E 59/0/0/4 BUILD SUCCESS，
  cycleS 例在窗口内正常通过。

### 验证与收口

- R4-27/R4-28 同一证据目录 final-20260920-1943-r427：UNIT/PACKAGE/MIDDLEWARE/
  HOSTAPPS 首跑通过；E2E 首跑暴露本项，修门后 ONLY_STAGES 从 E2E 续跑
  （R4-20 断链续跑机制复用同一目录，产物未变）；后续七阶段结果回填。

