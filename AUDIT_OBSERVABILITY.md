# 可观测性生产就绪审计报告（8 服务）

- 审计范围：shop-gateway、shop-user-service、shop-product-service、shop-marketing-service、shop-order-service、shop-pay-service、shop-settlement-service、shop-aftersale-service（支撑模块 shop-framework / shop-common / shop-api，部署目录 deploy/）
- 技术基线：Spring Boot 3.2.5（根 pom.xml:36）、Spring Cloud、Micrometer 1.12.5、RocketMQ 5.x 客户端、Nacos、Sentinel、Druid 1.2.22 连接池
- 审计方式：只读静态审计；Micrometer 默认直方图行为已用 micrometer-core 1.12.5 + micrometer-registry-prometheus 1.12.5 实测验证
- 审计日期：2026-09-17

---

## 1. 指标（Metrics）

### 1.1 actuator/prometheus 端点暴露

8 个服务全部引入 actuator，7 个业务服务（经 shop-framework）引入 `micrometer-registry-prometheus`：

- 依赖：`shop-framework/pom.xml:31-38`（actuator + micrometer-registry-prometheus）
- 暴露配置（8 份配置完全一致，仅暴露 `health,prometheus,info`，开启探针）：
  - `shop-gateway/src/main/resources/application.yml:67-76`
  - `shop-user-service/src/main/resources/application.yml:59-68`
  - `shop-product-service/src/main/resources/application.yml:55-64`
  - `shop-marketing-service/src/main/resources/application.yml:55-64`
  - `shop-order-service/src/main/resources/application.yml:55-64`
  - `shop-pay-service/src/main/resources/application.yml:55-64`
  - `shop-settlement-service/src/main/resources/application.yml:55-64`
  - `shop-aftersale-service/src/main/resources/application.yml:55-64`
- Prometheus 抓取 8 个目标，均走 `/actuator/prometheus`：`deploy/prometheus/prometheus.yml:5-45`

**严重问题：网关的 prometheus 端点实际不可用。** `shop-gateway/pom.xml:30-33` 只有 `spring-boot-starter-actuator`，**没有** `micrometer-registry-prometheus`（网关不依赖 shop-framework），因此 `/actuator/prometheus` 无对应端点、返回 404，`deploy/prometheus/prometheus.yml:6-10` 的 shop-gateway 抓取任务必然失败，网关的 JVM/HTTP 指标全部缺失。

**标签不匹配导致 Grafana 全看板无数据（见 §5）：** 所有 application.yml 均未配置 `management.metrics.tags.application`，抓取配置打的目标标签是 `app` 而非 `application`（`deploy/prometheus/prometheus.yml:10,15,20,...`），但看板全部按 `application` 标签过滤。

### 1.2 自定义 Micrometer 业务指标

对全部源码 grep `MeterRegistry / Counter / Timer / @Timed / Metrics. / Gauge`：**命中数为 0**。以下业务关键点均无指标埋点，只能靠日志或数据库事后排查：

| 业务点 | 现状 | 证据 |
|---|---|---|
| 限流命中 | 直接抛 BizException，无计数 | `shop-framework/src/main/java/com/shop/framework/ratelimit/RateLimitAspect.java:75-77` |
| MQ 发送成功/失败/耗时 | 仅 log.error，无 Counter/Timer | `shop-framework/src/main/java/com/shop/framework/mq/MqProducer.java:85,107,127,148` |
| MQ 消费失败/重试/进死信 | 无指标；只靠 broker 侧重试与 error 日志 | `shop-framework/src/main/java/com/shop/framework/mq/MqErrorPolicy.java:23-24`（注释自承"由对账/告警人工兜底"，但告警不存在） |
| 库存预警 | 落库 + log.warn + MQ 事件，无指标 | `shop-product-service/.../stock/service/impl/StockServiceImpl.java:306` |
| Outbox 积压/重放/耗尽 | 仅日志 | `shop-framework/.../outbox/OutboxRelayJob.java:78,84`（框架 outbox 包内无任何 MeterRegistry 调用） |
| 保证金不足 | 仅落库字段 + DEPOSIT_ALERT 事件，无指标 | `shop-settlement-service/.../deposit/service/DepositService.java:130-133` |
| 支付/退款/提现业务计数 | 无 | 全库 grep 无业务 Counter |

