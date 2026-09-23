# GAP_PLAN_PLATFORM — 平台框架韧性与基础设施规划

> 范围：shop-framework / shop-gateway / shop-common / deploy（kubernetes、rocketmq、loadtest、prometheus、grafana）/ sql/common。业务服务只规定"引用方式/配置落点"，具体业务改法归 GAP_PLAN_FUNDS / TRADE / USER。
> 只读规划：不改任何代码/配置，不执行 mvn/docker/kubectl。所有行号为取证时（2026-09-17）现状，实施时以现状为准；行号失效不影响卡内容（按类名/配置键定位）。
> 证据来源：AUDIT_RESILIENCE.md（B1–B7/Z1–Z11）、AUDIT_MQ_CONSISTENCY.md（P2-2/P2-3/P3-1/P3-2/P3-3/P0-1）、AUDIT_API_CONTRACT.md（§4.2/§7.3）、AUDIT_K8S.md（§A/§B/§C）、PERF_REPORT.md（§3 雪崩实证）。

## 0. 审计勘误（行号失效证伪表）

| # | 审计原述 | 复核结论 | 影响 |
|---|---|---|---|
| K1 | AUDIT_RESILIENCE B3 称网关"无 RequestRateLimifier 依赖" | 表述成立（shop-gateway/pom.xml 仅 gateway+nacos-discovery+loadbalancer+actuator+jjwt 五类依赖，无限流/熔断组件）；原文拼写 RequestRateLimifier 应为 RequestRateLimiter | 仅文字勘误，结论不变 |
| K2 | AUDIT_MQ P2-2 建议"在 MqConsumerRegistrar 反序列化后统一兜底" | 现状 dispatch（MqConsumerRegistrar.java:166-179）反序列化后直接 invoke（:177），listener.onMessage（:184）直接拿 payload，**框架侧不碰 eventId**；eventId 兜底在各业务 MqConsumeService 内（order 合成 noid:、user 抛 IllegalArgumentException） | 卡 R-MQ 落点修正：兜底应在框架 invoke 包一层事件标准化或由框架提供 EventNormalizer 工具，不能在 dispatch 反序列化处（payload 是泛型 Object） |
| K3 | AUDIT_RESILIENCE Z3"consumeMaxAttempts 是死配置" | 成立：MqProperties.consumeMaxAttempts=16（MqProperties.java:19）存在，但 buildOnce（MqConsumerRegistrar.java:140-146）构建 PushConsumer 时未 setMaxAttempts/未引用该字段；实际重试由 broker SubscriptionGroup 默认值决定 | 卡 R-MQ：要么接线到 PushConsumerBuilder，要么删除该键改由 create-topics.sh updateSubGroup 参数声明 |
| K4 | AUDIT_RESILIENCE Z9 称 AftersaleTimeoutListener 是"唯一无 eventId 消费流水的监听器" | 与 MQ 审计 P2-1 同源（AftersaleTimeoutServiceImpl）；属业务执行点（FUNDS/TRADE 域），本计划只在 R-MQ 提供框架统一 eventId 兜底（C15）后业务侧删除私有分支 | 不重复发卡 |
| K5 | AUDIT_K8S B4"业务初始堆 ≈820Mi > request 768Mi" | 具体数值实施时以 10-services.yaml 各 Deployment env JVM_ARGS 与 resources.requests.memory 实测为准（grep 确认探针/资源结构存在，未逐段核数值） | 卡 R-K8S 保留调参动作，数值标注"实施时核算" |
| K6 | AUDIT_RESILIENCE B2 引用 `shop-framework/pom.xml:46/66-68` | 行号未复核（未读 pom 行段）；事实面已独立证成：框架仅有 FeignRequestInterceptor.java 两个 RequestInterceptor Bean（:23/:34），全类无 Options/ErrorDecoder/Retryer，无连接池依赖声明于 gateway/框架 Java 侧 | 按类名定位，不依赖行号 |
| K7 | AUDIT_API E-4 称 ConstraintViolationException 回 `e.getMessage()` | 成立：GlobalExceptionHandler.java:50 `Result.fail(ErrorCode.PARAM_INVALID, e.getMessage())`，消息形如 `方法名.arg0.字段 模板`，泄露方法签名 | API-P 卡直接修 |
| K8 | P3-3"common 到 V5" | 成立：sql/common 仅 V3__outbox.sql、V5__outbox_suspend_count.sql（无 V1/V2/V4，V4 在 settlement 域为副本）；本计划新增为 V6 | DDL 编号无冲突 |
| K9 | PERF_REPORT §3 称网关回"裸 500 JSON 非业务包裹体" | 成立：shop-gateway 内 grep ErrorWebExceptionHandler/ErrorAttributes 零命中，无任何统一异常处理；下游 Connect refused/5xx 直接透传 Netty/SC Gateway 原生错误体 | R-B3 卡核心验收点 |
| K10 | AUDIT_RESILIENCE Z2 root/root | 成立：shop-order application.yml:12-14 仍为 username/password root/root（7 业务服务同构）；JWT/内部令牌的 prod fail-fast 已由 ShopSecretEnvironmentValidator（:44-53）+ SecretStrength 落地（H-3 已闭环代码侧），**Z2 剩余项收窄为 DB/中间件口令外部化 + prod 清单复核** | R-Z2 卡范围收窄，避免重复 H-3 |

## 1. 卡总览与依赖图

九张卡（W1 先行：配置基线/Feign/异常契约；W2 入口与 MQ；W3 事务规约与部署硬化）：

| 卡 | 名称 | 审计项 | 波次 |
|---|---|---|---|
| R-B1 | Druid 有界连接池与慢 SQL 基线 | B1 | W1 |
| R-B2 | Feign 统一超时/连接池/ErrorDecoder/熔断 | B2（支撑 B6） | W1 |
| R-B3 | 网关限流 + 统一异常包裹体 + 快速失败 | B3、FEATURES B12、PERF §3 | W2 |
| R-B4 | Redis 故障策略矩阵与 chaos 流量补盲 | B4 | W2 |
| R-B5 | 事务外调用框架规约（模式卡） | B5、Z1 | W2（业务执行归 FUNDS） |
| R-MQ | MQ 平台侧兜底/巡检/DLQ/outbox/调度/探针/Nacos | P2-2/P2-3/P3-1/P3-2/P3-3、Z3/Z4/Z5/Z6/Z7/Z8/Z11、P0-1 配套 | W2 |
| R-Z2 | 中间件口令外部化与 prod fail-fast 复核 | Z2、H-3 复核 | W1 |
| API-P | 全局异常契约补全（400 语义/签名泄露/体上限） | AUDIT_API §4.2 E-1/E-2/E-4/E-6、§7.3 | W1 |
| R-K8S | 部署硬化清单（HPA/NetworkPolicy/JVM/securityContext/TLS/broker/Service/脚本） | AUDIT_K8S §B（§A 仅残留记录） | W3 |

依赖图：

```
R-B1（连接池基线）─┐
R-B2（Feign 基线）─┼─> R-B3（网关在统一包裹体协议确定后做异常映射；熔断选型与 R-B2 一致）
API-P（错误契约）──┘        │
                            ├─> R-B4（chaos 验证依赖 R-B1/R-B2 的有界行为与 R-B3 包裹体断言）
R-Z2（密钥/口令）─> W1 全部部署项（prod 清单先行）
R-MQ（C15 eventId/C18 DLQ）─> FUNDS 卡删业务私有兜底、补 P3-1 执行点
R-B5（规约模式）─> FUNDS/TRADE 业务卡引用，不在本仓落地业务代码
R-K8S 依赖 R-B1/R-B2 配置键定型（ConfigMap 落值）；broker 关 autoCreate 依赖 create-topics.sh 完整性（C17）
```

显式不做：BizException HTTP 语义（E-3：除 401/403 外全 200+body.code）**维持现状不改**——全链路、E2E、前端均依赖 body.code 判定，改动跨所有服务与 shop-e2e，属独立契约变更，本计划只声明不改。

## 2. 全局契约清单（shop-framework / shop-common / shop-gateway 变更）

新增配置统一前缀 `shop.*`，全部带安全默认值（dev 可裸跑，prod 由 profile/ConfigMap 收紧）。契约编号 C12–C19（接既有计划编号，不查历史占用，若冲突实施时顺延）。

