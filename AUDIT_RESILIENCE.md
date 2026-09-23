# 分布式系统韧性审计报告

- 审计对象：`/Users/bytedance/bits/shop`（shop-gateway + user/product/marketing/order/pay/settlement/aftersale 共 8 个 Spring Boot 3.2.5 服务）
- 审计方式：只读静态核对（配置 + 代码 + DDL + 部署脚本），所有结论附「文件:行」证据
- 审计日期：2026-09-17

---

## 一、结论摘要

总体判断：**业务一致性的"骨架"质量高**（事务性 Outbox、消费幂等、CAS/UK、TCC 补偿、ShedLock 全覆盖、MQ 注册不阻断启动均已落地），但**运行时韧性的"皮肤"基本裸奔**——Feign、Druid、限流三处最基础的容错配置全部是框架默认值或空白，下游/中间件抖动时缺少任何一道防线，压测脚本（chaos）的流量模型又恰好绕开了所有薄弱点，形成"已验证零失败"的认知偏差。

阻断项 **7 条**，重要项 **11 条**，建议项 **10 条**。

七项审计维度覆盖矩阵：

| # | 审计维度 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | FeignClient 超时/重试/降级 | **阻断**：5 个契约全部裸注解，默认 10s/60s、不重试、JDK HttpURLConnection、无 ErrorDecoder/熔断；异常解包不统一，售后 3 处 null 被当成功 | 见 B2、B6 |
| 2 | 数据库（Druid/事务/CAS/UK） | **阻断**：Druid 全裸默认（maxActive=8、maxWait 无限）、无慢 SQL；pay/order 存在事务内远程调用大事务；CAS 与 69 处 UK 覆盖扎实；@Version 20 个实体 | 见 B1、B5、B7 |
| 3 | Redis/Redisson | **阻断**：读链路有软降级（chaos 零失败的真实原因），但锁/幂等/限流/订单号序列 fail-closed 且未被 chaos 覆盖；无连接池/重试显式配置 | 见 B4 |
| 4 | RocketMQ/Outbox | 重要：快慢车道、毒消息终态分类、25/26 消费者幂等设计完整；但 `consumeMaxAttempts` 是死配置、DLQ 无消费无告警、outbox 无 biz_key UK | 见 Z3、Z4、Z5 |
| 5 | 限流/幂等 | **阻断**：网关与 Sentinel 事实零限流，仅 6 个 @RateLimit 落点；@Idempotent 仅 3 处（设计内的支付回调用 DB UK 替代） | 见 B3 |
| 6 | ShedLock 定时任务 | 25 个 @Scheduled **全部**加锁，无漏加；但默认单线程调度器互堵、锁前缀无环境隔离 | 见 Z6、Z7 |
| 7 | 启动顺序依赖 | MQ 注册不阻断启动已实现且设计良好；DB initialSize=0 不阻断；Redis IdGenerator 有兜底；但 readiness 组未显式配置，默认组含 db/redis/mq，中间件抖动会摘流；Nacos 宕机行为未实测 | 见 Z8 |

---

## 二、阻断项（7 条，生产可致级联故障或资金/数据错误）

### B1. Druid 连接池全裸默认：maxActive=8、maxWait=-1（无限等待），无保活/校验/慢 SQL

7 个业务服务的 datasource 配置均只有 driver/url/username/password/type 五项，例如：
- `shop-order-service/src/main/resources/application.yml:10-15`（其余 6 个业务服务同构）
- 全仓 grep `initial-size|min-idle|max-active|max-wait|validation-query|keep-alive|remove-abandoned|time-between-eviction` **零命中**；无 stat/wall filter、无 `slow-sql-millis`、无 p6spy、无监控页、无手写 DataSource Bean。
- Druid 版本 `pom.xml:41`（1.2.22），靠 druid-spring-boot-3-starter 自动装配（`shop-framework/pom.xml:66-68`）。

实际跑的是 Druid 出厂默认：`maxActive=8`、`maxWait=-1`（拿连接无限期阻塞）、`initialSize=0`、无 testOnBorrow/validationQuery、无 keepAlive。后果链：
1. 与 B5 的长事务叠加——支付/退款/超时关单事务内含多个 Feign（默认读超时 60s），8 个连接会被瞬间占满；
2. 第 9 个请求在 `getConnection()` 上**永久排队**（无超时），Tomcat 线程随之挂死，故障不可自愈；
3. MySQL 断连（wait_timeout/重启）后无校验，首次借用拿到死连接直接报错；
4. 无慢 SQL 与连接池监控，故障发生时无任何可观测线索（Prometheus 暴露了端点但无 Druid 指标接入，见 Z10）。

