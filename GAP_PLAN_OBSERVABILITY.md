# GAP_PLAN_OBSERVABILITY — 可观测性缺口规划

> 范围：8 服务（shop-gateway + user/product/marketing/order/pay/settlement/aftersale）+ shop-framework/shop-common，覆盖日志关联、追踪、指标、告警、看板、管理审计留痕。
> 只读声明：本文件仅为规划，不含任何代码/配置改动；未执行 mvn/docker/kubectl；证据均为静态 grep/sed 取得（文件:行号）。
> 依据：AUDIT_OBSERVABILITY.md（§1–§7）、AUDIT_RESILIENCE.md Z10（第 181 行起）。
> 编号约定：框架公共组件 **C50–C59**（不与既有 PLATFORM/RESILIENCE 编号冲突）。

## 0. 审计勘误

1. AUDIT_OBSERVABILITY §5.1 称 docker-compose 段为 `deploy/docker-compose.yml:144-170`、弱口令在第 160 行——本次未逐行复核，实施前以 `sed -n '140,175p'` 复核行号后再引用。
2. §1.1 列 8 份 application.yml 的 management 段行号（网关 67-76、业务 55-64/59-68 混用）：本次实读 `shop-gateway/src/main/resources/application.yml` management 段位于 63-73 行（`include: health,prometheus,info` 在 66-67），审计行号有 ±3 行偏差，结论不变。
3. §7 阻断项 3（看板四连修）与 §5.2 完全对应：① application/app 标签失配；② 无 `_bucket`；③ 异常 200 化使 5xx 口径失效（此项修复在平台/异常处理波，本规划 O3 仅改看板口径并依赖该修复）；④ HikariCP 面板应改 Druid（需先有 Druid 指标桥接，见 O6/C57，无桥接前该面板只能占位/标注无数据）。
4. AUDIT_RESILIENCE Z10 与本规划 O2/O6 为同一闭环：Z10 要求 Druid/Redisson/RocketMQ exporter 与告警规则，本规划将"应用侧指标埋点"归 O6（C57），"rules + exporter"归 O2；exporter（mysql/redis/rocketmq/node）属基础设施环境残留范畴，仓库内只交付 prometheus 抓取/规则声明，不交付外部 exporter 部署栈。
5. §5.2 表称看板"7 面板"：实测 shop-overview.json 面板为 QPS、P95/P99、5xx、热路径 P95、JVM 堆、GC、HikariCP 共 7 个，无误；模板变量 `label_values(http_server_requests_seconds_count, application)` 在 json:16。
6. §2.1 称 7 份 logback-spring.xml"逐字相同"本次仅实读 shop-user 一份（pattern 见 §O1 证据），实施时以一份为基准统一替换即可，不再逐份比对。

## 1. 卡总览与依赖图

| 卡 | 主题 | 阻断来源 | 依赖 |
|---|---|---|---|
| O1 | MDC 关联 ID + Micrometer Tracing 桥接（OTel 日志格式） | §7-1 | 无，先行 |
| O2 | Prometheus alerting rules + rule_files/Alertmanager 占位 | §7-2、Z10 | O6（业务指标名先冻结）、O7（直方图） |
| O3 | Grafana 看板四连修（标签/直方图/5xx 口径/Druid 面板） | §7-3 | O4（网关 target）、O6/O7、异常状态码修复（跨规划） |
| O4 | 网关 micrometer-registry-prometheus 缺失致 404 | §7-4 | 无（独立小改），建议与 O1 同批 |
| O5 | @AuditLog 注解 + AOP 管理操作审计留痕（先日志后落库） | §7-5 | O1（复用 MDC userId/requestId/traceId） |
| O6 | 业务自定义 Micrometer 指标最小集 | §6(重要项)、Z10 | O4/O7 之后可观测；指标名先冻结供 O2 引用 |
| O7 | HTTP 直方图 percentiles-histogram 开启（p95/p99 口径） | §1.3、§7-3 | 无（纯配置），随 W1 |

依赖图：
```
O1(MDC/traceId, W1) ──► O5(@AuditLog 复用 MDC)
O7(直方图, W1配置) ─┐
O4(网关 registry, W1)┼─► O3(看板修复, W6) ─┐
O6(业务指标埋点, W1框架+各服务)─────────────┼─► O2(rules/告警, W6)
异常状态码 200→4xx/5xx（跨规划，平台波）───┘
```
关键原则：**O1 先行**——MDC 中的 traceId/requestId/userId 是 O5 审计日志与跨服务排障的公共基础；O6 的指标命名表必须先冻结，O2 的规则表达式才能定稿；O3 必须在 O4/O7 与异常状态码修复后才有真实数据。

## 2. 契约/配置清单

### 2.1 框架侧公共组件（shop-framework，除非注明在 gateway）