### C12 — 数据层韧性配置基线（R-B1）
`shop.datasource.tuning.*`（shop-framework 提供 `DataSourceTuningProperties` + Druid 自定义绑定，或经 `spring.datasource.druid.*` 标准键，实施时任选其一并全仓统一，推荐后者减少自定义代码）：
- `spring.datasource.druid.initial-size=2`
- `spring.datasource.druid.min-idle=5`
- `spring.datasource.druid.max-active`（**按服务分档**，默认 20；pay/settlement/order=30，其余=20；kind/压测环境可覆盖为 10）
- `spring.datasource.druid.max-wait=3000`（毫秒，有界；消除 -1 永久排队）
- `spring.datasource.druid.validation-query=SELECT 1`、`test-while-idle=true`、`test-on-borrow=false`、`keep-alive=true`
- `time-between-eviction-runs-millis=60000`、`min-evictable-idle-time-millis=300000`、`phy-timeout-millis=1800000`
- `remove-abandoned=true`、`remove-abandoned-timeout=300`、`log-abandoned=true`
- filters: `stat,wall`；`spring.datasource.druid.filter.stat.slow-sql-millis=1000`、`log-slow-sql=true`
- 暴露：`DruidStatManagerFacade` Micrometer 指标（druid 活跃连接数/等待数），供 R-K8S/observability 告警引用（告警规则本身不在本卡实现，仅保证指标可达 /actuator/prometheus）。
- 覆盖机制：每个服务 application.yml 只放分档 profile（dev/kind/prod），prod 值来自 K8s ConfigMap；不允许任何服务私设 max-wait=-1。

### C13 — Feign 韧性契约（R-B2，支撑售后 R-B6）
`shop.feign.*`（shop-framework 新增 `feign/FeignResilienceConfiguration`，经 @Import 进 ShopService 或 spring.factories AutoConfiguration 自动生效，业务零改动）：
- `shop.feign.connect-timeout=2s`、`shop.feign.read-timeout=3s`（默认）；按 client 覆盖键 `shop.feign.clients.<clientName>.read-timeout`（如 pay-channel-query=5s）。
- `shop.feign.retry.max-attempts=0`（默认不重试；仅幂等 GET 允许 `shop.feign.retry.methods=GET` 且 attempts=2 + backoff=200ms；重试在熔断器内部，禁止对 POST 默认重试）。
- HTTP 连接池：启用 Apache HttpClient5（`feign-hc5`），`shop.feign.pool.max-connections=200`、`max-connections-per-route=50`、`connection-time-to-live=30s`、`time-to-live-unit=SECONDS`。
- `ShopErrorDecoder implements ErrorDecoder`（framework 新类）：
  - 5xx / IO 异常 / RetryableException / 空 body / body 非 Result 结构 → 统一抛 `BizException(DEPENDENCY_FAIL, "下游服务不可用: "+clientName)`（可恢复，进 MqErrorPolicy 重试集合——现状 MqErrorPolicy 对非 BizException 已默认可恢复，BizException(DEPENDENCY_FAIL) 不在 TERMINAL_CODES，语义自洽）；
  - 200 且 body 为 `Result{code!=0}` → 透传原 code/message（业务 code 不被吞）；
  - 200 且 body 为 `Result{data=null}` 且泛型非 Void/包装类型 → 抛 DEPENDENCY_FAIL（**框架层永不返回 null，根治售后三处 r==null 当成功，支撑 R-B6**）；
  - 404 映射 NOT_FOUND 类业务码（透传 body.code 优先）。
- 熔断器选型：**Resilience4j（feign-circuitbreaker + bulkhead）**，理由：①与 SC Alibaba 无关联绑定，规则可用 `resilience4j.circuitbreaker.configs.default.*` 纯配置持久化（Nacos/本地 yml 双可），不依赖 Sentinel dashboard 推送（现状 dashboard 规则重启即丢，B3 已证）；②可同时提供熔断 + 舱壁（按 clientName 隔离线程池/信号量），直接实现"核心链路与非核心链路隔离"；③gateway 侧限流另选 Redis token bucket（见 C14），两端组件不强绑同一控制面。默认：slidingWindowType=COUNT_BASED,100；failureRateThreshold=50；waitDurationInOpenState=10s；slowCallRateThreshold=60、slowCallDurationThreshold=2s；bulkhead maxConcurrentCalls=30。允许 `shop.feign.circuit.enabled=false` 单测/本地关闭。
- framework 提供唯一 `FeignResults.unwrap(Result<T>)`（合并 shop-order/shop-pay 两份重复实现到 shop-framework `feign/FeignResults`），业务服务改为 import；**ErrorDecoder 落地后 unwrap 仅兼容保留，新代码直接用返回值（框架保证非 null）**。
- RequestInterceptor 现状两个 Bean（internal token / 身份头，FeignRequestInterceptor.java:23-48）保持不动。

### C14 — 网关限流与统一错误包裹契约（R-B3）
- 限流实现：Spring Cloud Gateway 官方 `RequestRateLimiter` + Redis（Lua token bucket），键解析器 `ShopKeyResolver`：
  - 全局默认：`shop.gateway.ratelimit.global.replenishRate` / `burstCapacity`（默认 100/200，prod 按容量压测后 ConfigMap 覆盖）；
  - 路由维度：`shop.gateway.ratelimit.routes.<routeId>.replenishRate/burstCapacity`（order=20、pay=10、marketing-seckill 路径更低，实施时引用 PERF §3 拐点 20 TPS 为初始值）；
  - 主体维度：已登录按 X-User-Id（JwtAuthGlobalFilter 清洗后内部头），匿名按 client IP（取 X-Forwarded-For 首段，防伪造由网关覆写）；
  - key 前缀带环境：`rl:{env}:{routeId}:{principal}`，复用 R-Z2 的 env 注入。
  - Redis 故障时限流器默认 deny（fail-closed，可用 `shop.gateway.ratelimit.redis-fail-open=false` 锁定，不允许生产打开）。
- **统一异常处理（新增，WebFlux）**：`shop-gateway` 新增 `ShopGatewayErrorAttributes implements ErrorWebExceptionHandler`（或自定义 `ErrorWebExceptionHandler` Bean，Ordered 高于 DefaultErrorWebExceptionHandler）：
  - 捕获 `ConnectException/Connection refused/` `ResponseStatusException 5xx`、`NotFoundException(503)`、`PrematureCloseException`、限流 `HttpStatus.TOO_MANY_REQUESTS`、超时；
  - 一律输出业务包裹体 `{"code":<码>,"message":<文案>,"data":null}`（与 shop-common Result 同构，复用 Result 序列化字段名 code/message/data），并回写 HTTP 状态：限流 429→code=10007（沿用 ErrorCode 操作过频，实施时核对码值）；下游不可达 503→code=DEPENDENCY_FAIL；超时 504→DEPENDENCY_TIMEOUT（如无该码在 shop-common 新增，编号实施时定）；其余 500→SYSTEM_ERROR；
  - 响应头带 `X-Request-Id`（网关生成/透传 trace 头），message 禁止含 Netty 堆栈/IP/端口（PERF §3 的 `/10.244.0.114:8084` 不得出现在响应体）；
  - 与 API-P 契约共用错误码常量来源（shop-common ErrorCode），gateway 已依赖 shop-common（pom 已证）。
- 快速失败与有限重试：`spring.cloud.gateway.httpclient.connect-timeout=2000ms`、`response-timeout=5s`；重试过滤器仅对 GET 且 502/503/Connect refused 生效，retries=1（`shop.gateway.retry.methods=GET`），POST/PUT/DELETE 默认零重试（幂等键在业务层，不做不安全重试）；`spring.cloud.loadbalancer.retry.enabled=false`（避免与网关重试叠加双重重试，实施时验证）。
- 服务层 @RateLimit（framework/ratelimit，6 处业务落点）**保留为第二道防线**，本卡不删不改其业务使用点；其 Redis fail-closed 行为（R-B4 矩阵）保持。

