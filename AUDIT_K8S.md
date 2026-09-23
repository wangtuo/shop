# Kubernetes 高可用部署审计报告（AUDIT_K8S）

- 审计对象：`deploy/kubernetes/` 全部清单与脚本，以及 `deploy/` 下与部署相关的 compose / Dockerfile / Spring 配置
- 审计时间：2026-09-17
- 审计方式：只读静态审查，未执行任何脚本、未修改任何文件
- 环境定位：清单分两层——生产基线清单（`00/10/15/20/30`）与本地 kind 单节点验收覆盖层（`kind/`、`tls/`、`ha-check.sh`）；本地中间件由 `deploy/docker-compose.yml` 提供

## 总体结论

| 审计项 | 结论 |
|---|---|
| 1. 8 服务 Deployment 与副本数 | 达标（生产基线 8/8 均 2 副本；kind 单节点有运行时豁免补丁，基线文件未被放宽） |
| 2. 探针（三组/参数/probes 开关/MQ 指标排除） | 达标 |
| 3. 资源、JVM 堆匹配、优雅终止 | 达标（有 2 处建议级调优点） |
| 4. Service/Ingress/ConfigMap/Secret | 基本达标（密钥注入与 prod fail-fast 完整；JDBC useSSL=false 为高优建议） |
| 5. PDB/HPA/NetworkPolicy/反亲和/拓扑分布 | 达标（kind 豁免有声明；Egress 策略、网关 HPA 缺失属建议） |
| 6. 镜像 tag 策略与脚本正确性 | 脚本逻辑正确；仓库地址为占位符、无 digest 锁定/拉取凭据，上线前必须解决 |
| 7. MySQL/Redis/RocketMQ 高可用现状 | 本地全为单副本；k8s 内不部署中间件、仅留外部地址占位——生产数据平面 HA 需外部落实，属上线条件性阻断项 |

---

## 1. 八个服务 Deployment 与副本数

8 个工作负载全部存在，Deployment 共 8 个（网关在 `00-namespace-config.yaml`，7 个业务服务在 `10-services.yaml`），副本数全部为 2：

| 服务 | Deployment 证据 | replicas | 端口 |
|---|---|---|---|
| shop-gateway | `deploy/kubernetes/00-namespace-config.yaml:40-46` | 2（`:46`） | 8080 |
| shop-user-service | `deploy/kubernetes/10-services.yaml:12-18` | 2（`:18`） | 8081 |
| shop-product-service | `10-services.yaml:136-142` | 2（`:142`） | 8082 |
| shop-marketing-service | `10-services.yaml:258-264` | 2（`:264`） | 8083 |
| shop-order-service | `10-services.yaml:380-386` | 2（`:386`） | 8084 |
| shop-pay-service | `10-services.yaml:502-508` | 2（`:508`） | 8085 |
| shop-settlement-service | `10-services.yaml:633-639` | 2（`:639`） | 8086 |
| shop-aftersale-service | `10-services.yaml:755-761` | 2（`:761`） | 8087 |

- 滚动策略统一 `maxUnavailable: 0, maxSurge: 1` + `minReadySeconds: 15`（网关 `00:47-50`；业务如 `10-services.yaml:19-22`），保证发布期间不少副本。
- **kind 单节点豁免（已接受）**：基线清单是 `requiredDuringSchedulingIgnoredDuringExecution` 跨主机硬反亲和（如 `10-services.yaml:32-37`），单节点 kind 上第 2 副本必然 Pending。`ha-check.sh:65-75` 在 apply 后检测节点数 <2 时，用 JSON Patch **运行时**放宽为 preferred 反亲和 + ScheduleAnyway，不改基线文件；并用 grep 反向校验基线文件仍含 required（`ha-check.sh:112-118`），防止"为 kind 顺手改软生产清单"。`kind/kind-cluster.yaml:9-10` 明确声明单节点 + 2 副本同节点的验收定位。多节点 kind 可 `SKIP_SCHEDULING_PATCH=1` 验证真反亲和（`ha-check.sh:65`）。
- **判定：达标。** 副本数、发布策略满足单机以外 HA；kind 豁免机制设计严谨（运行时打补丁 + 基线守护校验）。

## 2. 探针

### 2.1 三组探针齐全性