| 编号 | 组件 | 位置（新建/修改） | 说明 |
|---|---|---|---|
| C50 | `TraceMdcFilter`（Servlet，OncePerRequestFilter，HIGHEST 优先级） | shop-framework `.../web/trace/TraceMdcFilter.java`（新） | 入口读 `X-Request-Id`/`X-Trace-Id`，缺省生成（UUID 去横线，traceId 32 位），写 MDC：traceId、requestId、userId（UserContext 已解析后回填）、clientIp；finally clear。注册于 WebMvcConfig |
| C51 | `GatewayTraceGlobalFilter`（WebFlux，Ordered.HIGHEST） | shop-gateway `.../filter/GatewayTraceGlobalFilter.java`（新） | 网关生成/采纳 X-Request-Id 并经 mutate 向下游透传；响应头回写 X-Request-Id |
| C52 | Feign trace 头透传 | shop-framework `.../feign/FeignRequestInterceptor.java`（改，现 22-48 行仅透 token/身份） | 追加透传 X-Request-Id、X-Trace-Id、b3（若开启 bridge 时由 Micrometer 自动传播则仅补 requestId） |
| C53 | Micrometer Tracing 桥接依赖管理 | shop-framework/pom.xml（改，现 33-38 行 actuator+registry-prometheus）+ shop-gateway/pom.xml（改） | 引入 `micrometer-tracing-bridge-otel`；**不引入** otel-exporter/Zipkin；OTel W3C traceparent 仅用于日志与头透传。tracer 采样率配置化（默认 1.0） |
| C54 | 统一 logback pattern（含 MDC） | 7 份 `src/main/resources/logback-spring.xml`（改，现 10 行/份，pattern 第 4 行）+ shop-gateway 新增同名文件（现无，证据 §2.1） | pattern 增 `[%X{traceId:-},%X{spanId:-}] [req=%X{requestId:-}] [uid=%X{userId:-}]`；Spring Boot 3.2 + tracing bridge 下 `%X{traceId}` 自动可用 |
| C55 | management 公共配置（common tags + 直方图） | shop-framework 提供 `observability-defaults.yml`（或 8 份 application.yml 一致段） | `management.metrics.tags.application=${spring.application.name}`；`distribution.percentiles-histogram.http.server.requests=true`；网关 application.yml:63-73 同步 |
| C56 | `@AuditLog` + `AuditLogAspect` | shop-framework `.../audit/`（新）：注解（action/targetType/SpEL targetId）、切面、结构化输出 | 字段：traceId/requestId/userId/userName/userType/merchantId、action、target、结果、IP、耗时、失败原因；先 JSON 行日志（logger=AUDIT），预留落库开关 |
| C57 | 业务指标埋点套件（MeterRegistry 注入） | shop-framework `.../metrics/BizMetrics.java`（新，统一指标名常量+注册助手）+ 各埋点类 | 指标名见 O6 表；framework 侧覆盖限流/MQ/Outbox/ShedLock 心跳，业务服务侧覆盖下单/支付/退款/库存预警/保证金 |
| C58 | Druid–Micrometer 指标导出 | shop-framework 配置类（新，Druid 1.2.22 已在基线程） | 以 Druid `DruidDataSourceStatManager` 定期采样导出 `druid_active_count/druid_wait_thread_count/druid_pooling_count`（Gauge）；不引第三方包装，最小实现 |
| C59 | （可选，二阶段）审计落库 | shop-framework `.../audit/AuditLogSink.java` + 各服务 `t_sys_audit_log` DDL | 开关默认 off；on 时异步写库，表结构含操作人/动作/目标/前后值摘要/IP/结果/traceId |

### 2.2 application.yml / 配置变更清单（8 服务，内容一致）

- `shop-gateway/src/main/resources/application.yml`（现 management 段 63-73）：加 `metrics.tags.application`、`percentiles-histogram`、（可选）独立 `management.server.port: 8090` 并收敛 `/actuator/**`（对应审计重要项 11，本规划仅建议，不强制）。
- 7 业务服务 `src/main/resources/application.yml`（management 段约 55-64 行）：同上 tags + histogram；management endpoint exposure 保持 `health,prometheus,info`。
- 8 份 `logback-spring.xml`：网关为新增，其余 7 份替换 pattern（C54）。

### 2.3 deploy 文件清单（新增/修改）