### C15 — MQ 平台契约（R-MQ）
- 事件标准化：framework 新增 `mq/EventNormalizer`（或在 MqConsumerRegistrar.invoke 前以装饰器方式包 listener）：从 MessageView.getKeys()/header/eventId 字段统一取 eventId，为空时合成 `noid:{topic}:{bizKey|msgId}` 注入消费上下文（MqConsumeContext ThreadLocal/参数），**业务 MqConsumeService 内 order 的 `noid:topic:bizNo` 拼接与 user 的 IllegalArgumentException 私有分支全部删除**，改由框架提供；落消费流水的 eventId 列由此统一保证非空。配置 `shop.mq.event.synthetic-enabled=true`（关闭时 null eventId 走 ACK+ERROR 告警的备选策略，二选一由配置决定，默认合成）。
- 重试接线：`shop.mq.consume-max-attempts`（现存死键，MqProperties.java:19）在 buildOnce 中接线到 PushConsumer 订阅/消费构建（RocketMQ 5.x gRPC 对应重试策略以官方 builder 能力为准，实施时若 builder 无该 setter，则改为 create-topics.sh `updateSubGroup ... -r 16` 声明并在启动日志回显实际组配置，同时删除 Java 死键，二者必居其一，禁止继续"配置存在但不生效"）。
- DLQ/死信可观测：framework 新增 Micrometer 计数器 `shop_mq_consume_dead_total{topic,group}`（invoke 终态 ACK 丢弃处 + 未来 DLQ 拉取巡检处）与 `shop_outbox_suspended_total`（OutboxRelayJob.requeueSuspended log.error 处，P3-2）；新增内部只读端点 `shop.mq.admin`（actuator 自定义 endpoint，默认 enabled=false，prod 仅内网/带内部 token 开放）：列出 status=2 outbox、手动 requeue（复用已有 manualRequeue(id) 逻辑包 HTTP 壳）。
- 孤儿 topic 巡检：framework 启动后/每日（复用 ShedLock 单实例）执行一次"本服务发出 topic vs 本地订阅组"核对，无法判定仓库外订阅；**主体动作是文档声明（C17 的 ACCEPTANCE 表）+ broker 侧订阅组计数指标**，框架只提供 `shop_mq_topic_no_subscriber{topic}` 指标（经 mqadmin 或 broker 指标，能力不足时降级为日志），不做自动删 topic。
- Outbox 自检：framework 新增 `OutboxSchemaHealthCheck`（ApplicationRunner，`shop.outbox.schema-check.enabled=true`，prod 默认 true）：启动时 `SELECT 1 FROM t_mq_outbox LIMIT 1`（不查 information_schema 以免权限问题），失败时按策略：`mode=failfast`（prod 默认）拒绝启动并打印明确指引"执行 sql/common/V3/V5/V6"；`mode=log`（dev 默认）仅 WARN。
- 调度器：framework 新增 `SchedulerConfig`：ThreadPoolTaskScheduler poolSize=`shop.scheduler.pool-size`（默认 2，prod 建议按服务 @Scheduled 数量设置，order/pay/settlement=4），线程名 `shop-sched-`；@EnableScheduling 保持在 ShopService（:35），Bean 由框架提供覆盖单线程默认。
- ShedLock 前缀环境隔离：`RedisLockProvider(connectionFactory, env)`，env 取 `shop.env`（无默认值时 dev-local；prod 必须由 env 注入，配合 R-Z2）；key 形如 `shop:scheduler:{env}:{lockName}`；`@SchedulerLock(name=...)` 业务侧 name 不变。
- 探针分组：framework 统一在 AutoConfiguration 注册 `management.endpoint.health.group.readiness`（include: readinessState,db,redis；**排除 mqConsumers**——消费注册循环本就设计为 broker 不可用时 HTTP 照常，不应进入 readiness 导致摘流）与 `liveness`（仅 livenessState）；现状 yml 仅有 exposure include=health,prometheus,info（order application.yml:59-61），分组键未显式配置，10-services.yaml 探针已打 /health/liveness|readiness（:82-92 等），框架补齐分组定义使其语义真实。
- 消费事务内 Feign 规约：framework 不新增代码，提供 `mq/AbstractTransactionalListener` 规约基类或文档化模板（见 R-B5 卡）：先事务外预取/校验，再开短事务只做 DB+outbox；settlement PaymentSucceededListener、aftersale claimInsurance 执行点归 FUNDS 卡，本卡只提供模式与评审 checklist。

### C16 — 密钥/口令注入契约（R-Z2）
- DB 口令：全部 7 业务服务 application.yml 的 `username/password: root/root` 改为 `${SHOP_DB_USER:root}` / `${SHOP_DB_PASSWORD:root}`（dev 默认保留可裸跑）；prod 仅允许来自 K8s Secret（deploy/kubernetes/15-secret-template.yaml 增加 DB 凭据键，create-secrets.sh 补生成/读取说明）；Redis 密码键 `spring.data.redis.password=${SHOP_REDIS_PASSWORD:}`；RocketMQ/Nacos 鉴权键位由 C12/C15 配置同模式 env 化（broker 开启鉴权属 §A 托管事项，本卡只预留键）。
- 复核（不重做 H-3）：ShopSecretEnvironmentValidator（prod 或 shop.security.require-env-secrets=true 时校验 JWT/内部 token 非默认且强度≥16，:44-53）与 shop-gateway/GatewaySecretValidator 同构校验已存在；本卡只做：①prod 清单（00-namespace-config.yaml ConfigMap）显式置 `shop.security.require-env-secrets=true` 并经 ha-check grep 守护；②将 DB 口令弱口令（root/root）纳入同一校验器（新增 `shop.security.require-env-db=true`，prod 拒绝口令等于 root/空/长度<8）；③JWT 默认值占位现状（gateway application.yml:64 与 JwtAuthGlobalFilter.java:70 同一默认串）保持 dev 可用、prod 必被 fail-fast 拦截——加一条启动自测/单测断言 prod 缺 secret 时上下文启动失败（回归 H-3）。

### C17 — topic/DLQ create-topics.sh 精确追加（R-MQ/部署）
现状 create-topics.sh 已含 25 topic（含 P0-1 的 shop_refund_shortfall）+ 26 消费组，幂等 updateTopic/updateSubGroup。追加：
- 无新增业务 topic（本计划不产生新业务事件）；
- `updateSubGroup` 每条增加重试/死信参数显式化：`-r 16 -d 1`（消费重试 16 次、开启 DLQ，具体参数名以 mqadmin 版本 help 为准，实施时以现状为准），消除"broker 默认值决定行为"（Z3）；
- 新增 DLQ 巡检辅助函数（脚本段，非常驻服务）：`mqadmin topicList | grep %DLQ%` 输出各组死信条数并在 >0 时 exit 非零，供 ha-check/运维 cron 调用（Z4 台面最小版；完整重放走 C15 admin endpoint + RUNBOOK）；
- 9 个无消费者 topic（shop_deposit_alert/shop_withdraw_result/shop_points_changed/shop_clearing_register/shop_clearing_settle/shop_seckill_event/shop_groupbuy_event/shop_stock_warning，外加 shop_pay_result tag=result、shop_presale_event 部分 tag——P2-3）**不删 topic、不加假消费者**；在 ACCEPTANCE.md 新增"开放事件去向声明表"，逐个标注：开放通知（保 topic 不保证订阅）/ 仓库外消费方（系统名+SLA，需业务确认）/ 待废弃（标注版本，后续删生产者）；本计划只给出表骨架与强制声明要求，内容由业务负责人填。
- broker 生产模板：deploy/rocketmq/broker.conf.tpl:10-11 `autoCreateTopicEnable=true / autoCreateSubscriptionGroup=true` 改为由环境变量插值 `${RMQ_AUTO_CREATE_TOPIC:true}`，prod compose/清单显式置 false（R-K8S 卡落值）；create-topics.sh 成为生产部署前置步骤（README.md 补顺序）。

### C18 — API 错误契约补全（API-P，不改 E-3 语义）
- shop-framework GlobalExceptionHandler 新增：
  - `@ExceptionHandler(HttpMessageNotReadableException.class)` → HTTP 400 + `Result.fail(PARAM_INVALID, "请求体格式错误")`（不回显 Jackson 原始 message，防泄露类名）；
  - `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` → HTTP 400 + `"参数类型错误: "+name`（只带参数名，不带 requiredType/value）；
  - 新增同类：`HttpMediaTypeNotSupportedException`、`MissingServletRequestPartException` → 400；
  - 所有 400 handler 显式 `response.setStatus(400)`（现状仅 401/403 设状态）。
- `ConstraintViolationException` handler（:48-51）改为只取 `violation.getMessage()` 拼接（模板文本），禁止 `e.getMessage()`（含 `方法名.arg0.property` 签名）；路径变量/参数名通过 `propertyPath` 末段可选附带（不带类/方法名）。
- 业务侧 IllegalArgumentException（E-2：ChannelLimits:75-77、AdminController:80 LocalDate.parse 等）**框架不兜 IAE→400 映射**（IAE 可能是真 bug，误吞污染告警）；由业务卡逐点改抛 BizException(PARAM_INVALID)，本卡只给评审规则。
- `@Validated` 推广：framework 提供 `@Validated` 元注解组合或在 WebMvcConfig（framework/web/WebMvcConfig）上为所有 `@RequestMapping` 处理器的类级校验启用提供配置说明；实际动作是业务 Controller 类级加 @Validated（参数级 @Min/@NotBlank 才生效，E-6）——本卡只在 framework WebMvcConfig 注册 `MethodValidationPostProcessor` 兜底（若 Boot 默认未注册），业务加注解归各业务计划。
- 请求体上限：framework 统一 `spring.servlet.multipart.max-file-size` / `max-request-size`（默认 10MB/20MB，可由 `shop.web.body.max-request-size` 覆盖）+ Tomcat `server.tomcat.max-swallow-size` 与 `server.max-http-request-header-size` 基线（16KB）；Nginx/Ingress 侧注解同步（R-K8S）。
- 不改：BizException 维持 HTTP 200（401/403 除外）+ body.code 结构（E-3 显式冻结声明，全链路与 shop-e2e 依赖）。