8 个工作负载均配置 startup / readiness / liveness 三组 HTTP 探针，路径分别为 `/actuator/health/liveness` 与 `/actuator/health/readiness`：

- 网关：`00-namespace-config.yaml:92-106`
- user：`10-services.yaml:81-95`；product：`:203-217`；marketing：`:325-339`；order：`:447-461`；pay：`:578-592`；settlement：`:700-714`；aftersale：`:822-836`

### 2.2 参数合理性

全 8 负载参数一致：

| 探针 | period | timeout | failureThreshold | 最长容错窗口 |
|---|---|---|---|---|
| startup | 5s | 3s | 24 | 120s 慢启动窗口 |
| readiness | 10s | 3s | 3 | 约 30s 摘流 |
| liveness | 20s | 5s | 3 | 约 60s 自愈判定 |

- 所有探针**均未显式设置 initialDelaySeconds**（grep 全目录 0 处）。这是合理写法：liveness/readiness 在 startupProbe 通过前不参与判定，startup period=5s 从第 0 秒即起探，等价于把初始延迟交给 startup 统一承担，避免固定 initialDelay 在快启动时浪费、慢启动时不足。
- startup 120s 窗口与注释"覆盖 MQ 消费者收敛"匹配（`10-services.yaml:79-80`）；liveness 60s 窗口对 Full GC/瞬时抖动有容忍度。**判定：合理。**

### 2.3 probes 开关

8 个服务的 `application.yml` 全部启用 `management.endpoint.health.probes.enabled: true`：

- 网关 `shop-gateway/src/main/resources/application.yml:75-76`
- 其余 7 服务：`application.yml:67-68`（user/product/marketing/order/pay/settlement/aftersale 行号相同）
- 探针端点随 `management.endpoints.web.exposure.include: health,prometheus,info` 暴露（同文件）。

### 2.4 MQ 自定义健康指标是否排除在探针组外

- 自定义指标仅有一个：`shop-framework/src/main/java/com/shop/framework/mq/MqConsumerHealthIndicator.java:15`（Bean 名 `mqConsumers`，消费者注册未完成时 DOWN，`:25-43`）。
- 全仓库未配置任何 `management.endpoint.health.group.*`（对 8 份 yml grep `group:/liveness/readiness` 无命中）。Spring Boot 3.2.5（`pom.xml:36`）在 probes.enabled 下自动装配的 liveness/readiness 组**只含 livenessState / readinessState**，自定义 HealthIndicator 默认只进聚合端点 `/actuator/health`，不进任何探针组。
- 因此 MQ 慢收敛/抖动：不会被 liveness 杀容器，也不会因 readiness 反复摘流量——符合审计要求。指标类注释亦明确"K8s liveness 不会因 MQ 抖动反复杀容器"（`MqConsumerHealthIndicator.java:11-13`）。
- **副作用（记录，不判问题）**：mqConsumers 同样不在 readiness 组内，消费者注册完成前 Pod 已 Ready 可接 HTTP 流量。当前架构 HTTP 服务不等待 MQ（后台线程注册），实际影响小；若日后要求"消费者未就绪不接量"，应显式把 mqConsumers 纳入 readiness 组（liveness 组仍必须排除）。

- **判定：达标。**

## 3. 资源、JVM 堆与优雅终止

### 3.1 requests/limits（8/8 齐全）

| 工作负载 | requests | limits | 证据 |
|---|---|---|---|
| gateway | 500m / 512Mi | 2 / 1Gi | `00-namespace-config.yaml:107-109` |
| 7 个业务服务 | 500m / 768Mi | 2 / 2Gi | 如 `10-services.yaml:96-99`（product `:218-221`、order `:462-465`、pay `:593-596`、其余同构） |

### 3.2 JVM 堆与容器内存匹配