另外，限流异常经全局处理器后返回 **HTTP 200**（见 §2.3），连从标准 HTTP 指标中间接统计 429 都做不到。Sentinel 仅有 dashboard 传输（各 application.yml `spring.cloud.sentinel.transport.dashboard`），**未接 Prometheus 数据源**（pom 无 sentinel-metric/prometheus 适配依赖）。

### 1.3 HTTP 直方图（P99 数据基础）实测

所有 application.yml 均未配置 `management.metrics.distribution.percentiles-histogram` 或 SLO（全库 grep `percentiles|histogram|slo` 在 resources 下无命中）。用仓库实际依赖版本实测：Micrometer 1.12.5 默认 Timer 只导出 `_count / _sum / _max`，**不导出任何 `_bucket`**。因此看板上两个 `histogram_quantile()` 面板（P95/P99、热路径 P95）无数据，需显式开启 `management.metrics.distribution.percentiles-histogram.http.server.requests=true` 或配置 SLO 边界。

---

## 2. 日志（Logging）

### 2.1 日志格式：无 traceId / 无 MDC

7 个业务服务的 `logback-spring.xml` 完全相同（各 10 行）：

- `shop-user-service/src/main/resources/logback-spring.xml:1-10`（product/marketing/order/pay/settlement/aftersale 同名文件逐字相同）
- pattern（第 4 行）：`%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{40} - %msg%n`
- **无 traceId/spanId、无 X-Trace-Id、无任何 MDC 变量**；全库 grep `MDC.` 零命中。
- **网关连 logback-spring.xml 都没有**（`shop-gateway/src/main/resources/` 下仅有 application.yml），使用 Spring Boot 默认格式，同样不含 traceId（实际日志样例 `deploy/local/logs/shop-gateway.log`）。

`X-Trace-Id` 头常量存在（`shop-common/src/main/java/com/shop/common/constant/SecurityHeaders.java:16`），但唯一使用点是**网关入口剥离该头**（`shop-gateway/.../filter/JwtAuthGlobalFilter.java:90`）——没有任何代码生成、透传或落 MDC。即：外部传入的 trace 头被删掉，系统自己不产生 trace 头。`deploy/RUNBOOK.md:14` 宣称"日志：全链路 traceId（X-Trace-Id）"，**与实现不符**。

### 2.2 级别与滚动策略

- 7 份配置均为 root level=INFO、仅 ConsoleAppender、STDOUT 单 appender，无按环境（dev/prod）区分级别，无 JSON 结构化输出，无异步 appender，无采样。
- **无任何 FileAppender / 滚动策略**（grep `RollingFileAppender|SizeBasedTriggeringPolicy` 零命中）。本地脚本 `deploy/local/start-apps.sh:70,78` 用 `nohup java -jar > deploy/local/logs/<svc>.log 2>&1` 裸重定向，文件无大小/时间切割，会无限增长。K8s 形态依赖容器运行时日志轮转，尚可接受，但无集中式日志（无 logstash-encoder / Loki / ELK 依赖，pom grep 零命中），多副本下日志无法按请求聚合。

### 2.3 全局异常处理的日志与状态码问题

`shop-framework/src/main/java/com/shop/framework/web/GlobalExceptionHandler.java`：

- 业务异常：`log.warn` 带 code/msg（第 36 行），但**只有 UNAUTHORIZED/FORBIDDEN 设置 HTTP 状态**（第 31-35 行）；
- 参数校验异常（第 40-56 行）、方法不支持等：**直接返回，不打任何日志**；
- 兜底系统异常（第 63-67 行）：`log.error("系统异常", e)` 记录完整堆栈（不外泄给客户端，正确），但**没有设置 HTTP 500 状态码**，响应是 HTTP 200 + 体内 code=10009。

后果：
1. Grafana"5xx 错误率"面板对所有未捕获异常不可见——它们在 HTTP 指标里全部计为 200 SUCCESS，错误率监控事实失效；
2. 限流 429、参数错误 400 同样以 200 返回（`ErrorCode.java:30` TOO_MANY_REQUESTS=10007），无法用状态码做告警/统计；
3. 校验类异常无日志，恶意参数探测无痕迹。

### 2.4 敏感信息

未发现明文打印密码/密钥的日志（grep `log.*[Pp]assword` 等零命中），登录链路无请求体日志。脱敏做得相对完整：