### C19 — 环境标识与配置分档元契约
- 强制 `shop.env=${SHOP_ENV:local}`（local/kind/prod），供 C14 限流键前缀、C15 ShedLock 前缀、C16 密钥校验、C17 broker autoCreate 统一引用；prod 下该键缺失时 ShopSecretEnvironmentValidator 一并 fail-fast。
- 配置分档文件约定：framework 不内置 application-prod.yml（各服务独立部署单元），但在 deploy/kubernetes/00-namespace-config.yaml 的 ConfigMap 中集中给 prod 基线值（C12/C13/C14 各键），服务私配仅允许分档 max-active/read-timeout 等容量值。

## 3. DDL / 部署清单

### 3.1 sql/common/V6__outbox_biz_key_uk.sql（新增，Z5）
- 现状：V3 表 t_mq_outbox 仅有 PRIMARY KEY(id)、KEY idx_status_deliver，biz_key VARCHAR(128) DEFAULT '' 无唯一约束（V3__outbox.sql 已证）。
- 新增 V6（每个业务库执行：shop_user/shop_product/shop_marketing/shop_order/shop_pay/shop_settlement/shop_aftersale；settlement 域 V4 副本同步）：
  - 对存量脏数据兜底：先 `UPDATE t_mq_outbox SET biz_key=CONCAT('legacy:',id) WHERE biz_key='' OR biz_key IS NULL;`（历史空键不应阻止建 UK；周期/重放场景允许同一 biz_key 多条 outbox 的问题见下）；
  - **UK 口径选择**：Z5 审计原意是 biz_key 唯一，但 outbox 存在"同业务键多条事件"（同订单 order_created 与重发/多 tag）的合法语义，裸 UK(biz_key) 会误杀。实施二选一（推荐 A）：
    - A（推荐）`UNIQUE KEY uk_topic_bizkey (topic, biz_key)` 或 `(topic, tag, biz_key)`——按事件流去重，配合 relay 至少一次投递；
    - B 仅加普通索引 KEY idx_biz_key (biz_key)，去重完全交消费幂等表（现状机制），Z5 降级为"加速巡检"。
  - 用 V5 同款 information_schema + 存储过程守卫，幂等可重复执行；建索引前评估表规模（大表 ONLINE DDL，MySQL 8 默认 inplace/允许 DML，注释中写明）。
- V6 不引入 Flyway/Liquibase（P3-3 用启动自检替代，见 C15 OutboxSchemaHealthCheck）；README/部署文档补"sql/common V3→V5→V6 顺序"。

### 3.2 yaml/conf 改动一览（仅清单，不落盘）
- 7 业务服务 `src/main/resources/application.yml`：C12 druid 分档（可抽到 framework 内置 baseline yml 由各服务 import，减少 7 份重复——实施推荐该方式：framework 维护 `application-druid-baseline.yml` 不现实（framework 是 jar），改为 framework AutoConfiguration 用 DruidDataSourceCustomizer 注入默认值，服务 yml 零改动即可获基线，仅分档值写服务 yml）；C13/C15/C18 键同方式默认化。
- shop-gateway/src/main/resources/application.yml：C14 路由限流键、httpclient 超时、default-filters（Retry 仅 GET）；redis 连接配置（限流用，复用 spring.data.red is）。
- deploy/kubernetes/00-namespace-config.yaml：prod 基线 ConfigMap（shop.* 全部键）、`shop.security.require-env-secrets=true`、SHOP_ENV=prod、RMQ_AUTO_CREATE_TOPIC=false。
- deploy/kubernetes/10-services.yaml：网关 Deployment + Service + HPA 补齐；7 业务 Service 补齐；securityContext；imagePullPolicy: IfNotPresent；JVM InitialRAMPercentage 与 memory request 对齐（K5 数值实施核算）；readiness/liveness 路径不变（框架 C15 补分组定义）。
- deploy/kubernetes/30-networkpolicy.yaml：policyTypes 增 Egress + 默认拒绝基线，同命名空间规则收敛到具体端口（现 :34-35 仅 Ingress 且 namespace 内全放通）。
- deploy/kubernetes/20-tls-ingress.yaml：Ingress 注解（或 Gateway API TLS options）固化 TLS 最低 1.2（建议 1.3 preferred），禁用 1.0/1.1。
- deploy/kubernetes/15-secret-template.yaml + create-secrets.sh：DB/Redis/MQ 凭据键位与"Secret 不覆盖"策略文案修正（ha-check/build 脚本小项）。
- deploy/rocketmq/broker.conf.tpl：autoCreate 改环境变量插值（C17）。
- deploy/rocketmq/create-topics.sh：C17 追加（SubGroup 重试/DLQ 参数、DLQ 巡检函数）。
- deploy/loadtest/chaos.sh：补 POST 下单/支付 fail-closed 流量段（R-B4 验收）；修正 PRODUCT_PATH 单一流测口径说明。
- deploy/prometheus：新增告警规则文件（Druid 占用率、Feign 熔断开路、MQ DLQ/消费堆积、outbox suspended、网关 5xx/429）——规则内容引用 C12/C13/C15 指标名，归 observability 执行，本计划只冻结指标名。
- 密钥只允许 env/Secret：全部新增口令/密钥键禁止写入镜像与 git 清单明文；15-secret-template.yaml 仅占位。

### 3.3 §A 托管中间件残留（只记录，不做假实现）
AUDIT_K8S §A 两项阻断（MySQL/Redis/RMQ/Nacos 高可用无托管交付、registry.example.com 占位 8 处无 imagePullSecrets/CI）不在本计划内"用本地清单伪造"；R-K8S 卡仅保留：useSSL=false→TLS 占位开关（待托管地址确定后落值）、数据目录 deploy/rocketmq/data 运行期数据移出部署目录、heapdump 持久路径。真实 StatefulSet/备份/registry/CI 需基础设施项目承接。

## 4. 任务卡

### 卡 R-B1 — Druid 有界连接池基线（W1）

- **现状证据**：7 业务服务 datasource 仅 driver/url/username/password/type 五项（shop-order-service/src/main/resources/application.yml:9-15，其余同构）；全仓无 initial-size/max-active/max-wait/validation-query/keep-alive 配置，实际运行 Druid 出厂默认 maxActive=8、maxWait=-1（拿连接无限阻塞）、无保活校验；无 stat/wall filter 与 slow-sql-millis；Druid 1.2.22 经 druid-spring-boot-3-starter 自动装配。PERF §3 雪崩链第 1 环即 8 连接被在途请求占满。
- **精确文件清单**：
  - 新增 shop-framework/.../datasource/DruidBaselineCustomizer.java（BeanPostProcessor/自定义 DruidDataSourceCustomizer 对 DruidDataSource 注入 C12 默认值，确保未配置服务也获得有界基线）；
  - 新增 shop-framework/.../datasource/DruidMetricsRegistrar.java（活跃/等待/慢查询 Micrometer 指标）；
  - shop-framework/pom.xml（druid starter 已在，micrometer 随 actuator 传递，实施时以现状为准）；
  - 7 业务服务 application.yml（仅分档覆盖：max-active pay/settlement/order=30 其余=20；kind profile=10）；
  - deploy/kubernetes/00-namespace-config.yaml（prod 分档值）。