- 镜像未硬编码 -Xmx，统一使用 cgroup 感知的百分比（`deploy/docker/Dockerfile:10`）：`-XX:MaxRAMPercentage=60.0 -XX:InitialRAMPercentage=40.0`，JRE 17（`Dockerfile:6`）。
- 业务服务：最大堆 ≈ 2Gi×60% = **1.2Gi**，加 Metaspace/CodeCache/直接内存/线程栈/GC 开销，2Gi limit 余量约 800Mi，匹配合理（清单注释 `10-services.yaml:98`）。
- 网关：最大堆 ≈ 1Gi×60% = **614Mi**，limit 1Gi，对 WebFlux 网关偏紧但可用。
- **建议级偏差（非阻断）**：业务服务初始堆 2Gi×40% ≈ 820Mi，已高于 memory request 768Mi；网关初始堆 ≈ 410Mi，加非堆后 RSS 约 550Mi，亦高于 512Mi request。Pod 运行依赖节点内存超卖（不影响 limit/OOM 判定），调度与节点压力评估时建议把业务 request 提到 ~1Gi、网关 ~600Mi，或下调 InitialRAMPercentage。
- `HeapDumpOnOutOfMemoryError` 输出到容器内 `/tmp/heapdump`（`Dockerfile:10,14`），Pod 销毁即丢失，建议挂 emptyDir/宿主收集或直传对象存储。

### 3.3 优雅终止与连接排空（链路完整，判定达标）

四层配置齐备且预算自洽（preStop 10s + Spring graceful 30s < 60s 封顶）：

1. `terminationGracePeriodSeconds: 60`：网关 `00:57`；业务 `10-services.yaml:30/154/276/398/520/651/773`。
2. `preStop: sleep 10`：先等 kube-proxy/Ingress/Nacos 摘传播再收 SIGTERM，网关 `00:80-84`；业务 `10-services.yaml:55-60` 等 7 处。
3. `server.shutdown: graceful`（8 份 `application.yml:3`）+ `spring.lifecycle.timeout-per-shutdown-phase: 30s`（8 份 `application.yml:7`）。
4. 信号可达性正确：`Dockerfile:23-24` `STOPSIGNAL SIGTERM` + `exec java` 让 JVM 成为 1 号进程，注释明确否定了 sh 包裹导致信号不转发的写法（`Dockerfile:19-22`）。
5. 注册中心侧主动注销依赖 Nacos client 的 graceful shutdown hook（与 preStop 10s 互补）。

## 4. Service / Ingress / ConfigMap / Secret

### 4.1 Service

- 仅网关有 ClusterIP Service：`00-namespace-config.yaml:111-120`（80→8080）。
- 7 个业务服务**无 Service 对象**（10-services.yaml 中 kind 仅 Deployment/PDB/HPA）。这是 Nacos 注册发现直连 Pod IP 的有意设计（网关路由 `lb://shop-xxx-service`，见 `shop-gateway/src/main/resources/application.yml:18-47`），代价是东西向流量无 cluster DNS/Service 兜底，完全依赖 Nacos 注销的及时性（已由 preStop 10s 覆盖传播延迟）。**建议**为每个业务服务补无头/普通 Service 以便排障与未来脱离 Nacos，当前记录为设计取舍而非缺陷。

### 4.2 Ingress（TLS/host/路径）

`deploy/kubernetes/20-tls-ingress.yaml:14-46`：

- host `shop.example.com`（`:37`），TLS `secretName: shop-gateway-tls`（`:32-35`），路径 `/` Prefix → `shop-gateway:80`（`:40-46`），ingressClassName nginx（`:31`）。
- 证书双轨：生产 cert-manager 注解 `cert-manager.io/cluster-issuer: letsencrypt-prod`（`:21`，Issuer 需自行创建，文件头 `:4-9` 已说明）；kind/内网走 `tls/gen-self-signed.sh` 自签。
- 80 强跳 443（`:23-24`，ha-check 实测 308/301，`ha-check.sh:201-205`）、HSTS 1 年含子域 preload（`:26-27`）、支付回调 body 8m（`:29`）。
- **建议**：未显式设置 TLS 最低版本（现代 ingress-nginx 默认 1.2/1.3，建议显式 ssl-protocols 固化）。

### 4.3 ConfigMap

`00-namespace-config.yaml:22-37`：Nacos/MySQL/Redis/RocketMQ/Sentinel 全部为外部地址占位（`mysql-proxy.shop-infra:3306` 等），`NACOS_GROUP: shop-prod` 与环境隔离（`:31`），`SHOP_PAY_MOCK_CHANNELS_ENABLED: "false"`（`:37`）。kind 覆盖层 `kind/00-kind-infra.yaml:18-31` 改为 host.docker.internal + `shop-ha-kind` 注册组，差异逐项注释。业务容器同时以显式 env 与 `envFrom configMapRef`（如 `10-services.yaml:77-78`）注入，存在键重复但显式 env 优先级更高，无实际问题。