修复方向：每个服务外部化一组生产参数（建议 initialSize=2/minIdle=5/maxActive 按容量评估 20~50、maxWait=3000ms、validationQuery=SELECT 1、testWhileIdle=true、keepAlive=true、timeBetweenEvictionRunsMillis=60s、minEvictableIdleTimeMillis=300s、removeAbandonedOnBorrow=true + timeout=300s），开启 stat filter 与慢 SQL 阈值（slow-sql-millis=1000），接入 micrometer/druid 监控并配连接池占用率告警。

### B2. Feign 零超时/零熔断/无连接池：默认 60s 读超时可拖垮全部工作线程

5 个契约接口全部是裸 `@FeignClient(name=..., path="/inner/...")`，无 configuration、无 fallback：
- `shop-api/src/main/java/com/shop/api/order/client/OrderClient.java:15`
- `shop-api/src/main/java/com/shop/api/pay/client/PayClient.java:28`
- `shop-api/src/main/java/com/shop/api/product/client/ProductClient.java:29`
- `shop-api/src/main/java/com/shop/api/user/client/UserClient.java:29`
- `shop-api/src/main/java/com/shop/api/marketing/client/MarketingClient.java:30`

配置侧证据：全仓 Java grep `Request.Options|Retryer|ErrorDecoder|connectTimeout|readTimeout` 零命中；所有 yml 无 `feign:` 段；`shop-framework/pom.xml:46` 仅有 spring-cloud-starter-openfeign，**没有** feign-httpclient/feign-okhttp 依赖 → 实际使用 JDK HttpURLConnection（无连接池、无长连接复用）。框架侧只有两个 RequestInterceptor（内部 token、身份头透传），无任何容错组件：`shop-framework/src/main/java/com/shop/framework/feign/FeignRequestInterceptor.java:22-48`。

因此全链路生效的是 Spring Cloud 默认值：connectTimeout=10s、readTimeout=**60s**、Retryer=NEVER_RETRY。结算/售后/支付超时等多跳链路（如 order→pay、aftersale→pay+product+user、settlement→order）任一下游慢响应，上游线程被占满 60s；Tomcat 默认 200 线程只需 ~200 个慢下游调用即可整站不可用（级联雪崩），且没有任何舱壁/熔断让核心链路（下单、支付）与非核心链路（评论、售后窗口）隔离。

重试风暴面评估：Feign 层 NEVER_RETRY，**不存在 Feign 无限重试风暴**；真正的放大面在 MQ 消费返回 FAILURE 后 broker 重投（见 Z4），但消费侧有幂等表兜底，风险可控。

修复方向：①引入 feign-hc/okhttp 启用连接池；②按下游重要性分 client 配 Request.Options（建议连接 2s、读 3~5s，支付渠道查询可单独放宽）；③接入 Resilience4j/Sentinel Feign 熔断舱壁（fail-fast 优于 60s 挂死）；④统一 ErrorDecoder 把 5xx/网络错误包成 BizException(DEPENDENCY_FAIL)，并统一 FeignResults（现在 order/pay 各有一份逐字重复的实现：`shop-order-service/.../support/FeignResults.java`、`shop-pay-service/.../support/FeignResults.java`）。

### B3. 网关层与 Sentinel 层事实零限流，6 个 @RateLimit 是全部防线

- 网关 `shop-gateway/pom.xml:19-28` 只有 gateway + nacos-discovery + loadbalancer，**无 sentinel、无 RequestRateLimifier 依赖**；`shop-gateway/src/main/resources/application.yml:19-61` 七条路由只有 StripPrefix=2，无 default-filters/限流过滤器；`JwtAuthGlobalFilter.java:84-102` 只做鉴权与头清洗。
- 7 个后端服务 yml（如 `shop-order-service/src/main/resources/application.yml:26-30`）仅配置 `spring.cloud.sentinel.eager=true` + dashboard:8858，**无规则、无 datasource**；全仓 `@SentinelResource` 使用数 = **0**。规则只能靠 dashboard 运行时手工推送，dashboard 重启/服务重启即丢失，等于无防护。
- 应用层自研 @RateLimit 共 6 个落点（Redis ZSET 滑动窗口）：`shop-user-service/.../auth/controller/AuthController.java:35`（注册 5/60s）、`:50`（登录 20/60s）、`shop-order-service/.../order/controller/OrderController.java:43`（下单 5/s）、`shop-marketing-service/.../coupon/controller/CouponCenterController.java:39`（领券 30/60s）、`shop-settlement-service/.../withdraw/controller/MerchantWithdrawController.java:38`（提现 5/60s）、`shop-marketing-service/.../activity/service/SeckillService.java:59`（秒杀 1/3s，唯一带 SpEL 维度键）。秒杀主场景只有限流没有网关层兜底。