- **核心步骤**：①Customizer 在 DataSource init 前注入有界默认（maxWait=3000、validationQuery=SELECT 1、testWhileIdle/keepAlive、evictor 60s/300s、phyTimeout 30m、removeAbandoned 300s）；②开启 stat,wall filter 与 slow-sql-millis=1000；③指标挂到现有 /actuator/prometheus（exposure 已含 prometheus，order application.yml:59）；④prod 下私设 maxWait=-1 fail-fast（dev 仅 warn）。
- **单测**：①裸五项配置经 Customizer 后全部为基线值；②服务覆盖 max-active=30 生效、max-wait=-1 被拦截；③stat filter 产生慢 SQL 计数；④contextLoads 不与现有 Actuator 冲突。
- **故障注入/验收**：制造 MySQL 断连恢复后首次借连接不报错；第 maxActive+1 并发在 3s 快速失败而非永久挂死（线程 dump 无无限 waiting）。E2E：shop-e2e 全量回归。
- **残留**：不开启 stat-view-servlet 监控页（无鉴权暴露面），指标走 Prometheus。
- **风险回归面**：maxActive 提高后 MySQL max_connections 需同步评估（§A 托管项，ConfigMap 注释）；wall filter 灰度观察拦截情况。

### 卡 R-B2 — Feign 统一超时/连接池/ErrorDecoder/熔断（W1，支撑售后 R-B6）

- **现状证据**：5 个 @FeignClient 裸注解（shop-api 下 order/pay/product/user/marketing Client）；全框架唯一 Feign 定制是 shop-framework/.../feign/FeignRequestInterceptor.java（仅两个 RequestInterceptor，:23/:34）；无 Request.Options/ErrorDecoder/Retryer、无 feign-hc/okhttp，实际 JDK HttpURLConnection + connect10s/read60s/NEVER_RETRY；order/pay 各一份逐字重复 FeignResults；PERF §3 无熔断致在途请求无限堆积。
- **精确文件清单**：新增 framework/.../feign/FeignResilienceConfiguration.java、ShopErrorDecoder.java、FeignResults.java、FeignResilienceProperties（shop.feign.*）；shop-framework/pom.xml 增 io.github.openfeign:feign-hc5 与 resilience4j-feign（spring-cloud-starter-circuitbreaker-resilience4j，版本随 BOM）；ShopService.java @Import（现 :35 含三个类）增配置类；业务侧删两份重复 FeignResults 改 import（执行归业务卡）；7 服务 yml 仅放需放宽 client 的覆盖键。
- **核心步骤**：①HttpClient5 池 200/单路由 50/TTL30s；②Options 默认 connect2s/read3s，`shop.feign.clients.<name>.read-timeout` 覆盖；③ShopErrorDecoder：5xx/IO/空体/非 Result→BizException(DEPENDENCY_FAIL)；Result{code!=0} 业务码透传；Result{data=null}（非 Void 泛型）→DEPENDENCY_FAIL（**框架永不返回 null，根治 R-B6 三处**）；404→NOT_FOUND；④Resilience4j 熔断+bulkhead 按 clientName 自动配置（C13 默认：100 窗口/50%/10s open/慢调 2s 占 60%/并发 30）；⑤默认 Retryer.NEVER_RETRY，GET 可按 client 开 attempts=2 backoff200ms，POST 永不重试。
- **单测（WireMock）**：①下游 sleep5s → 3s 超时 DEPENDENCY_FAIL；②500/空体/HTML 三态均 DEPENDENCY_FAIL；③业务 code 原样透传；④熔断 open/half-open 行为；⑤连接复用；⑥GET 重试一次、POST 零重试；⑦unwrap(null) 必抛。
- **故障注入/验收**：chaos 增"下游 kill/丢包"段：上游 p99 受 readTimeout 上界约束、线程不膨胀、恢复后自动闭合；**售后 R-B6 三处（AftersaleTimeoutServiceImpl:70-77、AftersaleMqServiceImpl:162-169、AftersaleServiceImpl:857-860）在业务卡删私有判空后，注入空体断言统一 DEPENDENCY_FAIL 且 MQ 可重试**。E2E：shop-e2e 全量 + 滚动重启不断流。
- **残留**：不提供统一静默 fallback（防掩盖依赖故障），业务需要各自 fallbackFactory；监控规则归 observability。
- **风险回归面**：3s read 可能卡掉现存慢调用——上线前按 Feign 指标筛 >2.5s 调用逐 client 覆盖；低 QPS client 配 minimumNumberOfCalls=10 防误判。

### 卡 R-B3+B12 — 网关限流、统一错误包裹体、快速失败（W2）

- **现状证据**：shop-gateway/pom.xml 仅 gateway/nacos-discovery/loadbalancer/actuator/jjwt；application.yml:18-61 七路由仅 StripPrefix=2，无 default-filters/限流；JwtAuthGlobalFilter（:41 起）只鉴权与洗头；无 ErrorWebExceptionHandler（grep 零命中）。PERF §3：40 TPS 档 order Pod exit137 后 Nacos 摘除窗口网关对死亡实例 Connection refused，回 792 次裸 500 非业务 JSON。服务层 @RateLimit 6 处是现存唯一防线。
- **精确文件清单**：shop-gateway/pom.xml 增 spring-boot-starter-data-redis-reactive（SC Gateway RequestRateLimiter 随 BOM）；新增 filter/ShopKeyResolver.java、error/ShopGatewayExceptionHandler.java（Ordered 高于 DefaultErrorWebExceptionHandler）、config/GatewayResilienceProperties.java；改 gateway application.yml（default-filters、httpclient connect2000ms/response5s、redis、路由限流键）。
- **核心步骤**：①Redis token bucket：全局 100/200 默认 + 路由桶（order=20/pay=10 起步，以 PERF 拐点校准）+ 主体维度（登录 X-User-Id/匿名 XFF 首段，网关覆写防伪造），键前缀 rl:{env}:{route}:{principal}；Redis 故障默认且锁定 deny；②异常处理器对 ConnectException/PrematureClose/5xx/超时/429 统一输出 {code,message,data}+X-Request-Id，HTTP 语义化（429→10007、503→DEPENDENCY_FAIL、504→DEPENDENCY_TIMEOUT、500→SYSTEM_ERROR），message 禁含 IP/堆栈；③GET 对 502/503/Connect refused 重试 1 次，POST/PUT/DELETE 零重试，关闭 loadbalancer retry 防双重；④服务层 @RateLimit 保留为二道，不动 6 落点。
- **单测**：①KeyResolver 三态；②超限 429 包裹体；③下游 Connect refused/500/慢响应分别 503/500/504 且响应体严格业务结构、无 Netty 细节；④GET 重试一次 POST 不重试；⑤Redis 挂限流器 deny。
- **验收（PERF §3 强制）**：kind 复跑 20/30/40 TPS：20 维持 exit0；30/40 档全部失败响应为业务包裹体（无裸 JSON）；40 档 429 主导而非连接堆积；pod-kill/滚动期间错误体恒定；ha-check 增加错误体契约断言。
- **残留**：单用户/单 SKU 热点限流首版靠路由桶+服务层秒杀 1/3s 兜底，Sentinel 热点参数作后续增强，不引双控制面。
- **风险回归面**：全局桶过紧误伤——键全可配、prod 值压测后定；GET 重试白名单只列显式安全路径。

### 卡 R-B4 — Redis 故障策略矩阵与 chaos 写链路覆盖（W2）

- **现状证据**：软降级已有：framework IdGenerator @PostConstruct catch 本地推导；product SpuDetailCache 双兜底；marketing SeckillStockClient 降级 DB CAS（不凭空造库存）；coupon UK/CAS 无锁。硬依赖 fail-closed：order OrderNoGenerator（Redis INCR，:33/:67）；framework IdempotentAspect（:75/:119）；RateLimiter（:41/:57 无 catch）；DistributedLockTemplate（:19/:48，6 使用点）。chaos.sh:35 仅 GET 商品列表（不触 Redis），:169 把窗口失败标"预期降级"，写链路零覆盖。
- **策略矩阵（冻结到 RUNBOOK/评审 checklist；业务执行归 FUNDS/TRADE）**：

| 路径 | 策略 | 动作 |
|---|---|---|
| 网关桶/服务层 @RateLimit | 必须 fail-closed | 拒绝优于漏限；C14 已锁，服务层维持抛错 |
| 幂等切面 | 必须 fail-closed | 无幂等窗口放行=重复扣款；维持，异常语义统一为 DEPENDENCY_FAIL |
| 分布式锁（支付/退款/清算/提现/库存/签到） | 必须 fail-closed | 无锁破坏互斥；维持，"Redis 挂=拒绝不超卖" |
| 秒杀库存 | fail-open 到 DB CAS | 现状保留（含"不凭空造库存"注释） |
| 订单号生成 | fail-closed→补本地兜底 | 复用 IdGenerator 本地推导（执行归 TRADE/FUNDS），降级本地号段 |
| 商品读缓存 | 允许 fail-open 回源 | 现状保留 |
| 账户/资金余额读 | 禁止 stale 缓存参与决策 | 只回源或拒绝 |