### 4.4 Secret 来源（无硬编码生产密钥，判定达标）

- 清单中**所有**敏感值均走 secretKeyRef：数据源账号（如 `10-services.yaml:69-70`）、JWT/internal-token/data-enc-key（`:71-73`）、网关 `00:89-90`、支付六渠道密钥 `10-services.yaml:567-572`。
- 模板 `15-secret-template.yaml:30-42` 全部是 `CHANGE_ME_*` 占位，带 `do-not-apply` 注解（`:26-28`），文件头明令禁止随 kubectl apply 批量下发（`:3-10`）。
- 引导脚本 `create-secrets.sh`：真实值只从环境变量读取（`:20-38`，不进参数/history），Secret 已存在默认拒绝覆盖、需显式 `ALLOW_OVERWRITE=1`（`:42-48`），dry-run 生成再 apply（`:51-64`）。
- prod fail-fast 校验链确实存在于代码中：
  - 业务服务 `shop-framework/.../ShopSecretEnvironmentValidator.java:39-55`：prod 下空值/弱值/内置默认值/CHANGE_ME 占位直接抛异常阻止启动；
  - 网关 `shop-gateway/.../GatewaySecretValidator.java:32-40` 同构校验；
  - 支付渠道 `shop-pay-service/.../ChannelSecretProvider.java:116-133`：prod 禁止 mock 渠道，唯一豁免是双开关 `shop.pay.allow-mock-under-prod=true` + 六渠道密钥全部外置（kind 验收路径，`kind/00-kind-infra.yaml:29-31`）。
- kind Secret（`kind/05-kind-secret.yaml:14-30`）为开发值，注释明确禁止生产使用，且值刻意不等于内置默认以验证 fail-fast 本身有效。
- **高优建议**：生产 JDBC URL 显式写了 `useSSL=false`（`10-services.yaml:66` 及 7 个服务同构行 190/312/434/556/687/809），数据库链路为明文；接云托管 MySQL 时应启用 TLS（并补 Redis TLS/鉴权与 RocketMQ TLS 策略）。

## 5. PDB / HPA / NetworkPolicy / 反亲和 / 拓扑分布

### 5.1 PDB（8/8）

每个工作负载一个 `policy/v1` PDB，`minAvailable: 1`：网关 `00:122-130`；业务如 `10-services.yaml:101-109` 等 7 个。2 副本下含义明确：始终保留 1 个健康副本。ha-check 用真实 Eviction 子资源验证了 API Server 429 拒绝（`ha-check.sh:128-149`，第 1 个 ALLOW、第 2 个 BLOCKED）。

### 5.2 HPA（7/8，网关缺失）

7 个业务服务均有 `autoscaling/v2` HPA，minReplicas=2，CPU 65% + 内存 70% 双指标：

- maxReplicas：user 4（`:122`）、product 6（`:244`）、marketing 4（`:366`）、order 6（`:488`）、pay 6（`:619`）、settlement 3（`:741`）、aftersale 3（`:863`）。
- 内存阈值 70% 的取舍有注释说明（堆≈limit 60%，阈值过高会来不及扩容先被 OOMKill，`:128-129/250-251` 等）。
- kind 通过 `kind/install-metrics-server.sh`（含 `--kubelet-insecure-tls` 补丁，`:8-9`）满足 HPA 取数。
- **缺口**：**网关无 HPA**（00 文件只有 Deployment/Service/PDB）。网关是流量入口，建议补 HPA（CPU/连接数自定义指标）。

### 5.3 NetworkPolicy

- 生产策略 `30-networkpolicy.yaml:13-78`：业务服务仅放行网关、同命名空间、RFC1918 三段私网且限定 actuator 端口 8081-8087（`:37-48`）；网关仅放行 ingress-nginx 命名空间 + 同命名空间 + 私网到 8080（`:51-78`）。
- kind 覆盖 `kind/40-kind-networkpolicy.yaml` 用节点真实 /16 网段替换占位（ha-check `:81-85` 动态 sed）。
- **已接受豁免**：kindnetd 不支持 NetworkPolicy 时策略为空操作（`30:4-6`，ha-check apply 带 `|| true`，`:84-85`）。
- **建议**：① 全部策略只有 Ingress、无 Egress/默认拒绝，出站（到假 mysql/redis、外网）无约束；② 业务策略"同命名空间"规则未限定端口（`:34-35`），同 ns 任意 Pod 可达任意端口，建议收紧到业务端口；③ 生产应把 RFC1918 占位换成真实 VPC/节点网段（文件头 `:8-11` 已提示）。