- 手机号序列化脱敏（138****5678）：`shop-common/src/main/java/com/shop/common/jackson/PhoneMaskingSerializer.java:29-52`，用于 `ReceiverDTO.java:32`、`OrderDTO.java:47`、`UserDTO.java:47`；
- 收款账号/姓名脱敏（`**** **** **** 1234` / 张**）：`shop-settlement-service/.../support/AccountMask.java:16-38`，`WithdrawVO.java:23-40` 出参先解密再脱敏，库内密文存储（`sql/settlement/V2__settlement.sql:185-186`）。

残留风险点：

- MQ 关闭（dry-run）时 `MqProducer.send/sendRaw` 以 INFO 打印**完整事件体**（`MqProducer.java:75,117`），订单事件含收货人手机号/地址；当前 prod 默认 `shop.mq.enabled=true` 不触发，但配置一旦误关即 PII 落日志，且该日志无脱敏。
- 支付渠道回调原文整体落库 `t_pay_notify_log.notify_body`（`sql/pay/V2__pay.sql:160`），模拟渠道暂无卡号类字段，但接真实渠道后需确认渠道报文不含敏感要素。
- 日志无统一脱敏兜底（无正则转换/Jackson 脱敏日志框架），依赖各开发点自觉。

---

## 3. 链路追踪（Tracing）

**结论：全链路追踪能力为零。**

- 依赖：全部 pom grep `sleuth|micrometer-tracing|zipkin|skywalking|brave|otel` **零命中**；Spring Cloud 2023.x 已移除 Sleuth，而项目未引入 micrometer-tracing-bridge-* 任何桥接，也没有 zipkin/skywalking/otel exporter。
- Feign：`shop-framework/.../feign/FeignRequestInterceptor.java:22-48` 两个拦截器只透传 `X-Internal-Token` 与登录身份头（X-User-Id/Name/Type/MerchantId），**无 trace 头透传**。
- HTTP 入口：`AuthInterceptor.java:41-58` 只解析身份头，**无 MDC.put(traceId)**；没有任何 servlet Filter/WebFilter 生成请求 ID。
- MQ：生产端 `MqProducer.buildRaw()`（`MqProducer.java:157-173`）只设置 topic/body/tag/keys/deliveryTimestamp，**未写入任何 trace 属性**；消费端 `MqConsumerRegistrar` 接收 `MessageView` 后也无上下文恢复（properties 未读取）。RocketMQ 5 客户端自带的 telemetry 是 gRPC 链路遥测，与业务 trace 无关。
- 日志之间、HTTP 与 MQ 之间、跨 8 个服务之间没有任何关联 ID，单笔交易排障只能靠业务号（orderNo 等）人工 grep，异步/定时/死信场景基本无法串联。

---

## 4. 健康检查（Health）

### 4.1 探针与分组

- 8 份配置均 `management.endpoint.health.probes.enabled=true`（见 §1.1 行号），K8s 三探针配置正确：startup（120s 慢启动覆盖）/readiness/liveness 分别打 `/actuator/health/liveness|readiness`
  - 业务服务：`deploy/kubernetes/10-services.yaml:81-95`
  - 网关：`deploy/kubernetes/00-namespace-config.yaml:98-111`
- **但没有任何显式 health group 配置**（全部 yml grep `group` 零命中）。Boot 3.2 默认 liveness/readiness 组只含 livenessState/readinessState，db、redis、自定义 MQ 指示器**均不在组内**：
  - MySQL/Redis 故障时 `/actuator/health`（聚合）DOWN，但 `/actuator/health/readiness` 仍 UP，Pod 继续接流量；
  - `MqConsumerHealthIndicator.java:11-13` 的 Javadoc 自己写明"若部署将本指示器纳入 readiness 组"——即**未纳入**，MQ 消费者未就绪/掉线不摘流量。

### 4.2 自定义 HealthIndicator

全库仅一个：`shop-framework/.../mq/MqConsumerHealthIndicator.java:15-43`（bean 名 `mqConsumers`，按注册数/失败原因报 UP/DOWN）。无 Redis/MySQL/外部支付渠道自定义指示器。且该指示器只反映**启动期注册结果**（`allRegistered()` 一旦为 true 不再翻转，`MqConsumerRegistrar` 运行期消费者掉线无回传），对运行期 rebalance/消费者离线无感知。

### 4.3 暴露面