- **精确文件清单**：framework IdempotentAspect/RateLimiter/DistributedLockTemplate 异常统一包 BizException(DEPENDENCY_FAIL)（现状原生异常→10009，包装后可被映射 503 并分类告警）；framework 自动配置补 Lettuce connect-timeout/lettuce pool 有界/Redisson retryAttempts,retryInterval 基线（现 yml 仅 timeout=3000ms）；deploy/loadtest/chaos.sh 增 POST 登录/下单/支付流量段（随机幂等键）；RUNBOOK/ACCEPTANCE 写"拒绝服务但不超卖"口径。
- **单测/故障注入**：Redis 停容器后幂等/锁/限流返回 DEPENDENCY_FAIL；命令在 timeout 内返回不挂线程；chaos 写段恢复后成功率回基线 + 库存/账户零超卖零双花核对。
- **残留**：OrderNoGenerator 本地号段执行在业务卡。
- **风险回归面**：grep 业务侧 `catch (RedisSystemException|RedisConnectionFailureException` 确认包装异常不被既有 catch 误吞（实施时以现状为准）。

### 卡 R-B5+Z1 — 事务外调用框架规约（模式卡，W2；业务执行归 FUNDS）

- **范围声明**：本卡只出 framework 可复用组件与规约，**不点名改造业务方法**（支付扫单自调用、退款事务内锁+渠道 HTTP、PayTimeoutListener 单事务 4 Feign、售后五事务入口、settlement 20 个裸 @Transactional、对账大事务等执行点全部归 GAP_PLAN_FUNDS/TRADE，引用本卡组件，不重复描述）。
- **现状证据**：全仓 152 个方法级 @Transactional 无 readOnly/timeout；settlement 20 个裸 @Transactional 无 rollbackFor（Z1）；正面样板已存在：order OrderCreateServiceImpl 无 @Transactional，TCC 三步 Feign 事务外，落库走独立 Bean OrderPersister（事务内仅 insert+outbox）；marketing MarketingAppService:82-93 tryLock→代理调事务方法→finally 事务外解锁；framework OutboxRelayDispatcher 每消息独立事务。
- **精确文件清单（framework 新增，可选采用）**：
  - `shop-framework/.../tx/TransactionalTemplate.java`：封装"事务外预取 → 独立 Bean 短事务落库（仅 DB+outbox）→ 事务外远程调用 → 独立事务 CAS 终态"的回调模板（TransactionTemplate 编程式，传播级别显式）；
  - `shop-framework/.../tx/SelfInvoker.java`（AopContext.currentProxy() 或注入 ObjectProvider 自 Bean 的封装）：解决 this 自调用致 @Transactional/REQUIRES_NEW 失效（scanTimeout/handleDiff 模式）；
  - `shop-framework/.../tx/RequiresNewBatchExecutor.java`：逐元素独立事务批处理模板（单条失败不标记外层 rollback-only，结果分 success/failed 列表，配套重试 Job 接管失败项）；
  - 规约文档（framework 内 package-info 或 ARCHITECTURE 补充，不新建游离 md 之外的业务文档）。
- **核心规约（评审 checklist 硬项）**：
  1. 所有 @Transactional 必须 `rollbackFor = Exception.class`（消除 checked exception 不回滚缺口，Z1）；只读方法标 readOnly=true；批量/长事务显式 timeout（如 `@Transactional(timeout=10)`）；
  2. 锁包事务、不被事务包锁：tryLock 在事务外，finally 在事务提交后解锁（参照 MarketingAppService）；
  3. 事务内只允许 DB 操作与 OutboxPublisher 登记（26 处 outbox 用法保持），禁止 Feign/MQ 直发/HTTP/Redis 长操作；远程调用一律事务外预取或落 PROCESSING 状态提交后再调，再独立事务 CAS 终态；
  4. 批处理分页 + 逐元素 REQUIRES_NEW（必须经独立 Bean/代理，禁止同类 this 调用）；
  5. MQ 消费事务同规约：事件体能带的数据不回查（P3-1）；必须回查的事务外先查后开事务（settlement PaymentSucceededListener、aftersale claimInsurance 执行归 FUNDS）。
- **单测**：①RequiresNewBatchExecutor 第 N 条抛错时前 N-1 条提交、失败项入失败列表、外层无 rollback-only；②SelfInviker 走代理（验证内部调用传播级别生效，用嵌套 REQUIRES_NEW 挂点断言独立连接/事务）；③TransactionalTemplate 远程调用异常时本地无半成品行（PROCESSING 状态可见）。
- **验收**：业务卡合入后用连接池占用指标（R-B1）验证支付/关单高峰事务持有时长 p95 显著下降；chaos 在"远程慢响应"注入下 DB 连接/行锁不长占（innodb_trx 抽查脚本）。
- **残留/风险**：编程式模板与注解式并存期需评审防止两套风格扩散；模板不强制替换全部存量，只要求高风险链路（FUNDS 卡点名）采用。

### 卡 R-MQ — MQ 平台侧：eventId 兜底/孤儿 topic/DLQ 台面/outbox 自检/调度/锁前缀/探针/Nacos（W2）

- **现状证据**：
  - P2-2：null eventId 六套策略——order 合成 `noid:topic:bizNo`（MqConsumeService:23-27），user 抛 IllegalArgumentException（:25-27，不在 MqErrorPolicy 终态集→重试16次进DLQ），其余四库消费流水 eventId NOT NULL 无分支；框架 MqConsumerRegistrar.dispatch(:166-179)/invoke(:181-199) 不接触 eventId（勘误 K2）；
  - Z3：MqProperties.consumeMaxAttempts=16（:19）为死键，buildOnce(:140-146) 未接线，行为由 broker 组默认决定（K3）；
  - Z4：%DLQ% 无消费者/重放/告警；OutboxRelayJob.requeueSuspended 对 suspend_count>=3 仅 log.error（P3-2）；manualRequeue(id) 已有但无台面；
  - P2-3：9 个无消费者 topic（含 shop_deposit_alert/shop_withdraw_result/shop_points_changed 等，见 C17 清单），去向未声明；
  - Z5：t_mq_outbox 仅 PK(id)+idx_status_deliver，biz_key 无 UK（V3 实证）；
  - Z6：ShopService @EnableScheduling（:35）无自定义 TaskScheduler→Spring 默认单线程；
  - Z7：ShedLockConfig 硬编码 "shop-scheduler"（:20），无环境隔离；
  - Z8：探针仅见 yml exposure（order:59-61），readiness/liveness 组未显式定义，默认 readiness 含 db/redis/mqConsumers；k8s 探针路径已打 /health/liveness|readiness（10-services.yaml:82-92 等）；
  - Z11：Nacos 不可用启动/运行行为未验证；注册循环无限退避（MqConsumerRegistrar:77-130 同款模式可参照）；
  - P3-3：无 Flyway，V3/V5 手工执行，新环境漏跑全业务阻断；
  - P3-1：消费事务内 Feign（settlement/aftersale）——执行归 FUNDS，本卡只出规约（见 R-B5）。
- **精确文件清单**：
  - 新增 framework/.../mq/EventNormalizer.java（或 MqConsumeContext + dispatch 装饰层）；改 MqConsumerRegistrar（invoke 前注入标准化 eventId，键取 MessageView keys/headers，兜底 `noid:{topic}:{bizKey|msgId}`，配置 shop.mq.event.synthetic-enabled）；
  - 改 MqProperties：consumeMaxAttempts 接线 buildOnce（builder 无对应能力则删键并由 create-topics.sh 显式 -r 16，二选一，禁止死配置留存）；
  - 新增 framework/.../mq/MqMetrics.java（shop_mq_consume_dead_total、shop_mq_consume_retry_total、shop_outbox_suspended_total、shop_mq_topic_no_subscriber）；invoke 终态分支（:189-196）与反序列化毒丸分支（:169-175）埋点；
  - 新增 framework/.../mq/OutboxAdminEndpoint（actuator @Endpoint，shop.mq.admin.enabled=false 默认）：suspended 列表 + requeue（复用 OutboxRelayJob 既有 manualRequeue）；
  - 新增 framework/.../outbox/OutboxSchemaHealthCheck（ApplicationRunner，prod failfast/dev log）；
  - 新增 framework/.../job/SchedulerConfig（ThreadPoolTaskScheduler，shop.scheduler.pool-size 默认2）；
  - 改 ShedLockConfig：RedisLockProvider(env) 取 shop.env；
  - 新增 framework/.../health/HealthGroupsConfig（readiness: readinessState,db,redis；liveness: livenessState；mqConsumers 只进独立 health 组件不进组）；
  - 新增 framework/.../bootstrap/NacosResilienceVerifier（启动/运行验证工具，见下）；
  - create-topics.sh 按 C17 追加；sql/common/V6 按 §3.1；
  - 业务侧删 order/user 私有 eventId 分支（执行归各业务卡，本卡定稿框架 API）。