修复方向：网关接入 Sentinel（spring-cloud-alibaba-sentinel-gateway）或 RequestRateLimimiter，配置全局限流 + 按路由 QPS 桶；后端 Sentinel 规则通过 datasource 持久化到 Nacos；秒杀/下单在网关层增加热点参数限流。注意 @RateLimit 自身依赖 Redis（见 B4），Redis 故障时限流本身也失效（fail-closed 返回 500，至少不会放行，但该保护无法替代网关层）。

### B4. Redis 宕机时关键写链路全部 fail-closed，chaos"零失败"结论存在流量覆盖差距

软降级（有代码依据，设计良好）：
- `shop-framework/.../idgenerator/IdGenerator.java:30-49`：@PostConstruct catch 异常→本机推导，启动不依赖 Redis；
- `shop-product-service/.../goods/.../SpuDetailCache.java:33-82`：client==null 与异常双兜底回源；
- `shop-marketing-service/.../activity/.../SeckillStockClient.java:95-198`：client 缺失降级 DB 条件更新（且键缺失时不 INCRBY 凭空造库存）；
- `shop-marketing-service/.../coupon/service/CouponService.java:85-108`、`shop-marketing-service/.../inner/MarketingAppService.java:71-98`：降级靠 DB UK/CAS 无锁执行。

硬依赖（构造注入 RedissonClient，Redis 宕即异常，无 try/catch 降级）：
- 订单号生成 `shop-order-service/.../support/OrderNoGenerator.java:33,67`：下单必走 Redis INCR，Redis 挂→**下单直接失败**；
- 幂等切面 `shop-framework/.../idempotent/IdempotentAspect.java:75,119`：setIfAbsent 抛异常→下单/充值/提现在 Redis 窗口全部 500；
- 限流器 `shop-framework/.../ratelimit/RateLimiter.java:41,57`：Lua eval 无 try/catch；
- 分布式锁模板 `shop-framework/.../lock/DistributedLockTemplate.java:19,48`（6 个使用点：PaymentServiceImpl:132、RefundServiceImpl:69、ClearingReverseService:83、WithdrawService:88、StockServiceImpl:102、SignInServiceImpl:60）。

chaos 脚本 `deploy/loadtest/chaos.sh` 的持续流量仅 GET `/api/product/products`（`:35` PRODUCT_PATH），该列表接口不碰 Redis（仅商品详情 SpuServiceImpl:358-363 用缓存）；脚本 :169 还明确把 Redis 宕机窗口的失败标注为"预期降级"，考核项只看恢复后成功与服务 UP（:179-198）。**写链路（下单/支付/幂等/限流/锁/秒杀）在 Redis 宕机窗口的行为没有任何流量验证**——真实表现是核心交易全部报错（fail-closed 本身优于超卖，方向正确，但"零失败"的验收结论不能外推到写链路）。

修复方向：①明确写入 RUNBOOK/验收口径：Redis 故障时交易链路降级为"拒绝服务但不超卖"，而非"零失败"；②补一个针对 POST 下单/支付的 chaos 场景验证 fail-closed 行为与恢复；③OrderNoGenerator 增加 DB 号段或雪花本地兜底（IdGenerator 已有本地推导能力，订单号可复用）；④为 Lettuce/Redisson 显式配置命令超时、重试与连接池（当前 yml 仅 `spring.data.redis.timeout=3000ms`，如 `shop-order-service/src/main/resources/application.yml:17-21`，无 password、无池化参数）。

### B5. 支付/退款/超时关单存在"事务内远程调用 + 分布式锁 + 逐笔循环"大事务

全仓 152 个方法级 @Transactional（无 readOnly、无 timeout），其中高风险点：