- 7 个业务服务：`show-details: when_authorized`（各 application.yml），未引入 Spring Security（自定义拦截器鉴权），when_authorized 实际等同 never，细节不外露，OK；actuator 与业务端口共用 808x，未设独立 `management.server.port`。
- 后端服务外网隔离做得好：Ingress 只指向网关 Service（`deploy/kubernetes/20-tls-ingress.yaml:43-50`），NetworkPolicy 对 7 个后端只允许网关/同命名空间/RFC1918 私网访问探针端口（`deploy/kubernetes/30-networkpolicy.yaml:36-48`）。
- **网关自身的 actuator 与业务流量同在 8080**，网关白名单把 `/actuator/health/**` 对匿名公网开放（`JwtAuthGlobalFilter.java:61-63`），经 `shop.example.com` 任意人可访问聚合健康（show-details 默认 never，仅返回 status，风险低但 Ingress 未做路径级限制）；`/actuator/prometheus`、`/actuator/info` 虽不在白名单，但**任意持合法用户 JWT 的请求**都能通过过滤器、由网关本地 actuator handler 直接响应（无路由匹配时 actuator 仍被处理），prometheus 端点当前因缺依赖 404，info 可被普通登录用户访问。建议 actuator 独立端口或在过滤器/Ingress 层整体拒绝 `/actuator/**`（仅放行 health 探针来源）。

---

## 5. 告警（Alerting）

### 5.1 现状清单

- Prometheus：`deploy/prometheus/prometheus.yml:1-45`，8 个静态抓取任务，15s 间隔；**无 `rule_files`、无 `alerting`/Alertmanager 配置**，`deploy/prometheus` 目录下不存在任何 rules 文件。
- docker-compose：`deploy/docker-compose.yml:144-170` 仅起 prometheus + grafana（admin/admin 弱口令，compose 第 160 行；文件头自定位为本地/验收环境），**无 alertmanager、无 node-exporter、无 mysql/redis/rocketmq exporter、无 grafana alerting provisioning**。
- Grafana：数据源与 dashboard provisioning 齐全（`deploy/grafana/provisioning/datasources/datasource.yml:1-7`、`dashboards/dashboards.yml:1-12`），单个看板 `deploy/grafana/dashboards/shop-overview.json`（7 面板，15s 刷新），**面板内无任何 alert 定义**。
- K8s 生产形态：`deploy/kubernetes/` 下**没有 Prometheus 部署、没有 ServiceMonitor/PodMonitor、没有抓取配置**（仅 networkpolicy 注释里假设"Prometheus 在节点网络内"），生产监控链路整体缺位。

### 5.2 看板已覆盖项及有效性

| 面板 | 表达式依据 | 有效性 |
|---|---|---|
| QPS 按服务 | `http_server_requests_seconds_count{application=~"$application"}` | **无数据**：指标无 `application` 标签（未配 common tags，抓取标签是 `app`），模板变量 `label_values(..., application)` 本身为空 |
| P95/P99 延迟 | `histogram_quantile(...,http_server_requests_seconds_bucket)` | **无数据**：未开启直方图，实测默认只出 count/sum/max，无 `_bucket`（§1.3） |
| 5xx 错误率 | `status=~"5.."` | **基本失效**：兜底异常返回 HTTP 200（§2.3），真实系统错误不计入 5xx；且受 application 标签影响 |
| 热路径 URI P95 | 同上 histogram_quantile | 同上无 bucket |
| JVM 堆内存 | `jvm_memory_used/committed_bytes` | 指标本身存在，仍受 application 标签过滤影响 |
| GC 停顿频率 | `jvm_gc_pause_seconds_count` | 同上 |
| HikariCP 连接 | `hikaricp_connections_active/pending` | **永久无数据**：7 个服务数据源全部是 Druid（各 application.yml:15），无 HikariCP，也未引入 Druid-Micrometer 桥接，实际连接池零监控 |

### 5.3 关键告警缺口

`deploy/RUNBOOK.md:38` 列了"关键告警建议"（5xx、P99、实例数、HikariCP pending、RocketMQ 堆积、ShedLock），但**仅为文字建议，无任何落地规则**。按题项对照：