### 5.4 反亲和与 topologySpreadConstraints（8/8）

- 硬反亲和（required，topologyKey hostname）：网关 `00:58-63`；业务如 `10-services.yaml:32-37` 等 7 处。
- TSC 双约束：hostname `DoNotSchedule` + zone `ScheduleAnyway`：网关 `00:64-74`；业务如 `10-services.yaml:39-49` 等。
- **建议**：跨可用区目前是软约束（ScheduleAnyway），多 AZ 生产若要求均衡分布可评估改 DoNotSchedule（需接受单 AZ 容量不足时的调度阻塞）；hostname 的 TSC 与硬反亲和功能重复但无害。
- kind 单节点豁免见第 1 节；TSC 在单节点被同一补丁一并放宽（`ha-check.sh:72`）。

## 6. 镜像 tag 策略与脚本正确性（只读审查）

### 6.1 tag 策略

- 8 个镜像全部固定 tag `2.0.0`（如 `00:77`、`10-services.yaml:52`），无 latest。未显式写 imagePullPolicy：固定 tag 下 K8s 默认 `IfNotPresent`，与 kind load 本地镜像的玩法自洽（`build-and-load-kind.sh:4` 注释正确）。
- **上线阻断点**：仓库地址 `registry.example.com/shop/...` 是占位符，全仓库无真实镜像仓库、无 imagePullSecrets、无 CI 推送/签名步骤，也未按 `@sha256` digest 锁定。真实集群无法从这套清单直接拉到镜像；建议生产改为真实仓库 + digest（或不可变 release tag）+ 显式 imagePullPolicy + 拉取凭据。
- 构建脚本注释为"arm64 原生"（`build-and-load-kind.sh:28`），未做 buildx 多架构；Apple Silicon 之外的 kind/节点需自行确认架构一致。

### 6.2 build-and-load-kind.sh

逻辑正确：固定 `TAG=${TAG:-2.0.0}`（`:13`）与清单一致；模块数组 8 项 jar→镜像名映射（`:14-23`）与各模块 pom 的 `finalName=${project.artifactId}` 逐一核对相符；先 `mvn clean package -DskipTests`（`:26`），缺 jar 即退出（`:31`），构建上下文固定仓库根（`:33`，与 Dockerfile 注释一致），再逐镜像 `kind load ... --name kind`（`:40`）。SDKMAN nounset 处理得当（`:9-11`）。仅末尾提示的 `kubectl apply -f deploy/kubernetes/kind/` 会直接下发 kind Secret（ha-check 实际有"缺失才创建"保护，手动照敲则会覆盖），属提示文案不严谨。

### 6.3 ha-check.sh

总体正确且防护意识强，关键路径：

- context 硬保护，非 kind-kind 直接 exit 9（`:26-27`）；先停宿主机直跑应用避免消费组分摊（`:37-39`）。
- Secret 仅缺失时创建（`:43-48`）；RocketMQ 端点按宿主 LAN IP patch（broker 通告地址三平面可达，`:51-58`）。
- 单节点调度补丁仅运行时生效、节点数动态判断、可 `SKIP_SCHEDULING_PATCH=1` 关闭（`:65-75`）。
- HA 校验覆盖副本数/ready 数/优雅期/preStop/startupProbe/PDB/HPA/Service（`:99-123`），并 grep 基线文件守护硬反亲和与跨 zone spread（`:112-118`）。
- PDB Eviction 测试为 Kubernetes 教科书式验证（`:128-149`）；脚本自己注明了第 2 次驱逐可能因 Pod 快速恢复而误报的竞态（`:148`），可重跑设计合理。
- 真实流量验收：HTTPS `--resolve` 0.5s 一发、强杀网关+业务 Pod、两次 rollout restart、基线前 10 样本必须全 200、总样本 ≥100、全程失败率 <2%（`:154-196`）；80→443 跳转实测（`:201-205`）。
- 小瑕疵（建议，不影响主流程）：① `ipconfig getifaddr en0/en1`（`:51`）仅适用 macOS，Linux 验收需手工 `SHOP_BROKER_IP`；② ingress 端口回退取第一个端口（`:156`），极端情况下可能取到 80 导致后续 HTTPS 失败；③ 节点网段按 `/16` 截取（`:82`）依赖 docker bridge 默认编址，非 /16 网络需人工核对。