1. **支付扫单自调用大事务**：`shop-pay-service/.../payment/service/impl/PaymentServiceImpl.java:551` scanTimeout 为 @Transactional，:557 `this.activeQuery(...)` 是同类自调用——activeQuery（:498）自身的 @Transactional 被绕过，本意逐单独立事务实际并入外层一个大事务；每单内含 :516/:524 逐 ChannelFlow 渠道查询（N 笔 N 次远程）与 :544→:346 userClient.creditBalance。一单慢/失败→整批回滚、连接长占。
2. **退款事务内锁 + 双轮远程调用**：`shop-pay-service/.../refund/service/impl/RefundServiceImpl.java:67` refund（:95 retry 同构）：事务入口 :69 先取 Redisson 锁，:146 循环逐 split 执行 :197 `userClient.creditBalance` 与 :205 渠道 refund HTTP，:170 再一轮逐笔 CAS，:244 才 outbox。锁要到事务提交才释放，渠道 60s 超时期间连接、行锁、Redis 锁三者全占。
3. **支付创单事务内 Feign**：PaymentServiceImpl.java:95 createPayment 事务内 :105 orderClient.getByOrderNo；:132 锁内 :312 debitBalance、:274-275 逐 PayPart 渠道下单（当前 MockPayChannelClient 无真实延迟，换真实渠道前必须拆）。
4. **MQ 超时关单单事务 4 个 Feign（order 域最严重）**：`shop-order-service/.../mq/PayTimeoutListener.java:39` onMessage 开事务，随后 OrderOperateServiceImpl.java:89 payClient.getByPayNo、OrderResourceReleaser.java:36 userClient.releasePoints、:45 marketingClient.releasePromotion、:61 productClient.releaseStock 全部在同一事务内；任一慢调用拉长订单行锁/连接占用；远程成功但本地回滚时产生不一致窗口（靠消费重试 + 下游 bizNo 幂等兜底，最终一致但窗口真实存在）。
5. 售后域：`shop-aftersale-service/.../AftersaleServiceImpl.java:787` payClient.refund 挂在五个事务入口（audit/arbitrate/merchantReceive/autoConfirmReceive/autoConvertExchangeToRefund）的外层事务上（:756 的 REQUIRES_NEW 只保证退款单号先落库，不能消除远程调用）；:857 productClient.returnStock、:431 saleable、:621 getSku、:973 orderClient 均在事务内。
6. 其他大事务：`shop-user-service/.../account/service/impl/AccountServiceImpl.java:420` expireDuePoints 一个事务内 paged while 跑完全部到期积分 + :582 outbox；settlement WithdrawService auditBatch/remitBatch 单事务 500 笔（:147/:168）；pay ReconcileServiceImpl.java:62 单事务内远程拉账单 + 逐差异 insert，且 handleDiff :184 自调用致传播失效。

正面样板（修复时照此拆）：下单链路 `shop-order-service/.../order/service/impl/OrderCreateServiceImpl.java` 无 @Transactional，TCC 三步 Feign（:106/:114/:128）全在事务外，落库走独立 Bean `OrderPersister.java:49`（事务内仅 insert + outbox :69-71），补偿 :483/:491/:508 逆序且每步独立 try/catch；`shop-marketing-service/.../inner/MarketingAppService.java:82-93` 先 tryLock→代理调事务方法→finally 事务外解锁。

修复方向：按"锁包事务、远程调用移出事务、批处理拆独立 Bean 逐条短事务"重构 pay 三处与 PayTimeoutListener；scanTimeout 改调注入的 self 代理或独立 Bean；为批量任务加分页与事务超时；事务内只留 DB 操作与 outbox 登记（业务代码已做到零直连 MQ，26 处事件登记全走 OutboxPublisher，这一点保持）。

### B6. 售后三处把 null Result 当成功：退款/退积分/退货入库可能静默丢失

Feign 契约返回 `Result<T>`，反序列化/解码异常场景可能拿到 null。正确写法应是 null 或 !success 都抛异常（参照 `shop-settlement-service/.../clearing/service/ClearingService.java:272-282` 与 `shop-product-service/.../comment/service/impl/CommentServiceImpl.java:170-179`）。售后有三处用了 `r != null && !r.isSuccess()` 的错误判空：
- `shop-aftersale-service/.../aftersale/service/impl/AftersaleTimeoutServiceImpl.java:70-77`：运费险理赔 userClient.creditBalance，r==null 被当成功，理赔款不到账且售后流程继续；
- `shop-aftersale-service/.../mq/service/impl/AftersaleMqServiceImpl.java:162-169`：退款成功事件内 userClient.refundPoints，r==null 吞掉→积分不退、消息 ACK 不重试（消费者幂等表已先插入同事务，业务事务一旦提交无法靠 MQ 重试找回）；
- `shop-aftersale-service/.../aftersale/service/impl/AftersaleServiceImpl.java:857-860`：productClient.returnStock，r==null 吞掉→退货商品不回库存。

同文件 :431 saleable 用的是正确判空（`saleable == null || !saleable.isSuccess()`），说明是写法不统一而非刻意。:787 payClient.refund 走私有 unwrap（:1000）是安全的。

修复方向：三处改为 null 即抛 BizException(DEPENDENCY_FAIL)（可恢复→MQ 重试或人工介入）；根治手段是统一 FeignResults/ErrorDecoder（见 B2），让框架层永不返回 null。

### B7. 签到主入口 @Transactional 自调用失效，多表写入无事务保护