- **核心步骤/验收点**：
  - eventId：单测构造无 keys 消息→合成键确定性可重放（同消息两次合成结果一致，保证重试幂等命中同一消费流水行）；user 现状 IllegalArgumentException 路径消失；
  - 重试：启动日志回显各订阅组实际 maxReconsumeTimes（mqadmin 或 broker 回包），单测/部署断言=16；
  - DLQ 台面：actuator endpoint 列 suspended/requeue 的集成测试；create-topics.sh 增 DLQ 计数巡检段，ha-check 在制造一条毒丸后断言巡检 exit 非零且指标 +1；告警规则名冻结归 observability；
  - 孤儿 topic：ACCEPTANCE 去向声明表（C17）为发布门禁——9 topic+2 个 tag 特例逐行有结论；框架巡检指标在无订阅组时可观测（能力不足则日志+文档降级，不假实现）；
  - 调度器：单测两 @Scheduled（一个 sleep 5s）并发执行不互相阻塞（单线程下会串行）；
  - ShedLock：local 与 kind 环境同名任务锁键不同（redis keys 断言前缀）；
  - 探针：MQ broker 停时 readiness 仍 200（不摘流）、liveness 200；MySQL 停时 readiness 503；k8s 滚动不被 MQ 抖动阻塞；
  - Z11：NacosResilienceVerifier + chaos 段——①启动时 Nacos 已停：应用在退避后可启动（HTTP 健康、本地配置可用）或按文档明确 fail-fast 口径（二选一并固化，推荐运行期容错/启动短超时）；②运行中 kill Nacos：已注册服务间 Feign 仍走本地服务列表可用、新实例无法注册被监控发现；恢复后自动重注册。ha-check 增这两个场景；
  - P3-3：删表/新库模拟下 prod 启动 failfast 且错误信息含 V3/V5/V6 指引；dev 仅 WARN 不阻断。
- **E2E/chaos**：broker 滚动重启期间持续下单，outbox status=0 积压、恢复后自动追平（现有慢车道语义保持）；毒丸消息不阻塞同组其他消息（消费线程隔离由 broker 保证，断言 lag 恢复）。
- **残留**：DLQ 自动重放（按原因分类批量重投）不做，首版只支持手工 requeue + RUNBOOK；仓库外消费方 SLA 内容需业务方填写，平台只卡门禁。
- **风险回归面**：readiness 组摘除 mqConsumers 后，broker 全挂时 Pod 不被摘流——这是刻意语义（HTTP 与 MQ 解耦，注册循环本就为容错），但需在 RUNBOOK 写明"MQ 故障靠消费堆积告警而非 Pod 重启发现"。

### 卡 R-Z2 — 中间件口令外部化与 prod fail-fast 复核（W1）

- **现状证据**：7 服务 yml root/root 明文（order application.yml:12-14 实证）；JWT/内部令牌 prod 强制注入已闭环（ShopSecretEnvironmentValidator:44-53 + SecretStrength 拦空/<16/CHANGE_ME/默认值；网关 GatewaySecretValidator 同构；JwtAuthGlobalFilter:70 与 gateway yml:64 同一 dev 默认串）；15-secret-template.yaml 与 create-secrets.sh 已存在；k8s §4.4 审计判定"无硬编码生产密钥达标"，API 审计 §7.3 留 H-3 部署侧复测项。
- **精确文件清单**：7 业务服务 application.yml（datasource username/password、redis password、后续 MQ/Nacos 鉴权键全部 `${ENV:dev默认}` 化）；改 ShopSecretEnvironmentValidator（增 DB 弱口令校验，键 shop.security.require-env-db，prod 强制：口令为 root/空/长度<8 拒绝启动）；deploy/kubernetes/15-secret-template.yaml（补 SHOP_DB_USER/SHOP_DB_PASSWORD/SHOP_REDIS_PASSWORD 占位）、create-secrets.sh（生成/读取逻辑与不覆盖提示）、00-namespace-config.yaml（prod 置 require-env-secrets=true、SHOP_ENV=prod）。
- **核心步骤**：①env 化但保留 dev 本地裸跑默认（不破坏 start-apps/kind 既有流程）；②prod 校验扩到 DB 口令；③密钥零明文入 git（模板仅占位）；④broker/Nacos 鉴权开启属 §A 托管事项，本卡只预留 `${SHOP_MQ_*}`/`${SHOP_NACAS_*}` 键位，不做假鉴权部署。
- **单测**：①prod profile + 默认 JWT/内部 token/root 口令 → 上下文启动失败（含既有 H-3 回归断言）；②prod + 全部合法注入 → 启动成功；③dev profile 默认值不拦截；④SecretStrength 占位符用例不回归。
- **验收**：ha-check grep 守护"prod ConfigMap 不含明文口令/默认 JWT 串"；kind 流程仍一键可用（豁免 C 类不动）。
- **残留/风险**：旧环境升级需先建 Secret 再滚动，部署 RUNBOOK 补顺序；env 化改动面是全部 7 份 yml，机械修改但需逐服务 smoke。

### 卡 API-P — 全局异常契约补全（W1）

- **现状证据**：GlobalExceptionHandler.java（共68行）：ConstraintViolationException 直接 e.getMessage()（:50，泄露 `lockStock.arg0.points` 式方法签名，E-4）；无 HttpMessageNotReadableException/MethodArgumentTypeMismatchException handler（E-1，全落 :63 兜底 10009+error 日志）；仅 401/403 设 HTTP 状态，参数错误实际回 200（但 E-3 风格冻结不改）；全仓无 @Validated（E-6，参数级约束静默失效隐患）；无请求体上限显式配置。
- **精确文件清单**：改 shop-framework/.../web/GlobalExceptionHandler.java；改 shop-framework/.../web/WebMvcConfig.java（确认 MethodValidationPostProcessor 生效，必要时 @Bean 显式注册）；framework 配置基线（C18 multipart/header 上限）；deploy/kubernetes/20-tls-ingress.yaml（Ingress body 大小注解与应用上限对齐）；业务 Controller 类级 @Validated 与 IAE→BizException 逐点修复归各业务计划（E-2 ChannelLimits:75-77、AdminController:80 等只给规则不代改）。
- **核心步骤**：①新增 HttpMessageNotReadableException（400+固定文案"请求体格式错误"，不回显 Jackson 原始 message）、MethodArgumentTypeMismatchException（400+"参数类型错误: {name}"）、HttpMediaTypeNotSupportedException、MissingServletRequestPartException 四个 handler，全部 setStatus(400)+code=10001（PARAM_INVALID，码值实施时核对 ErrorCode 现状）；②ConstraintViolation 改为 violation.getMessage() 模板拼接，propertyPath 只取末段参数名；③请求体/头/multipart 上限基线（C18，默认 body 走 connector maxPostSize/swallowSize，multipart 10MB/20MB）；④@Validated 推广方案：framework 保证处理器注册，业务类级注解列入业务卡 checklist。
- **单测**（MockMvc，framework 测试模块）：①坏 JSON/枚举传字符串/body 类型错 → 400+10001 且响应无类名；②/orders/abc（Long 传字母）→ 400 且 message 无 requiredType/value 回显；③@RequestParam 约束触发时 message 无 `arg0/方法名`；④401/403 语义不回归；⑤BizException 普通业务码仍 HTTP200+body.code（E-3 冻结断言，防误改）；⑥超大 body 返回 413/400 而非连接异常。
- **E2E**：shop-e2e 中既有错误用例断言（body.code 风格）全绿；新增坏请求样例进 smoke。
- **残留/风险**：IAE 不做框架级 400 兜底（防吞真 bug），依赖业务逐点改，短期内个别点仍回 10009——在业务卡清零前属已知残留；setStatus(400) 可能影响只按 HTTP 200 判断成功的简陋客户端，但 body.code 契约不变，且 shop-e2e 全量验证。

### 卡 R-K8S — 部署硬化（W3；§A 只记残留）