| 应覆盖告警 | 状态 |
|---|---|
| 错误率（5xx / 业务错误码） | 缺失（且 5xx 口径因 200 化失效；业务 code 无指标） |
| P99 延迟 | 缺失（直方图未开，无规则） |
| Outbox 积压 | 缺失（outbox 表无导出指标，`OutboxRelayJob` 无埋点，耗尽挂起仅 error 日志） |
| RocketMQ 死信（%DLQ%） | 缺失（compose 无 rocketmq-exporter，客户端无 DLQ 指标；实际已存在 DLQ 队列数据 `deploy/rocketmq/data/consumequeue/%DLQ%*`，无任何监控） |
| 消费者掉线/消费延迟 | 缺失（健康指示器只看启动注册，无运行期 lag/在线指标） |
| JVM（堆/GC/线程） | 有面板无告警，且标签不匹配 |
| DB 连接池 | 面板指错数据源（HikariCP vs Druid），无 active/wait 指标与告警 |
| 健康实例数 <2 / 探针失败 | RUNBOOK 建议，未落地（K8s 亦无 Prometheus） |
| MySQL/Redis/节点资源 | 无 exporter，完全缺失 |
| ShedLock 定时任务漏跑（提现审核/结算批/outbox 接力） | 缺失（任务执行无指标/无心跳指标） |
| 限流命中量、保证金不足、支付/对账差错 | 缺失（无业务指标） |

---

## 6. 审计日志（管理后台敏感操作留痕）

**无统一操作审计设施**：全库 grep `@OperationLog / @OperateLog / AuditLog / 操作日志` 零命中，无 AOP 操作日志切面，无独立操作审计表。逐敏感操作核对：

| 敏感操作 | 留痕现状 | 证据 |
|---|---|---|
| 售后审核（商家同意/拒绝、平台仲裁） | **部分留痕**：`t_aftersale_status_log` 有 operator_id/operator_role/remark | 表结构 `sql/aftersale/V2__aftersale.sql:273-289`；写入 `AftersaleServiceImpl.java:830-836`。**但平台仲裁写死 operatorId=0L**（`AftersaleServiceImpl.java:554,561,565,578`，arbitrate 方法 530-580 全程不取当前运营 ID），仲裁单只存 result/award/remark/time（第 542-547 行），**仲裁责任人无法定位到具体运营账号** |
| 商品审核/违规下架 | 仅在 SPU 主表覆盖写最近一次 auditor_id/audit_time/audit_remark，**无历史、无前后值** | `SpuServiceImpl.java:215-230`；`sql/product/V2__product.sql:62-65` |
| 保证金（缴费/扣赔/罚款/退还） | 有资金流水表 `t_sett_deposit_log`（类型/金额/变动后余额/业务单号/备注），**但无 operator_id 列**，分不清系统扣赔还是人工操作 | `sql/settlement/V2__settlement.sql:249-271`；写入口 `DepositService.writeLog`（`DepositService.java:163-167` 一带） |
| 退款审批/发起 | 售后侧有状态日志（见上）；支付退款单只有 operator_type（0/1 角色语义），**无操作人 ID** | `shop-pay-service/.../refund/entity/RefundOrder.java:32`、`RefundServiceImpl.java:266-278` |
| 提现审批 | **无人工审批环节**：`WithdrawAuditJob` 注释自承"模拟风控自动过审"，`t_sett_withdraw` 只有 audit_time，**无 auditor_id/approver 列** | `shop-settlement-service/.../job/WithdrawAuditJob.java:11-25`；`WithdrawService.java:155-156`；表 `sql/settlement/V2__settlement.sql:177-201`（第 192 行仅 audit_time） |
| 商户入驻/等级调整/清退 | **零留痕**：service 签名不含操作人，直接 update 主表，无流水/历史表 | `AdminController.java:39-63`；`MerchantService.java:64-72`（updateLevel 仅 id+level）；`DepositService.java:140-146`（resign 仅 merchantId） |
| 对账差错人工补偿/批量重试（动款） | 差错单有 handle_action/remark/time，**无 handler/操作人列**；接口也不接收操作人 | `ReconcileController.java:48-58`；`ReconDiff.java:32-36`；`ReconcileServiceImpl.java:183-192` |
| 平台/商户账号开通 | 仅账号表本身，无"谁开通了谁"的审计记录 | `AdminAccountController.java:31-41` |
| 手动触发结算批/对账 | 仅有业务批次记录，无操作人 | `AdminController.java:76-82`、`ReconcileController.java:32-38` |

业务流水类表（`t_pay_notify_log`、`t_product_stock_log`、`t_aftersale_status_log`、MQ 消费幂等表）属于业务状态/幂等记录，不等于管理操作审计；资金与权限类操作整体不满足"谁、何时、对什么、从什么值改成什么值"的留痕要求。

---

## 7. 结论（三档）