`shop-user-service/.../signin/service/impl/SignInServiceImpl.java:46` 控制器入口 `sign(Long userId)` **无** @Transactional，内部 this 调用 :51 `sign(Long, LocalDate)`（有 @Transactional）→ 代理被绕过，:51 事务不生效。该方法内签到记录、积分发放、成长值等多表写入实际在自动提交模式下逐条执行，中途失败留下部分写入。

同类自调用（危害较低，一并列出）：PaymentServiceImpl.java:557→:498（见 B5）；ReconcileServiceImpl.java handleDiff :184 被 :62/:168 同类调用；AccountService.java:226→this.getOrCreate(:29)；CouponService.java:45→:68（:43 自身有注解，实际危害低）。已正确规避的独立 Bean：OrderPersister、MarketingTxOps、SettleClearingExecutor、AftersaleRefundStore、GrowthDiscountExecutor:36、OutboxRelayDispatcher。

修复方向：SignInServiceImpl 注入 self 代理（或拆独立 Tx Bean），主入口直接加 @Transactional；建立 ArchUnit 单测禁止 @Transactional 方法内同类调用另一个 @Transactional 方法。

---

## 三、重要项（11 条）

### Z1. settlement 全部 20 个事务方法使用裸 @Transactional（无 rollbackFor=Exception.class）

证据：AccountService.java:29、ClearingService.java:70/119/181、ClearingReverseService.java:63、DepositService.java:63/74/109/139/153、MerchantService.java:30/64、SettleClearingExecutor.java:58、WithdrawService.java:82/147/168/201/218/268/312。默认只对 RuntimeException/Error 回滚；其余 132 个事务方法均显式 rollbackFor。当前代码未抛受检异常，实际风险潜伏，但资金域应统一补齐。

修复方向：全模块统一 `@Transactional(rollbackFor = Exception.class)`，或在框架层定制 TransactionAttributeSource 统一默认。

### Z2. 数据库口令 root/root 明文硬编码在全部 yml

如 `shop-order-service/src/main/resources/application.yml:12-14`（7 个服务同构，本地库）。prod profile 只对 JWT/内部 token 做了 fail-fast（ShopSecretEnvironmentValidator、application.yml prod 段），数据源凭据未见环境注入/密文处理的统一方案（deploy/kubernetes 有 15-secret-template.yaml 但 yml 未引用 env 占位）。

修复方向：datasource url/username/password 改为 `${DB_...}` 环境注入，K8s Secret 挂载；CI 增加明文口令扫描。

### Z3. consumeMaxAttempts 是死配置，重试/DLQ 行为实际由 broker 默认值决定

`shop-framework/.../mq/MqProperties.java:19` 声明 consumeMaxAttempts=16，但全仓无任何引用；PushConsumer 构造点 `MqConsumerRegistrar.java:140-145` 未设置最大重试/消费线程数/批量参数，实际依赖 rocketmq-client-java 默认（16 次、20 消费线程/listener）。运维以为可调的参数实际不生效。

修复方向：要么在 builder 上显式设置并重命名说明，要么删除该配置避免误导；消费线程数按服务消费能力显式配置。

### Z4. %DLQ% 无消费者、无重放工具、无告警

代码侧只有 MqErrorPolicy.java:23-24 注释声明"人工对账兜底"；`deploy/rocketmq/create-topics.sh:10-44` 只建 22 topic + 26 消费组，不建/不订阅 DLQ；deploy/prometheus、deploy/grafana 无 rocketmq/DLQ 告警；终态毒丸在 `MqConsumerRegistrar.java:189-193` 被 ACK 丢弃（不进 DLQ，仅 error 日志，也无告警）。注意 outbox 的 status=2 死信（OutboxMapper.java:54 countDead→ERROR）与消费端 %DLQ% 是两套，均只有日志人工。

修复方向：增加 DLQ 消费/告警（堆积计数接 Prometheus）、终态错误日志接告警平台、提供按 msgId 重放工具；RUNBOOK.md:38 的"MQ 堆积"泛告警要落到具体指标。

### Z5. t_mq_outbox 缺少 biz_key 唯一约束

`sql/common/V3__outbox.sql:26-27` 只有 PRIMARY KEY(id) + idx_status_deliver(status,deliver_at)；biz_key（:17）是普通列。应用层重入/重试若重复登记同一业务事件，relay 会发出两条消息。25 个消费者有 eventId 幂等表兜底不会产生重复业务效果，但支付/库存等高流量域有额外放大成本，且幂等表之外的新增消费者容易踩坑。

修复方向：增加 `UNIQUE KEY uk_topic_biz (topic, biz_key)`（tag 是否纳入按业务评估），重复登记走 INSERT IGNORE 或捕获 DuplicateKey。