| 文件 | 动作 | 内容 |
|---|---|---|
| `deploy/prometheus/prometheus.yml` | 改 | 增加 `rule_files: - /etc/prometheus/rules/*.yml`；增加 `alerting.alertmanagers`（环境占位，见 O2）；8 个 job 的 target label `app` 保留，同时由应用侧 common tag `application` 兜底（二选一：也可直接把 labels 改为 application，本规划选应用侧统一，避免 K8s ServiceMonitor 形态不一致） |
| `deploy/prometheus/rules/shop-alerts.yml` | 新增 | O2 全部 alerting rules（分级） |
| `deploy/prometheus/alertmanager/alertmanager.yml` | 新增（可选，占位） | 缺省 `receiver: webhook-log`；钉钉/飞书 webhook 用 `${ALERT_WEBHOOK_URL}` 环境占位，未配置时仅 stdout/log（环境残留声明） |
| `deploy/docker-compose.yml` | 改（约 144-170 行 prometheus/grafana 段） | prometheus 挂载 rules 目录；可选挂 alertmanager；grafana admin 口令改环境变量（审计建议项 15，非阻断） |
| `deploy/grafana/dashboards/shop-overview.json` | 改 | O3 七面板修复（模板变量保留 application，由 C55 生效；面板 expr 见 O3） |
| `deploy/kubernetes/` | 新增（环境占位清单，不交付栈） | README 注释声明：生产形态 Prometheus/Alertmanager/ServiceMonitor 为环境提供方职责，仓库交付 rules 文件与 ServiceMonitor 示例（可选 `deploy/kubernetes/monitoring/` 占位目录），属环境残留 |

## 3. 任务卡

### O1 链路追踪：MDC 关联 ID + Micrometer Tracing（OTel 桥接，仅日志关联）

**现状证据**
- 7 份 logback pattern 无 traceId/MDC：`shop-user-service/src/main/resources/logback-spring.xml:4`（其余 6 份逐字相同）；网关无 logback 文件（§2.1）。
- 全库 `MDC.` 零命中（本次 grep shop-framework/shop-gateway）。
- 无 tracing 依赖：全部 pom grep `tracing|brave|otel|sleuth|zipkin` 零命中。
- Feign 不透传 trace 头：`shop-framework/src/main/java/com/shop/framework/feign/FeignRequestInterceptor.java:22-48`。
- `X-Trace-Id` 仅在网关被剥离：`shop-gateway/src/main/java/com/shop/gateway/filter/JwtAuthGlobalFilter.java:90`（常量 `shop-common/.../constant/SecurityHeaders.java:16`）。
- 入口无 MDC filter：`shop-framework/.../web/AuthInterceptor.java:41-58` 只解析身份。

**精确文件清单**
- 新建：shop-framework `.../web/trace/TraceMdcFilter.java`（C50）；shop-gateway `.../filter/GatewayTraceGlobalFilter.java`（C51）。
- 修改：FeignRequestInterceptor.java（C52）；shop-framework/pom.xml:33-38、shop-gateway/pom.xml:28-33（C53）；8 份 logback（C54）；8 份 application.yml（C55）。
- MQ（建议同卡，最小化）：`MqProducer.buildRaw()`（现 `shop-framework/.../mq/MqProducer.java:157-173`）把 traceId/requestId 写入 message property；消费端 `MqConsumerRegistrar` 收到 MessageView 时恢复 MDC 到消费日志结束 clear。不实现跨 MQ span。

**核心步骤**
1. C50：filter 顺序先于 AuthInterceptor（servlet filter 天然先于 interceptor），首次进入只放 traceId/requestId/clientIp；userId 在 AuthInterceptor 之后通过 request 属性或由 filter 在 finally 前从 UserContext 补 put（filter 包链，chain.doFilter 返回前 UserContext 已填充，doFilter 后补读仅用于异常边界，主方案：AuthInterceptor 内 preHandle 成功后 `MDC.put("userId", ...)`，afterCompletion 不动由 filter clear）。
2. C51：网关 filter 用 `UUID` 生成 requestId；若入站带 `X-Request-Id` 且合法（`[A-Za-z0-9-]{8,64}`）则采纳，防伪造注入；mutate().header 透传；响应头回写。
3. C53：仅 `micrometer-tracing-bridge-otel`，不引 exporter；logback pattern 用 `%X{traceId:-}`/`%X{spanId:-}`（bridge 自动管理），无桥接时 `:-` 退化为 `-`，保证网关/测试上下文不报错。
4. 采样与传播：默认 W3C traceparent + B3 单头双格式（OTel bridge 默认）；采样率 `management.tracing.sampling.probability=${TRACING_SAMPLE_RATE:1.0}`。

**单测/本地验证**
- TraceMdcFilter 单测：无 header 生成 ID 且日志行含 req=；带 header 采纳；请求结束 MDC 已清空（线程复用不串）。
- GatewayTraceGlobalFilter 单测（WebTestClient）：响应头回显 X-Request-Id；下游收到透传头。
- Feign 拦截器单测：template header 含 X-Request-Id。

**最终验收点**
- 网关 `curl -i http://localhost:8080/actuator/health` 与任意业务 API：响应头含 X-Request-Id；同一请求在 gateway 与下游服务日志中 traceId/requestId 一致（tail 两份日志 grep 该 ID）。
- 一次下单链路（gateway→order→product/pay）日志用同一 requestId 可串起 ≥3 个服务。