### 阻断项（不解决不具备生产可观测性，建议上线前清零）

1. **全链路追踪完全缺失**：无 micrometer-tracing/zipkin/skywalking 依赖，日志无 traceId/MDC，Feign（`FeignRequestInterceptor.java:22-48`）与 RocketMQ 消息（`MqProducer.java:157-173`）均不透传上下文；`X-Trace-Id` 只在网关被剥离（`JwtAuthGlobalFilter.java:90`），RUNBOOK:14 的"全链路 traceId"声明不实。8 服务交易链路无法按请求串联排障。
2. **告警体系为零**：prometheus.yml 无 rule_files、无 Alertmanager、Grafana 无告警、K8s 形态连 Prometheus 都没有；死信队列已实际产生数据而无任何监控。
3. **唯一看板整体不可用**：① 指标缺 `application` 公共标签（看板/模板变量全部失配，抓取打的是 `app` 标签）；② 未开直方图，实测无 `_bucket`，P95/P99 面板无数据；③ 全局异常把 500/429/400 一律回 HTTP 200（`GlobalExceptionHandler.java:63-67` + 31-35），5xx 面板与错误率告警口径失效；④ HikariCP 面板与实际 Druid 数据源不匹配。
4. **网关 `/actuator/prometheus` 404**：`shop-gateway/pom.xml:30-33` 缺 micrometer-registry-prometheus，网关自身指标全缺。
5. **敏感管理操作无责任人留痕**：平台仲裁写死 operatorId=0（`AftersaleServiceImpl.java:554,561,565,578`）、保证金流水无操作人列（`V2__settlement.sql:249-271`）、商户等级/清退/对账差错补偿/账号开通均无审计记录；无统一操作日志设施。资金合规视角不满足审计要求。

### 重要项（上线后短期内必须补齐）

6. **零自定义业务指标**：限流命中（`RateLimitAspect.java:75-77`）、MQ 发送/消费失败与死信、Outbox 积压与挂起耗尽（`OutboxRelayJob.java:78-84`）、库存预警（`StockServiceImpl.java:306`）、保证金不足、定时任务心跳等关键业务面不可度量。
7. **MQ 健康指示器未纳入 readiness 组且只反映启动注册**（`MqConsumerHealthIndicator.java:11-13`），运行期消费者掉线/堆积不摘流量也无告警；db/redis 同样不在 readiness/liveness 组。
8. **中间件与基础设施无监控数据面**：无 rocketmq/mysql/redis/node exporter，无 Druid 连接池指标，Sentinel 不向 Prometheus 导出；RUNBOOK:38 所列告警一条未落地。
9. **日志工程化不足**：7 份 10 行 logback 无滚动/无 JSON/无环境分级，网关无 logback；本地 nohup 裸重定向无切割；无集中式日志依赖。
10. **MQ dry-run 全量 payload 落 INFO 日志**（`MqProducer.java:75,117`），事件体含收货人手机/地址 PII，缺统一日志脱敏兜底；渠道回调原文整存入表（`V2__pay.sql:160`），真实渠道接入前需复核。
11. **actuator 与业务端口共用且网关对匿名开放 `/actuator/health/**`**（`JwtAuthGlobalFilter.java:62`），登录用户可触达网关 info 端点；建议独立 management 端口并在 Ingress/过滤器层收敛 `/actuator/**`。

### 建议项（增强）

12. 校验类异常补日志（`GlobalExceptionHandler.java:40-56` 当前静默），并为参数错误/限流设置真实 400/429/429 状态码，恢复 HTTP 语义与指标可用性。
13. 商品审核增加审核历史表（保留多次审核与前后状态），退款单补操作人 ID，提现引入人工审批节点与 approver 字段。
14. 为 HTTP 指标配置 SLO 边界/直方图与 common tags（application），看板变量改用实际标签；补 JVM 线程/直接内存、Druid active/pending、 RocketMQ lag/DLQ 增长、Outbox pending 计数等面板与对应 Prometheus 规则（错误率、P99、实例数、消费延迟、死信增速、任务漏跑）。
15. K8s 形态补齐 Prometheus + ServiceMonitor/PodMonitor 抓取与 Alertmanager，生产 Grafana 口令外置（compose 第 160 行 admin/admin 仅限本地）。
16. 网关补 logback-spring.xml 与各服务统一 JSON  pattern（含 traceId/spanId/userId 占位），接入集中日志后再做敏感字段自动脱敏。