### Z6. Spring 调度器默认单线程，同服务任务互相阻塞

全仓无 TaskScheduler/ThreadPoolTaskScheduler 配置，@Scheduled 默认共用单个 scheduling-1 线程。25 个任务中 aftersale 有 5 个 fixedDelay=60s 任务（AftersaleTimeoutJob.java:29/41/53、AftersaleClaimJob.java:31/43），marketing 有全表 reconcile（SeckillReconcileJob.java:27，15min 一次），settlement 有 500 笔/批的 remit（WithdrawRemitJob.java:22）；一个任务跑慢会推迟同服务所有其他任务（含 outbox relay——relay 在 framework 内每服务各跑，受各服务单线程影响，事件投递延迟被无关任务拖累）。

修复方向：配置 ThreadPoolTaskScheduler（poolSize 3~5，线程命名），或为关键任务（outbox relay）单独调度器。

### Z7. ShedLock 锁前缀硬编码 "shop-scheduler"，无环境/命名空间隔离

`shop-framework/.../shedlock/ShedLockConfig.java:18-20` RedisLockProvider 硬编码 key 前缀；多环境（dev/test/prod）共用同一 Redis 实例同 db 时，跨环境的同名任务会互相抢锁导致任务不执行。各服务 Redis db 已分开（order=db3 等），但环境间隔离未配置。另：除 outbox 2 个任务外部化（OutboxRelayJob.java:55/73）外，其余 23 个调度表达式全部硬编码。

修复方向：前缀改为 `shop-scheduler:${spring.profiles.active:default}` 或纳入 namespace 配置；cron 表达式外部化便于运维调整。

### Z8. readiness/liveness 探针组未显式配置，默认 readiness 组包含 db/redis/mqConsumers

全部 8 个 yml 只有 `management.endpoint.health.probes.enabled: true`（如 shop-order application.yml:60-63），**没有** group.readiness/liveness 成员映射；K8s `deploy/kubernetes/10-services.yaml:81-92` 等探针打 /actuator/health/liveness|readiness。Spring Boot 默认 readiness 组聚合全部 HealthIndicator → Redis/DB/MQ 任一抖动，readiness 翻 DOWN，Pod 被摘流；2 副本（:18）+ maxUnavailable=0（:21）下若中间件全局抖动可能两个副本同时摘流造成容量骤降（liveness 默认只含 livenessState，不会误杀进程，这一点是正确的）。MqConsumerRegistrar 注释（MqConsumerHealthIndicator.java:11-13）也写明"若部署纳入 readiness 组"，实际部署未做这层取舍。

修复方向：显式配置 readiness 组只含 livenessState + readinessState（或谨慎纳入 mqConsumers），db/redis 抖动通过独立告警与容错处理，而不是摘 Pod；Nacos 注册健康检查策略一并评估。

### Z9. AftersaleTimeoutListener 是唯一无 eventId 消费流水的监听器，且 dispatch 无统一事务边界

26 个 MQListener 中 25 个有"先插消费流水（同事务）→执行业务"保护（6 域 INSERT IGNORE + user 域 select/insert catch DuplicateKey，详见第四章节）；唯一例外 `shop-aftersale-service/.../mq/listener/AftersaleTimeoutListener.java:15`，dispatch（AftersaleTimeoutServiceImpl.java:38-51）不查不写 t_aftersale_mq_consume，5 类动作（audit/receive/exchange_ship/evidence/insurance）仅靠条件状态更新天然幂等。重复投递不会产生重复副作用（CAS rows=0 即跳过），但无 eventId 留痕，无法做消费审计/对账；且未知 kind 抛 IllegalArgumentException（:49，非 BizException）会走 16 次重试进 DLQ。

修复方向：补齐与其他 4 个售后监听器一致的流水写入；未知 kind 这类编程错误宜归为终态（MqErrorPolicy 增加 IAE 分类或显式 BizException(PARAM_INVALID)）避免无意义重试。

### Z10. 中间件与应用关键指标缺少监控/告警闭环

management 仅暴露 health,prometheus,info（shop-order application.yml:55-58），未见 Druid 连接池、Redisson、RocketMQ 消费堆积/DLQ、JVM 线程池的告警规则（deploy/prometheus、deploy/grafana 目录无对应规则，grep rocketmq/druid 无命中）；chaos 也只看服务 UP 与 HTTP 成功。B1/B4/Z4 类问题在生产发生时会"无指标可查"。

修复方向：接入 druid/micrometer、redisson、rocketmq exporter 指标，配连接池占用、慢 SQL、消费延迟、DLQ 计数、Tomcat 忙线程数告警。

### Z11. Nacos 不可用时的启动/运行行为未验证