- **现状证据**：HPA 7/8 网关缺失（10-services.yaml 中 HorizontalPodAutoscaler 出现 7 次，无 shop-gateway）；30-networkpolicy.yaml policyTypes 仅 Ingress（:31-35 同命名空间全放通、无 egress/默认拒绝）；20-tls-ingress.yaml 有 tls 段（:36-39）但无最低版本固化；broker.conf.tpl:10-11 autoCreate 双 true；清单无 imagePullPolicy 显式声明；7 业务 Service 缺失（grep `kind: Service` 0 命中，排障强依赖 Nacos）；JVM 初始堆与 memory request 失配（K5，数值实施核算）；无显式 securityContext；ha-check/build 脚本有 macOS IP/端口回退/文案等小项（§B-10）。
- **精确文件清单/步骤**：
  1. 网关 HPA：10-services.yaml 为 shop-gateway 增 Deployment（现网关无 Deployment 段落，grep 零命中，需连同副本/资源/PDB 一起补，PDB 是否补按 §5.1 现状"8/8"核对——网关 PDB 可能已在他处，实施时以现状为准）+ HPA（CPU/QPS 指标，阈值与业务 HPA 同口径）；
  2. NetworkPolicy：30 追加 default-deny 基线（namespace 级 default-deny-all 或每 PodSelector policyTypes [Ingress,Egress]），egress 白名单显式列：同命名空间服务端口（8081-8087/网关 8080）、DNS 53（kube-dns）、MySQL/Redis/RMQ/Nacos 网段与端口（地址来自 ConfigMap，§A 托管落地前用占位+注释）、NTP/镜像仓库按需；同命名空间规则从 podSelector:{} 收紧到端口；kindnetd 不支持继续走 kind/40-kind-networkpolicy.yaml 豁免（不动）；
  3. JVM/资源：逐服务核算 env JVM_ARGS 的 `-XX:InitialRAMPercentage/MaxRAMPercentage` 与 requests/limits（业务初始堆不得 > memory request，建议 InitialRAMPercentage 使初始堆 ≤ request 的 65%，Max ≤ limit 的 75%）；网关同算；
  4. securityContext：Pod 级 runAsNonRoot=true、fsGroup 非零、seccompProfile RuntimeDefault；Container 级 allowPrivilegeEscalation=false、capabilities drop ALL；readOnlyRootfilesystem 需先解决日志/heapdump/tmp 写路径（emptyDir 挂 /tmp 后再开，§B-5）；
  5. Ingress TLS：20-tls-ingress.yaml 加 ssl-proxy/ALB 对应注解或 IngressVersion tls-version: TLSv1.2_2021（最低 1.2，优先 1.3），显式禁用 1.0/1.1；cert-manager 生产路径保持（豁免自签仅 kind）；
  6. broker：broker.conf.tpl autoCreate 改 `${RMQ_AUTO_CREATE_TOPIC:true}` 插值，prod 清单/compose 显式 false；create-topics.sh 成为部署前置（C17）；deploy/rocketmq/data 运行期数据移出部署目录（改 .gitignore + README 指明卷路径）；
  7. 镜像：全部容器显式 imagePullPolicy: IfNotPresent（kind load 场景必需）+ digest/不可变 tag 策略（真实 registry 属 §A，本卡先在清单加 policy 与注释位）；
  8. 7 业务 Service：为排障直连补 ClusterIP Service（端口映射 actuator/业务端口），不改服务注册发现主路径；
  9. 脚本小项（§B-10）：ha-check.sh macOS IP 发现（:51）、ingress 端口回退（:156）、/16 网段假设（:82）改为参数化/多平台回退；build-and-load-kind.sh 末尾 apply 提示与"Secret 不覆盖"歧义文案修正；
  10. useSSL=false（§B-1）：JDBC URL useSSL 改为 `${SHOP_DB_USE_SSL:false}` 插值，prod 托管地址落地后置 true（同步评估 Redis/RMQ/Nacos TLS，占位键随 R-Z2 预留）；heapdump 持久收集（§B-6）挂 emptyDir/PVC，不落容器临时层。
- **验收（ha-check 扩展）**：①HPA 压测扩缩容（网关 20→40 TPS 段有副本扩张事件）；②NetworkPolicy：临时 Pod 对未授权端口/外网连接被拒，白名单内 DNS/中间件可达；③非根用户进程断言（ps/exec 检查，或只读 securityContext 静态 grep 守护，沿用 ha-check 基线 grep 模式）；④SSL Labs 等价检查/openssl 握手断言 TLS1.1 被拒；⑤prod 模板渲染后 grep autoCreate=false、IfNotPresent 全命中、无 useSSL=false 字面量；⑥Service 直连 actuator 200。
- **kind 豁免（不动）**：C 类三项（单节点反亲和 JSON Patch、mock 双开关+开发密钥、kind 自签 TLS）保持。
- **§A 残留（只记录不伪造）**：托管 MySQL/Redis/RMQ/Nacos 高可用与备份切换、registry.example.com 替换 + imagePullSecrets + CI 签名链为上线硬前提，本卡不为其在本仓造假 StatefulSet/假 registry；所有相关清单项以占位+注释+"托管落地后翻牌"形式存在，并在 ACCEPTANCE 残留表列示。
- **风险回归面**：default-deny egress 最易误伤（NTP/OCSP/DNS/日志采集/Sentinel dashboard:8858/Nacos 寻址），必须在 kind 先以 audit 模式（policy 只记录）跑一轮全量 e2e+chaos 再转 enforce；readOnlyRootfs 会打挂依赖相对路径写文件的组件，需逐服务 smoke。

## 5. 执行顺序

**W1 — 安全带（纯框架配置/契约，低风险高收益，先行；对应 AUDIT_RESILIENCE §五 第 1 波）**
1. R-Z2（env/Secret 键位 + prod fail-fast 扩 DB 校验）——所有后续配置键的 prod 注入通道先行；
2. R-B1（Druid 有界基线 Customizer，服务 yml 近乎零改动）；
3. R-B2（Feign 连接池/Options/ErrorDecoder/熔断；同步定稿 framework FeignResults）——**必须先于售后 R-B6 业务修复**，业务卡依赖"框架永不返回 null"；
4. API-P（GEH 400 契约 + 签名泄露 + 体上限；BizException 200 语义冻结）；
5. W1 出口门禁：shop-e2e 全量 + 慢调用/慢 SQL 日志筛查（R-B2 回归面）+ H-3 prod fail-fast 单测。

**W2 — 入口削峰、故障语义与 MQ 平台化（依赖 W1 配置键定型）**
6. R-B3+B12（网关限流 + 业务包裹体 + 超时/重试；C14/C19）；
7. R-MQ（eventId 标准化 → Z3 重试接线 → Z6 调度器 → Z7 锁前缀 → Z8 探针分组 → P3-3 自检 → Z4 DLQ 指标/台面 → Z11 Nacos 验证 → P2-3 孤儿 topic 声明门禁；V6 DDL 同期发布）；
8. R-B4（异常语义包装 + Lettuce/Redisson 基线 + chaos 写链路 fail-closed 段）；
9. R-B5+Z1（framework 事务模板/规约定稿）→ 交付 FUNDS/TRADE 卡引用，业务大事务拆改与本计划并行但合入顺序在框架组件之后；
10. W2 出口门禁：PERF §3 容量扫描在最终镜像重跑并更新结论表（20/30/40 TPS，包裹体断言、429 削峰断言）；broker/Redis/Nacos 三类 chaos 全过；孤儿 topic 声明表 100% 填写。

**W3 — 部署硬化（依赖全部配置键定终值）**
11. R-K8S 顺序：先 IfNotPresent/Service/脚本小项/JVM 调参（低风险）→ 网关 HPA+PDB 补齐 → TLS 版本 → securityContext（先 emptyDir 后 readOnlyRootfs）→ broker autoCreate 翻牌（create-topics.sh 前置就绪后）→ NetworkPolicy（**kind audit 一轮 e2e+chaos 后再 enforce**）；
12. 全程不动 kind C 类豁免；§A 托管项只维护占位与 ACCEPTANCE 残留表，不做假实现。

跨计划协作接口：
- FUNDS/TRADE：R-B2 ErrorDecoder 定稿后删除售后 3 处 null 误判（R-B6）与两份 FeignResults；R-MQ EventNormalizer 定稿后删 order/user 私有 eventId 分支；R-B5 模板用于 pay 三处、PayTimeoutListener、settlement 20 事务、对账大事务；P3-1 两个消费执行点随 FUNDS 改；
- USER 及各业务：@Validated 类级注解、IAE→BizException（E-2）随各业务计划清零；
- observability：本计划冻结的指标名（druid/feign/resilience4j/gateway 5xx,429/shop_mq_*/shop_outbox_*）为告警规则输入，规则文件归 observability 波次。