## 7. MySQL / Redis / RocketMQ（及 Nacos）高可用/持久化/重启现状

### 7.1 k8s 侧

`deploy/kubernetes/` 下**没有任何**中间件 Workload（grep 无 StatefulSet/PV/PVC）。应用清单只通过 ConfigMap 引用外部地址（`00-namespace-config.yaml:28-35`），设计声明为"生产由云厂商托管或独立 StatefulSet 集群"（`00:7-8`，`10-services.yaml:7-8`）。kind 验收则复用宿主机 compose（`kind/00-kind-infra.yaml:1-3,19-28`）。

### 7.2 docker-compose.yml 现状（本地/验收形态，逐项如实记录）

| 组件 | 副本/形态 | 持久化 | 重启 | 鉴权/其他 | 证据 |
|---|---|---|---|---|---|
| MySQL 8.0 | **单副本** | 命名卷 `shop-mysql-data` + 7 个库初始化 SQL | unless-stopped（`:45`） | root/root 弱口令（`:26`）；healthcheck 10 次（`:40-44`） | `:17-45` |
| Redis 7.2 | **单副本**，无 Sentinel/Cluster | AOF 开启 + 命名卷（`:50,53-54`） | unless-stopped（`:60`） | **无密码**；maxmemory 512mb + noeviction（`:50`） | `:47-60` |
| Nacos v2.4.3 | **standalone 单副本**（`:66`） | **无卷**（容器删则配置/命名空间元数据丢） | unless-stopped（`:79`） | `NACOS_AUTH_ENABLE=false`（`:70`）；Xmx 512m | `:62-79` |
| RocketMQ NameSrv 5.3.1 | **单节点** | 无状态 | unless-stopped（`:89`） | — | `:81-89` |
| RocketMQ Broker | **单 broker，ASYNC_MASTER + ASYNC_FLUSH**（`rocketmq/broker.conf.tpl:7-8`，无从库、无 DLedger） | bind 挂载宿主目录 `./rocketmq/data`（`:114`），非命名卷 | unless-stopped（`:122`） | `autoCreateTopicEnable=true`（broker.conf.tpl:10，注释自承认生产必须关闭）；brokerIP1 按宿主 IP 模板替换（`:97-108`） | `:91-122` |
| RMQ Dashboard / Sentinel / Prometheus / Grafana | 均单副本 | 仅 Grafana 有卷（`:162-164`） | unless-stopped | Grafana admin/admin（`:160`）；Sentinel x86 模拟运行（`:139`） | `:124-170` |

补充观察：`deploy/rocketmq/data/` 下已存在 broker 运行期数据（commitlog/config/consumequeue 等），运行产物落在部署目录树内，建议加入忽略/清理并改用专用卷或外部存储。

### 7.3 生产要求对照（差距）

- MySQL：需主从/多 AZ 托管版（RDS/PolarDB 类）、自动备份与故障切换、账号最小权限；当前仓库仅给了一个 `mysql-proxy.shop-infra:3306` 地址占位，无任何交付物或验收约束。
- Redis：需 Redis Cluster/Sentinel ≥3 主或托管版、TLS/鉴权；当前 `REDIS_HOST: redis-cluster.shop-infra`（`00:33`）同样只有占位。
- RocketMQ：需多 Master（DLedger CommitLog 或主从 + Controller 切换）、双副本/同步刷盘按数据安全等级选择、NameSrv ≥2、关闭 autoCreate、生产消费链路在中间件故障下的演练；本地为可丢数据的单点异步形态。
- Nacos：生产需 ≥3 节点集群 + 鉴权；清单中 `NACOS_ADDR` 是单地址（`00:28`），依赖外部 VIP/Service 负载均衡到集群，仓库内未提供该集群。
- 应用层（8 服务）本身无状态、多副本、可被 PDB/反亲和/HPA 保护，已具备 HA 条件；**数据平面/控制平面 HA 是当前整套部署唯一的实质性单点来源，且不在本仓库交付范围内**。