discovery 仅配 server-addr（如 shop-order application.yml:23-25），未见注册失败 fail-fast 或重试参数显式配置；按 Spring Cloud Alibaba 默认行为推断为懒注册/后台重试（启动不被阻断），但未做实测，也无文档结论。网关路由全依赖 Nacos 服务发现，Nacos 长时间不可用时新实例无法注册、网关对重启实例的实例列表行为需要核实。

修复方向：做一次 Nacos 宕机启动/重启演练并写入 RUNBOOK；评估 Nacos 本地缓存与网关本地实例缓存的存活时间。

---

## 四、做得好的点（韧性资产，修复时注意保持）

1. **事务性 Outbox 完整落地**：业务代码零直连 MQ（26 处事件全部走 OutboxPublisher 事务内登记）；快车道 status=0 每 2s relay（`shop-framework/.../outbox/OutboxRelayJob.java:55-56`，@SchedulerLock），慢车道 status=2 挂起 + suspend_count 限 3 次自动重放 + 300s 冷却（OutboxMapper.java:24-34,45-51），指数退避 LEAST(POWER(2,retry),300)s，markSent CAS（WHERE status=0），死信 countDead 告警；relay 用 REQUIRES_NEW 独立 Bean（OutboxRelayDispatcher.java:43-58），防自调用失效。DDL 语义注释清晰：`sql/common/V3__outbox.sql:8-11`。
2. **消费幂等体系**：7 张 t_*_mq_consume 表（order V2:209-221、pay V2:234-249 为 uk(consumer_group,event_id)、其余 uk_event_id），6 域 INSERT IGNORE + user 域 select/insert 双保险（MqConsumeServiceImpl.java:24-48），流水与业务同事务、异常一起回滚支持重试；product 域注释记录了历史 id=0 导致幂等失效的事故（MqConsumeRecordMapper.java:14-22）。
3. **毒消息终态分类**：MqErrorPolicy.java:29-37 七个终态错误码 ACK 丢弃，NOT_FOUND/ORDER_NOT_FOUND 刻意保留可重试（跨服务复制延迟，MqErrorPolicy.java:20-24）；反序列化毒丸直接 ACK（MqConsumerRegistrar.java:171-176）；可恢复异常 FAILURE 交 broker。
4. **消费者注册不阻断启动**：单 daemon 线程后台注册（MqConsumerRegistrar.java:43-47,59-71），无限指数退避封顶 30s ±20% 抖动（:113-132），并针对 rocketmq-client-java 5.0.x build 失败泄漏 gRPC 事件循环的问题做了重试速率压制（约 2 次/分钟/消费者）；生产者懒加载（MqProducer.java:41-68），enabled=false DRYRUN。
5. **ShedLock 25/25 全覆盖**：25 个 @Scheduled 全部带 @SchedulerLock（含 framework 的 2 个 outbox 任务），无漏加锁的多实例重复执行风险；锁参数（lockAtMostFor/lockAtLeastFor）按任务合理分级（日结 PT2H、扫单 PT5M/PT1M）；全部 public 跨 Bean 调用，无自调用失效。
6. **CAS/乐观锁/UK 扎实**：20 个 @Version 实体 + OptimisticLockerInnerInterceptor（MybatisPlusConfig.java:19-25）；关键资金/库存全部条件更新——库存四态 TCC（ProductSkuMapper.java:19-60，WHERE available_stock>=qty）、余额防透支（UserAccountMapper.java:18-48）、退款防累计超额（PaymentMapper.java:74-76，refunded_fen+amount<=amount_fen）、订单状态机（OrderMapper.java:15-100 全部 AND status=from）、拼团防超员（GroupbuyMapper.java:15-31）、秒杀 uk(activity_id,user_id,deleted)；69 处 UNIQUE KEY；FOR UPDATE 仅结算 2 处（AccountMapper.java:69-70、MerchantMapper.java:30），克制使用。
7. **下单链路事务边界是全仓样板**：Feign TCC 三步在事务外 + 独立 Bean 短事务落库 + outbox + 逆序补偿每步独立 try/catch（OrderCreateServiceImpl.java:106/114/128、OrderPersister.java:49-72、补偿 :475-514）；支付多重尝试墓碑 CAS uk_order_active(order_no,active_slot)（sql/pay/V4__pay_multi_attempt.sql:42-51）。
8. **@Idempotent 切面设计严谨**：@Order(HIGHEST_PRECEDENCE) 保证在事务外侧（IdempotentAspect.java:59，类注释 :42-56 详述顺序错误会回放未落库结果的风险）、INFLIGHT 占位 + 20ms 轮询/30s 上限回放同一结果、失败立即删键允许重试、SpEL 空值 fail-closed 防全局键退化（:113-115）；支付渠道回调刻意不用切面而以 t_pay_notify_log uk(channel_code,notify_id) 服务层幂等（ChannelNotifyController.java:22）。
9. **Redisson 看门狗已启用**：DistributedLockTemplate.java:25 lease=-1 自动续期（修复过固定租约问题），获锁失败明确 TOO_MANY_REQUESTS 而非放行。
10. **优雅停机与多副本部署完整**：server.shutdown=graceful + 30s（各 yml :3-7）、K8s preStop 10s + terminationGracePeriodSeconds=60 + 2 副本 + maxUnavailable=0/maxSurge=1 + PDB（deploy/kubernetes/10-services.yaml:18-30,56,102）。
11. **prod 密钥 fail-fast**：ShopSecretEnvironmentValidator、DataCipher.java:78-89、网关 GatewaySecretValidator 对 JWT/内部 token 默认值启动拒绝。
12. **MQ 延时消息统一走 outbox**：12 个延时业务点（支付超时 15/30min、自动收货 10 天、售后窗口 15 天、售后 5 类超时、运费险 72h、预售尾款等）全部 publishDelay 事务内登记（OutboxPublisher.java:32-58），避免 broker 定时消息与本地事务不一致；MqProducer.sendDelay 无业务调用方（无绕过）。