**环境残留声明**
- kind/本地形态**仅日志关联**，不部署 Jaeger/Tempo/Zipkin；若目标环境已存在 OTel collector/Jaeger 栈，属环境残留，仓库不提供其部署物，后续加 `opentelemetry-exporter-otlp` 需另立卡。
- RUNBOOK.md:14 "全链路 traceId" 在本卡完成后才与实现相符；完成前视为文档不实。

**回归面**：日志格式变更影响所有日志 grep 脚本/告警（无）；网关新增全局 filter 注意与 JwtAuthGlobalFilter 顺序、白名单（JwtAuthGlobalFilter.java:61-63）不受影响；MDC 不清理会导致线程池 userId 串号（必须单测覆盖）。

---

### O2 告警闭环：rules 清单 + 表达式 + 分级 + 通道占位

**现状证据**
- `deploy/prometheus/prometheus.yml:1-45` 无 rule_files/alerting；`deploy/prometheus/` 仅一个文件。
- docker-compose（约 144-170 行）无 alertmanager/exporter；Grafana 面板无 alert（§5.1）。
- DLQ 已有真实数据无监控：`deploy/rocketmq/data/consumequeue/%DLQ%*`（§5.3）。
- Z10（AUDIT_RESILIENCE.md:181）：无 Druid/Redisson/RocketMQ/JVM 线程池告警。

**精确文件清单**：新增 `deploy/prometheus/rules/shop-alerts.yml`、`deploy/prometheus/alertmanager/alertmanager.yml`（占位）；改 `deploy/prometheus/prometheus.yml`、`deploy/docker-compose.yml`。

**核心步骤 — 规则清单（分组：service / jvm / middleware / business）**