---

## 结论分档

### A. 阻断项（上生产前必须解决）

1. **中间件高可用未交付且无约束**：MySQL/Redis/RocketMQ/Nacos 在 k8s 清单中仅有外部地址占位（`00:28-35`），仓库内无托管实例引用/StatefulSet/备份与切换验收；compose 全为单副本（含无鉴权、Nacos 无卷、RMQ 单点异步刷盘）。上线前必须以云托管或独立多节点集群落实并完成故障切换演练，否则数据库/缓存/MQ/注册中心任一节点故障即全站不可用。
2. **镜像交付链不可用**：`registry.example.com` 占位（8 处 image）、无 imagePullSecrets、无 CI 推送与 digest/不可变 tag 锁定（如 `10-services.yaml:52`、`00:77`）。真实集群无法按清单拉取镜像，必须替换为真实仓库并建立构建-签名-拉取凭据链路。

### B. 建议项（不阻断，按优先级）

1. 生产 JDBC `useSSL=false`（`10-services.yaml:66` 等 7 处）改为 TLS；同步评估 Redis/RocketMQ/Nacos 链路加密与鉴权。
2. 为网关补 HPA（当前 7 个业务有、网关无）。
3. NetworkPolicy 增加 Egress/默认拒绝基线，并把同命名空间规则收紧到具体端口（`30-networkpolicy.yaml:34-35`）。
4. 内存 request 与 JVM InitialRAMPercentage 失配（业务初始堆 ≈820Mi > request 768Mi；网关类似），建议调参避免常态超卖。
5. 跨 AZ 拓扑打散由 ScheduleAnyway 评估为 DoNotSchedule（多 AZ 集群）；补 Pod/Container securityContext（runAsNonRoot 显式化、drop capabilities、readOnlyRootFilesystem 需先解决 heapdump 路径）。
6. OOM heapdump 落容器 /tmp 会随 Pod 丢失（`Dockerfile:10`），改持久收集。
7. 镜像显式 imagePullPolicy: IfNotPresent + digest 固定；CI 用 buildx 出多架构镜像。
8. Ingress 显式固化 TLS 最低版本；RocketMQ 生产关闭 autoCreateTopic/SubscriptionGroup（`broker.conf.tpl:10-11`）。
9. 为 7 个业务服务补 Service（排障/去 Nacos 强绑定）；将 `deploy/rocketmq/data/` 运行期数据移出部署目录。
10. 脚本小项：ha-check 的 macOS 专用 IP 发现（`:51`）、ingress 端口回退（`:156`）、/16 网段假设（`:82`）；build 脚本末尾 apply 提示文案与"Secret 不覆盖"策略存在歧义。

### C. 已接受豁免（仅限本地 kind / 验收，有显式声明与技术隔离，准予保留）

1. **kind 单节点 2 副本同机调度**：基线清单保持 required 硬反亲和不变，ha-check 运行时 JSON Patch 放宽（`ha-check.sh:62-75`），并有基线文件 grep 守护（`:112-118`）；PDB Eviction、pod-kill、滚动不断流验收在单节点仍有效。
2. **kind 支付 mock 双开关 + 开发密钥**：`kind/00-kind-infra.yaml:29-31` + `kind/05-kind-secret.yaml:14-30`，注册组 `shop-ha-kind` 与宿主环境隔离；生产 ConfigMap mock 关闭（`00:37`），ChannelSecretProvider prod fail-fast 默认拒绝 mock。
3. **自签 TLS 证书**用于 kind（`tls/gen-self-signed.sh`），生产走 cert-manager（`20-tls-ingress.yaml:4-9,21`）。
4. **NetworkPolicy 在 kindnetd 下空操作**（`30:4-6`，ha-check `|| true`），生产 CNI 支持时生效。
5. **compose 中间件单副本 + 弱口令/无鉴权**（root/root、Redis 无密码、Nacos AUTH 关闭、Grafana admin/admin）：文件头明确"用于开发与功能验收，生产见 k8s"（`docker-compose.yml:1-3`），不得用于生产。