---

## 五、修复建议（建议项 10 条 + 落地顺序）

建议项（除第二/三章已嵌入修复方向外的补充）：

1. **统一 FeignResults**：删除 order/pay 两份重复实现，在 framework 提供唯一 unwrap（null/失败/5xx 全包 DEPENDENCY_FAIL），配合 B2 的 ErrorDecoder。
2. **Feign 调用点做静态扫描门禁**：ArchUnit 规则禁止 @Transactional 方法（含其跨 Bean 调用链上的 service）直接出现 `xxxClient.` 调用，白名单化存量点。
3. **@Transactional 自调用检测**：单测/ArchUnit 禁止同类 this 调用标注 @Transactional 的方法（覆盖 B7、Z 中 4 处）。
4. **调度表达式外部化**：23 个硬编码 cron 迁到配置（outbox 已是范例）；为任务增加 metrics（上次执行时间/耗时/失败计数）。
5. **ShedLock 前缀加环境后缀**（Z7）。
6. **显式 health groups**（Z8）并做 K8s 探针演练。
7. **Redis 写链路降级策略评审**：明确哪些点允许降级（已有 SeckillStockClient/Coupon 模式）、哪些必须 fail-closed（锁/余额），并为限流/幂等切面的 Redis 异常区分"拒绝"与"500"语义，补 chaos 写链路场景（B4）。
8. **营销消费表 status 注释反向**：sql/marketing/V2__marketing.sql:332 注释为"0成功 1失败"，与其他域语义相反（代码固定写 0 不读该字段，暂无功能影响），统一注释/语义避免后续维护误判。
9. **MqConsumeTemplate.noop() 死代码**（MqConsumeTemplate.java:30-32 无调用方）清理；consumeMaxAttempts 死配置处理（Z3）。
10. **Nacos 故障演练 + MyBatis-Plus 插件顺序复核**（分页/乐观锁拦截器注册顺序 MybatisPlusConfig.java:19-25 已正确，保持即可）；补全各服务 Redis password 与 TLS 策略。

建议落地顺序：

| 波次 | 内容 | 理由 |
|---|---|---|
| 第 1 波（配置即可生效，不改代码） | B1 Druid 参数、B2 Feign 超时参数（Options 配置类）、Z8 health groups、Z2 凭据外部化、Z7 锁前缀 | 全部是 yml/配置类改动，风险低、收益最大，先给系统装上"安全带" |
| 第 2 波（防护网） | B3 网关+Sentinel 限流规则持久化、Z10 监控告警、Z4 DLQ 告警、Z3 消费参数显式化 | 在流量层与观测层补齐防线 |
| 第 3 波（代码重构） | B5 pay/order 事务边界重构、B6 三处判空修复、B7 签到事务、Z1 settlement rollbackFor、Z5 outbox UK、Z9 售后超时流水 | 需要测试覆盖，按资金域优先 |
| 第 4 波（演练与长尾） | B4 Redis 写链路 chaos 补测、Z11 Nacos 演练、建议项 1-10 清理项 | 验证前三波效果，消除认知偏差与技术债 |

---

附：审计未覆盖项说明——本报告为静态代码/配置审计，未进行运行时故障注入复测；chaos.sh 结论引用自脚本设计（`deploy/loadtest/chaos.sh:35,169,179-198`）而非现场运行结果；Nacos 故障行为（Z11）为推断待实测。