| 告警 | 表达式（核心，for/级别后列） | 级别 |
|---|---|---|
| 服务宕机 | `up{job=~"shop-.*"} == 0`，for 2m | P1 |
| 健康实例不足（K8s，环境有 kube-state-metrics 时） | `sum by(app) (up{job=~"shop-.*"}) < 2` for 3m | P1 |
| Pod 重启率（环境指标，占位） | `increase(kube_pod_container_status_restarts_total{namespace="shop"}[1h]) > 2` | P2 |
| 5xx 率 | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by(application) / sum(rate(http_server_requests_seconds_count[5m])) by(application) > 0.02` for 5m | P1（依赖异常状态码修复，否则口径失效，见 §0-3） |
| P99 延迟 | `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by(le,application)) > 800`（ms，热路径）for 10m | P2（依赖 O7） |
| JVM 堆内存 | `jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.85` for 10m | P2；>0.95 for 5m 升 P1 |
| GC 停顿/频率 | `rate(jvm_gc_pause_seconds_sum[5m]) > 0.1`（>10% 时间 GC）for 10m | P2 |
| DB 连接池等待（C58 落地后） | `shop_druid_wait_threads > 0` for 2m；`shop_druid_active / shop_druid_max > 0.9` for 5m | P1/P2 |
| Redis 不可用 | 以应用侧指标：`rate(shop_redis_failure_total[3m]) > 0`（O6 埋点）for 2m；无 redisson exporter 时以此为准 | P1 |
| MQ 积压 | `shop_mq_consume_lag`（O6/消费端埋点；未具备前以 broker exporter `rocketmq_group_diff` 为环境占位）> 阈值 for 10m | P2 |
| DLQ 增长 | `increase(shop_mq_dead_letter_total[10m]) > 0` for 0m（任何新增即报） | P1（Z4/Z10） |
| Outbox 卡死 | 业务 SQL 导出（需 outbox pending gauge，O6）：`shop_outbox_pending{lane="fast"} > 0` 且该批最老记录年龄 `shop_outbox_oldest_age_seconds > 120` for 3m；语义等价 `status=0 AND deliver_at<=NOW()` 持续滞留 | P1 |
| 限流命中率 | `increase(shop_ratelimit_hit_total[5m]) > 0` 按服务/资源；突增（>基线 5x）P2，持续命中 P3 | P2/P3 |
| MQ 生产失败 | `increase(shop_mq_publish_failed_total[5m]) > 0` | P1 |
| 定时任务漏跑（ShedLock 心跳） | `time() - shop_scheduler_last_run_seconds{job="..."} > 2 * 标称周期`（WithdrawAuditJob/结算批/outbox relay） | P2 |
| 支付/退款失败率（O6） | `rate(shop_pay_failed_total[5m]) / rate(shop_pay_total[5m]) > 0.05` | P2 |
| 保证金不足（O6） | `increase(shop_deposit_insufficient_total[10m]) > 0` | P3 |

分级定义：P1（7x24，电话/IM 群 @值班，5 分钟响应）、P2（工作时段 IM 群）、P3（群消息/日报）。
每条 rule 带 `labels: {severity, team}` 与 `annotations: summary/description/runbook_url`。

**通知通道（环境占位）**
- alertmanager.yml 缺省 `receiver: log-only`（webhook 指向本地日志打印器或不部署 alertmanager，仅在 Prometheus UI 可见）。
- 钉钉/飞书：`url: ${ALERT_WEBHOOK_URL}`，环境变量未设置则降级 log-only；密钥与真实 webhook 不入库。**真实通知通道为环境残留**，仓库只交付占位与文档。

**单测/本地验证**
- `promtool test rules`（新增 `shop-alerts.test.yml`）：构造 up==0、5xx 序列、outbox 滞留序列，断言告警触发与清除。
- `promtool check rules deploy/prometheus/rules/shop-alerts.yml`。

**最终验收点**
- Prometheus `/api/v1/rules` 返回全部规则且无 error；Status→Targets 8/8 up（依赖 O4）。
- `/api/v1/alerts` 在压测/chaos 注入下出现 Firing（DLQ、outbox 滞留至少做一次造数演练）。
- `bash deploy/kubernetes/ha-check.sh` 复跑，目标 down 时 P1 触发。

**环境残留**：Alertmanager 实例、mysql/redis/rocketmq/node exporter、kube-state-metrics、真实钉钉/飞书 webhook 均由部署环境提供；K8s 生产形态的 Prometheus/ServiceMonitor 不在仓库（审计重要项 8/15），rules 文件保证两种形态共用。

**回归面**：evaluation_interval 15s 不变；规则只增不改抓取；阈值需在 PERF 复测后按基线校准。

---

### O3 Grafana 看板四连修

**现状证据**（`deploy/grafana/dashboards/shop-overview.json`，grep 行号）
- 模板变量 `label_values(http_server_requests_seconds_count, application)`：json:16；指标无 application 标签（未配 common tags，抓取打 `app`：prometheus.yml:10,15,…）→ 变量为空，全看板失配。
- QPS expr：json:33；P95/P99：json:47,52（依赖 `_bucket`，未开直方图，§1.3）；5xx：json:66（异常 200 化，§2.3）；热路径 P95：json:80；JVM：json:94,99；GC：json:113；HikariCP：json:127,132（实际全 Druid，无 Hikari 指标）。

**精确文件清单**：仅改 `deploy/grafana/dashboards/shop-overview.json`；provisioning（`deploy/grafana/provisioning/*`）不动。

**核心步骤**
1. 标签：由 C55 在应用侧导出 `application` 标签（推荐，K8s/compose 一致），json 中 `$application` 变量与全部 expr 不改标签名即恢复数据；备选把 prometheus.yml 静态 labels 改 application 并同步 json——二选一，本规划选前者。
2. 直方图：json:47/52/80 expr 不动，待 O7 开启后 `_bucket` 出现即恢复；单位统一 ms（`_seconds` ×1000，面板 unit=short/ms 检查）。
3. 5xx 面板：表达式保留 status=~"5.."；在异常状态码修复（GlobalExceptionHandler.java:31-35,63-67 返回真实 4xx/5xx，跨规划）落地前，面板 description 标注"口径受限：兜底异常当前 200"；同时新增"业务异常率（code）"面板依赖 O6 `shop_biz_exception_total`。
4. HikariCP 面板（json:121-132）：替换为"Druid 连接池 active/wait/max"，expr 用 C58 的 `shop_druid_*`；C58 未交付前先改为占位面板并在 title 标注，避免永久误导。
5. 新增最小面板（随 O6 数据）：MQ publish/consume、DLQ 增量、Outbox pending/oldest age、限流命中、下单/支付 QPS 与失败率；JVM 面板补 threads/direct memory 可选。

**验证**：Grafana 导入后 Explore 逐面板 expr 无 "No data"（无流量的面板以 k6 造数：`deploy/loadtest/k6-order.js`）；变量下拉列出 8 个服务。

**最终验收点**：看板 7+ 面板在 20/40 TPS 压测（证据 `deploy/loadtest/evidence/k6-steady-40tps-clean.log`）下均有曲线；P99 与 PERF_REPORT 复测口径一致（O7）。

**回归面**：dashboard 仅展示层；provisioning updateIntervalSeconds=30 自动热加载。

---

### O4 网关 /actuator/prometheus 404

**现状证据**
- `shop-gateway/pom.xml:28-33` 仅 `spring-boot-starter-actuator`，无 micrometer-registry-prometheus；网关不依赖 shop-framework。
- `shop-gateway/src/main/resources/application.yml:66-67` 已 include prometheus 但端点无实现 → 404。
- 抓取任务 `deploy/prometheus/prometheus.yml:6-10` 必 DOWN。

**精确文件清单**：shop-gateway/pom.xml（加依赖，版本由 parent/Spring Boot BOM 管理，与 shop-framework/pom.xml:36-37 同坐标）；可选 application.yml 加独立 management 端口（审计重要项 11）。

**核心步骤**
1. pom 增加 `io.micrometer:micrometer-registry-prometheus`（不指定版本）。
2. 确认 Gateway（WebFlux/netty）下自动配置生效，指标名为 `http_server_requests_seconds_*`（WebFlux 在 Micrometer 1.12 下同名）。
3. 顺带收敛暴露面（建议非强制）：`management.server.port: 8090`，prometheus 抓 8090；或在 JwtAuthGlobalFilter/Ingress 拒绝普通 JWT 访问 `/actuator/**`（§4.3）。若采用独立端口，prometheus.yml:8 target 改 `host.docker.internal:8090`。

**单测/本地验证**：`mvn -pl shop-gateway test`；本地起网关 `curl -s -o /dev/null -w '%{http_code}' localhost:8080/actuator/prometheus` 期望 200 且输出含 `jvm_memory_used_bytes`、`http_server_requests_seconds_count`。

**最终验收点**：Prometheus Status→Targets shop-gateway 为 UP（当前必 DOWN）；`up{job="shop-gateway"}==1`；ha-check 抓指标步骤通过；Grafana 变量出现 shop-gateway。

**回归面**：依赖体积小；若开独立管理端口需同步 NetworkPolicy（`deploy/kubernetes/30-networkpolicy.yaml:36-48`）与探针端口配置（`00-namespace-config.yaml:98-111`），故默认先不拆端口，仅修依赖。

---

### O5 管理后台审计留痕：@AuditLog + AOP（先日志，二阶段落库）

**现状证据**：无任何审计设施（§6 全库 grep 零命中）。具体缺口：
- 平台仲裁写死 operatorId=0：`shop-aftersale-service/.../AftersaleServiceImpl.java:554,561,565,578`（arbitrate 530-580）。
- 保证金流水无 operator 列：`sql/settlement/V2__settlement.sql:249-271`；`DepositService.java:163-167`。
- 商户等级/清退零留痕：`shop-settlement-service/.../merchant/controller/AdminController.java:39-63`、`MerchantService.java:64-72`、`DepositService.java:140-146`。
- 对账差错补偿无 handler：`ReconcileController.java:48-58`、`ReconDiff.java:32-36`、`ReconcileServiceImpl.java:183-192`。
- 账号开通：`shop-user-service/.../account/controller/AdminAccountController.java:31-41`。
- 商品审核仅覆盖写：`SpuServiceImpl.java:215-230`（无历史/前后值）。
- 退款无操作人 ID：`RefundOrder.java:32`、`shop-pay-service/.../RefundServiceImpl.java:266-278`。
- 提现无人工审批：`WithdrawAuditJob.java:11-25`、`WithdrawService.java:155-156`、表 `sql/settlement/V2__settlement.sql:177-201`。

**精确文件清单**
- 新建 C56：shop-framework `.../audit/AuditLog.java`（注解：action、targetType、targetIdSpEL、resultDefault）、`AuditLogAspect.java`（@Around，@annotation 切点）、`AuditEvent.java`、JSON 输出（logger 名固定 `AUDIT`，独立 logger 可定向采集）。
- 各服务在管理端点方法加注解（端点清单见下）；AftersaleServiceImpl 仲裁方法必须改为从 UserContext 取真实运营（配合 O1 MDC），消灭 operatorId=0L 硬编码。
- 二阶段 C59：`t_sys_audit_log`（id/user_id/user_name/user_type/merchant_id/action/target_type/target_id/before_after/result/ip/ua/trace_id/request_id/cost_ms/created_at）+ 异步 sink，配置开关。

**需要加 @AuditLog 的管理端点清单（逐服务）**
- marketing：`ActivityAdminController`（/admin/activities 增删改、上下线）、`CouponAdminController`（/admin/coupons 发券/作废）、`PromoAdminController`（/admin/promos 秒杀/拼团配置与启停）。
- product：`AdminGoodsController`（/{spuId}/audit 审核、/{spuId}/violation 违规下架、admin 商品状态变更）。
- aftersale：售后商家审核端点、平台仲裁（arbitrate 全部分支，含补偿裁决/罚金）、退款审批入口。
- settlement：`MerchantDepositController`（保证金缴费/扣赔/罚款/退还）、`AdminController`（/admin/merchants、/{id}/level、/{id}/resign、手动结算批）、ReconcileController（/admin/reconcile/settle、差错 handle/批量重试）、提现审核（WithdrawAuditJob 当前自动过审；接人工审批端点时必须带 @AuditLog + approver）。
- pay：退款审批/发起（RefundServiceImpl 266-278）、渠道/对账人工操作（如有 admin controller）。
- user：`AdminAccountController`（平台/商户账号开通、禁用、角色调整）。
（实施时以 grep `mapping .*admin|@AuditLog 候选` 逐 controller 核对方法签名，禁止漏标罚金/动款/权限类。）

**核心步骤**
1. 切面取操作人：直接读 `UserContext`（现有，web 包）与 MDC（O1 的 traceId/requestId），IP 从 `X-Forwarded-Id`（网关后取首段）/RemoteAddr；失败也记录（@Around catch 后 rethrow，result=FAIL + 异常类）。
2. target 用 SpEL：`@AuditLog(action="MERCHANT_LEVEL_CHANGE", targetId="#id")`；前后值对读取型切面不强制，动款/等级类在注解声明 `captureArgs=true` 记录入参摘要，禁止记录密码/卡号字段（复用 §2.4 脱敏序列化）。
3. 日志方案不新增表、无事务影响；落库方案异步队列+失败降级回日志，绝不阻断业务。

**单测/本地验证**
- AuditLogAspect 单测：成功/异常两条路径、SpEL 解析、匿名为系统调用时 userType=SYSTEM；并发线程 MDC 不串。
- 本地以管理员 token 调一次仲裁/等级变更，grep `logger=AUDIT` 输出含操作人 ID、target、结果、traceId，且同 traceId 能关联业务日志。

**最终验收点**：上述清单每个管理端点至少一条审计记录（造数核对覆盖率脚本：grep @AuditLog 数 ≥ 端点数）；仲裁操作 `operatorId=0` 不再出现（改代码 + 抽查库 t_aftersale_status_log）。

**环境残留/后续**：落库表与合规留存周期属二阶段（C59）；日志采集到 SIEM 由环境提供。

**回归面**：AOP 绕管理端点性能可忽略；注意切面优先级不与 IdempotentAspect（HIGHEST_PRECEDENCE）/RateLimitAspect 冲突（audit 应最外层附近，统计真实耗时）。

---

### O6 业务自定义 Micrometer 指标最小集

**现状证据**：全库 `MeterRegistry|Counter|Timer|@Timed|Metrics.|Gauge` 命中 0（§1.2）。关键位置：
- 限流 `shop-framework/.../ratelimit/RateLimitAspect.java:75-77`
- MQ 生产 `shop-framework/.../mq/MqProducer.java:85,107,127,148`；消费/死信 `MqErrorPolicy.java:23-24`
- Outbox `shop-framework/.../outbox/OutboxRelayJob.java:78,84`
- 库存预警 `shop-product-service/.../stock/service/impl/StockServiceImpl.java:306`
- 保证金 `shop-settlement-service/.../deposit/service/DepositService.java:130-133`

**精确文件清单**：新建 `shop-framework/.../metrics/BizMetrics.java`（C57，集中常量）、`DruidMetricsBinder`（C58）；改动点：RateLimitAspect、MqProducer、MqConsumerRegistrar/MqErrorPolicy、OutboxRelayJob/OutboxMapper（pending 计数用定时 Gauge 扫 `status=0 AND deliver_at<=NOW()`）、framework `@SchedulerLock` 任务心跳（建议 AOP 包一层或在 ShedLock 切面记录）、订单/支付/退款 service、StockServiceImpl、DepositService。

**最小指标集（命名冻结，O2 规则直接引用）**

| 指标名 | 类型 | 标签 | 埋点位置 |
|---|---|---|---|
| `shop_order_created_total` | Counter | result(success/fail),channel | 下单入口 |
| `shop_order_create_seconds` | Timer | — | 下单主流程 |
| `shop_pay_total` / `shop_pay_failed_total` | Counter | channel,result | 支付成功/失败 |
| `shop_pay_seconds` | Timer | channel | 支付调用（含渠道等待） |
| `shop_refund_total` | Counter | result,operator_type | 退款发起/审批结果 |
| `shop_mq_publish_total` / `shop_mq_publish_failed_total` | Counter | topic,event | MqProducer 85/107/127/148 |
| `shop_mq_publish_seconds` | Timer | topic | send 耗时 |
| `shop_mq_consume_total` / `shop_mq_consume_failed_total` / `shop_mq_dead_letter_total` | Counter | topic,group,event,reason | 消费成功/可重试失败/终态 DLQ（MqErrorPolicy 23-37） |
| `shop_mq_consume_lag` | Gauge | topic,group | 消费 lag（无 broker exporter 时客户端尽力估算；环境有 rocketmq-exporter 则以其为准） |
| `shop_outbox_pending` | Gauge | lane(fast/slow) | 定时扫 status=0 / status=2 计数 |
| `shop_outbox_oldest_age_seconds` | Gauge | lane | min(NOW()-deliver_at) on stuck rows |
| `shop_outbox_relay_seconds` | Timer | result | relay 单次 |
| `shop_ratelimit_hit_total` | Counter | service,resource | RateLimitAspect:75-77 |
| `shop_scheduler_last_run_seconds` / `_outcome_total` | Gauge/Counter | job | 25 个 @Scheduled（含 WithdrawAuditJob/结算批）统一心跳 |
| `shop_stock_alert_total` | Counter | sku?（避免高基数：不带 skuId，带级别） | StockServiceImpl:306 |
| `shop_deposit_insufficient_total` | Counter | merchant 维度不带 id（高基数），带 event 类型 | DepositService:130-133 |
| `shop_redis_failure_total` | Counter | op | 限流/幂等/锁切面 Redis 异常处（配合 Z10/B4） |
| `shop_biz_exception_total` | Counter | code | GlobalExceptionHandler 业务异常分支 |
| `shop_druid_active/idle/wait/max` | Gauge | service | C58 |

高基数纪律：标签禁止 orderNo/userId/skuId/merchantId/URL 原值。

**验证**：`curl /actuator/prometheus | grep shop_` 出现上述序列；计数器随业务动作递增（单测 + 手工点单）。

**最终验收点**：O2 规则中引用的 business 组指标 100% 存在（对照清单核）；PERF 复测可从指标读下单 TPS/支付延迟。

**回归面**：Micrometer 注册表已随 actuator 存在；Gauge 扫库注意低频（30s）与只读从库/限流；埋点不得在热路径分配大对象。

---

### O7 HTTP 直方图 p99 口径（percentiles-histogram）

**现状证据**：8 份 application.yml 无 `percentiles-histogram/slo`（§1.3，resources grep 无命中）；Micrometer 1.12.5 默认仅导出 count/sum/max，无 `_bucket`（已实测）；看板 json:47/52/80 因此无数据。

**精确文件清单**：C55 统一配置（framework 默认配置或 8 份 application.yml management 段；网关 application.yml:63-73）。

**核心配置**
```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      maximum-expected-value:
        http.server.requests: 5s
      # 可选 SLO 边界（桶更可控，PERF 复测建议同时给出）：
      # slo:
      #   http.server.requests: 50ms,100ms,200ms,500ms,1s,2s,5s
```
注意：percentiles-histogram 出 Prometheus 原生 bucket（默认边界由 Micrometer 决定，p99 在 ≤5s 范围可靠）；p95/p99 统一用 `histogram_quantile` over `rate(..._bucket[5m])`，与 O3 json 现有写法一致；PERF 复测必须以开启后数据为准，复测窗口 ≥15 分钟（15s scrape）。

**验证**：`curl -s localhost:808x/actuator/prometheus | grep http_server_requests_seconds_bucket | head`；Prometheus 直接求 `histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by(le,application))` 有值。

**最终验收点**：看板 P95/P99/热路径三面板出数；PERF_REPORT 引用的 p99 与 PromQL 结果一致（误差仅来自时间窗）。

**回归面**：bucket 系列约增几十行序列/服务，基数影响可忽略；与 O4 同时验证网关 WebFlux 同样导出 bucket。

## 4. 执行顺序（与 PLATFORM W1/W6 对接）

**W1（框架波，随平台第 1 波配置/框架改动一起，低风险先装安全带）**
1. O1 全套（C50–C54）+ O4（一个依赖，阻断 target DOWN）+ O7（C55 纯配置）——三者同 PR 批次，构成可观测地基。
2. O6 framework 侧埋点（C57 的限流/MQ/outbox/scheduler/Druid C58）随 W1 进框架；业务服务侧埋点（下单/支付/退款/库存/保证金）随各服务 W1 末尾或 W2 初，命名表本文件冻结后并行开发。
3. O5 框架件 C56 随 W1 交付；注解铺设（各服务 admin 端点 + 仲裁 operatorId=0 修复）可随各服务业务波次在 W6 前完成。

**W6（观测闭环波，依赖 W1 数据面）**
4. O3 看板修复（json）：在 O4/O7 与异常 200→4xx/5xx 修复（平台波）合入后进行；先验证标签与 bucket 出数再改面板。
5. O2 rules + rule_files + alertmanager 占位 + promtool tests：引用 O6 冻结指标名；与 Z10/Z4 同波关闭。
6. ha-check/chaos 复跑：`deploy/kubernetes/ha-check.sh`、`deploy/loadtest/chaos.sh` 下验证 P1 告警（实例 DOWN、DLQ、outbox 滞留）。
7. PERF 复测：以 O7 直方图为 p99 口径，回填 Grafana 面板与阈值基线（O2 阈值校准）。
8. C59 审计落库：W6 之后二阶段，开关默认 off。

**验收闸门**：W1 末——8/8 target UP、日志含 traceId、bucket 存在；W6 末——promtool 全绿、规则在 Prometheus 可求值、看板无空面板、审计注解覆盖率 100%（管理端点清单核对）、P1 webhook 在配置环境后实测一条。
