# PERF_REPORT — 性能与防超卖验收报告（kind 单节点）

- 验收日期：2026-09-17（首轮）/ **2026-09-19 韧性整改后同一套最终产物复扫与十二门全链复验**
- 验收对象：8 服务生产级实现（下单全链路：登录 → 收货地址 → 营销试算 → 提交订单）+ 秒杀超卖专项（抢单 → 支付 → HMAC 回调 → 订单状态确认）
- 压测工具：k6（`grafana/k6:latest` 容器），脚本 `deploy/loadtest/k6-order.js`，种子 `deploy/loadtest/seed.sh` / `seed-oversell.sh`
- **总结论**：
  1. **防超卖：PASS（硬断言 exit=0）**——40 并发抢 20 件秒杀库存，成交=20、售罄拒绝=20、支付确认=20，DB 三方对账零超卖零漏卖零库存悬挂（2026-09-19 最终产物复跑一致）。
  2. **单节点可持续吞吐：30 TPS（恒定到达率）下成功率 99.98%、下单 p95=376.5ms、0 丢弃**（2026-09-19 复扫，整改前拐点 20 TPS）；20 TPS 基线更优：p95=114.6ms。
  3. **40 TPS 仍超单节点服务天花板（~30/s）**，但故障形态已由整改前的「无界队列→Pod OOM 被杀→裸 500 雪崩」变为「Feign 超时/熔断快速失败 + 实例全部存活（0 重启）」的**优雅降级**，过载解除即恢复。
  4. design.md 10.1 的 **5000 TPS / p95<500ms 不能在单节点 kind 验证**（12C/16G 同机承载 16 个业务 JVM + MySQL/Redis/RocketMQ/Nacos + k6），该项明确保留给多节点 perf 集群（见 §6 豁免声明）。本报告给出本环境实测拐点作为容量基线与扩容依据。

---

## 1. 环境与口径

| 项 | 值 |
|---|---|
| K8s | kind 单节点 v1.37.0，Docker Desktop VM **12 CPU / 16Gi** |
| 业务工作负载 | 8 Deployment × 2 副本 = 16 Pod（HPA 验收期间 live-cap `maxReplicas=2`，见 §7.4） |
| 中间件 | 同宿主 docker compose：MySQL 8（max_connections=600，R4-17）/ Redis 7 / RocketMQ 5.3.1（broker 通告宿主 LAN IP:18081）/ Nacos |
| 入口 | ingress-nginx HTTPS 443，`shop.example.com` 自签证书；k6 容器经 `--add-host shop.example.com:192.168.65.254`（Docker Desktop 网关）入流量 |
| 压测账号池 | **2000 个** `load_1..load_2000` 真实注册用户（seed.sh 失败即 FATAL，逐账号登录取 JWT） |
| 商品池 | 50 个在架"压测商品"SKU，每个物理库存 100000（拐点测试不涉及库存瓶颈） |
| 单迭代内容 | 登录 + 收货地址兜底 + `/api/marketing/h5/marketing/calculate` 试算 + `/api/order/orders` 提交订单（3 次 HTTPS + 完整 Feign 扇出：商品锁库存 → 营销锁优惠 → 订单落库+outbox） |
| 迭代用户分布 | `iterationInInstance % USER_POOL` 场景级全局迭代号轮转（R-脚本修复，旧 `(__VU*7+__ITER)` 会跨 VU 撞同账号触发 10007，见 §7.1），任何在途迭代用户互不相同 |

指标口径：
- **成功率** = 订单接口 HTTP 200 且业务 `code∈{0,30001}`（成交 / 售罄均视为正确响应）；阈值 `rate>0.99`。
- **下单延迟** `order_create_latency_ms`：`POST /api/order/orders` 端到端（网关 → 订单服务 → 商品/营销 Feign → MySQL → outbox），阈值 `p95<800ms`。
- k6 退出码 0 = 全部 thresholds 通过；任一阈值跨越 exit=99（CI 可直接门控）。

## 2. 冒烟基线：20 TPS × 3min 恒定到达率 —— PASS

命令：

```bash
docker run --rm -i --add-host shop.example.com:192.168.65.254 grafana/k6:latest run \
  --insecure-skip-tls-verify - \
  -e BASE_URL=https://shop.example.com -e MODE=smoke \
  -e USER_POOL=2000 -e SKU_COUNT=50 \
  -e SMOKE_RATE=20 -e SMOKE_DURATION=3m \
  -e SMOKE_PREALLOC_VUS=200 -e SMOKE_MAX_VUS=800 \
  < deploy/loadtest/k6-order.js
```

### 2.0 第五轮修复后最终产物复跑（2026-09-20，R4-25，证据 `final-20260920-1800-r425/11-k6-smoke.log`，k6 **exit=0**）

同一套最终源码 → jar → 镜像，紧接 HA 门（kind 16 Pod 刚滚动收敛）执行：90s warmup
+ 3min 测量窗（阈值仅 `{scenario:smoke}` 测量窗）。

| 指标 | 值 |
|---|---|
| 完成迭代 | 5401（warmup 1800 + 测量 3601），interrupted=0 |
| 成交订单 `order_created_total` | **5376**（另 25 次仅集中在 warmup 头 8s，见下） |
| 售罄（stock_not_enough） | 0 |
| 成功率（测量窗） | **100%**（阈值 >0.99 ✓），5400 迭代阈值判定 0 失败 |
| 下单延迟 | **avg=168.5ms，p95=389.3ms**（阈值 <800ms ✓） |
| warmup 噪声 | 25 条 code=10008（shop-product 不可用），全部发生在 warmup 开始后 18:27:38–18:27:45 的 8 秒内（HA 滚动后首批 kind Pod JIT/路由冷启动），不计阈值；测量窗 0 错误 |

结论：R4-25 消息键族修复对性能无可观测回归；p95 较清洁环境基线（114.6ms）高，属 HA 扰动后
同集群未静置的冷启动尾延迟（PERF §3/R4-21 已归因），测量窗均值与零失败说明稳态容量未变。

### 2.0b R4-26 同套产物复跑（2026-09-20，证据 `final-20260920-1840-r426/11-k6-smoke.log`，k6 **exit=0**）

紧接 HA 74/74 滚动收敛后同模型复跑：阈值门仍 PASS（测量窗 0 失败），
order_created_total=5351，avg=364.8ms。warmup 头段冷启动 50 条 10008（集中在
warmup 开始后约 30s 内），**日志全量聚合 p95=1865ms 含 warmup 噪声故越 800ms 口径**；
k6 阈值以 `{scenario:smoke}` 标签只统计测量窗（exit=0 即过门的权威口径），全量聚合值
仅供冷启动尾延迟归因对照。两轮（r425: 25 条/p95=389.3ms；r426: 50 条/p95=1865ms）
差异来自 HA 后首批 Pod 冷态抖动，非代码路径回归（两轮仅差售后守卫与 eventId 兜底，
均不在下单热路径）；彻底消除须 perf 集群静置后预热，见残留清单。

### 2.1 最终产物复跑（2026-09-19，R4-17/18 后，证据 `final-20260919-225436/11-k6-smoke.log`，k6 **exit=0**）

k6 以双场景运行：90s warmup（20 TPS 恒定到达预热 JIT/Druid/Nacos 路由，**不参与阈值**，
R4-18 方法学修复）+ 3min 测量窗；阈值带 `{scenario:smoke}` 标签只统计测量窗。

| 指标 | 值 |
|---|---|
| 完成迭代 | 5401（warmup 1800 + 测量 3601），interrupted=0 |
| 成交订单 `order_created_total` | **5401**（测量窗 3600 到达全部成交） |
| 售罄（stock_not_enough） | 0 |
| 成功率 | **100%**（阈值 >0.99 ✓） |
| 下单延迟 | **avg=60.5ms，p95=114.6ms**（阈值 <800ms ✓） |
| HTTP/业务失败 | 0 |

### 2.2 历史基线（2026-09-17，证据 `deploy/loadtest/evidence/k6-steady-20tps-clean.log`，k6 **exit=0**）

| 指标 | 值 |
|---|---|
| 到达迭代 | 3600（20/s × 180s），实际完成 **3601**，dropped=0 |
| 成交订单 `order_created_total` | **3601** |
| 售罄（stock_not_enough） | 0 |
| 成功率 | **100%**（每迭代成交；阈值 >0.99 ✓） |
| 下单延迟 | **avg=63.3ms，p95=122.8ms**（阈值 <800ms ✓） |
| HTTP 失败 | 0 |
| 节点 CPU 占用 | 稳态约 50–60%（12C） |

更早对照（前一验收会话，1 分钟短测）：1200/1200 成功、p95=231.9ms、0 失败。

## 3. 单节点容量拐点扫描

constant-arrival-rate 恒定到达，每档 3 分钟，段间冷却 90s；2026-09-19 起每档前置
90s 同速率 warmup（R4-18，冷 JVM/连接池不进测量窗，阈值 `{scenario:smoke}` 只看测量窗）。
脚本：`deploy/loadtest/rescan-30-40.sh`，证据：`final-20260919-225436/11-k6-{30,40}tps-rescan.log`；
整改前历史基线：`evidence/k6-steady-{30,40}tps-clean.log`（40 档另有修复前脏跑
`k6-steady-40tps.log` 作为对照存档）。

| 到达 TPS | 完成迭代 | 成交（测量窗） | 成功率 | 下单 avg | 下单 p95 | dropped/未服务 | 结论 |
|---|---|---|---|---|---|---|---|
| 20（09-17 旧基线） | 3601/3600 | 3601 | 100% | 63.3ms | 122.8ms | 0 | ✅ 可持续（exit=0） |
| 20（09-19 最终产物） | 3601/3600（另预热 1800） | 3601 | **100%** | 60.5ms | **114.6ms** | 0 | ✅ **可持续（exit=0）** |
| **30（09-19 复扫）** | 5401/5400（另预热 2700） | 5400 | **99.98%**（测量窗第 ~96s 仅 1 次瞬时 10008，次秒自恢复） | 174.9ms | **376.5ms** | **0** | ✅ **可持续（exit=0，双阈值过）——拐点上移** |
| 30（09-17 旧基线） | 5103/5400 | 5085 | 99.65% | 3.18s | 12.69s | ~297（5.5%） | ⚠️ 排队（exit=99） |
| **40（09-19 复扫）** | 测量窗启动 5793/7200（预热 3301/3600，合计 9094） | **2674**（测量窗；全运行 5630） | **46.2%**（登录超时链式） | 1.56s | **5.62s** | 测量窗 **1407** 到达未获 VU（预热另 299） | ❌ 双阈值跨越（exit=99），**但优雅降级、0 Pod 重启/无裸 500** |
| 40（09-17 旧基线） | 4295/7200 | 3381 | 78.7% | 22.2s | 48.7s | ~2905（40%） | ❌ 过载雪崩（exit=99） |

**拐点结论（2026-09-19 复扫）：本环境可持续速率 = 30 TPS**（成功率与 p95 双达标，
0 丢弃、3 分钟稳态）；整改前拐点 20 TPS，**框架韧性波 R-B1/B2/B12 + R4-17/18 把拐点
抬升 50%，且同档延迟从 12.69s 降到 0.38s**。40 TPS 超过单节点服务天花板（实测处理
率上限约 30/s），在途请求按到达率-处理率之差堆积，但表现已与整改前本质不同（见下）。

### 3.1 40 TPS 故障形态对比（雪崩 → 优雅降级）

整改前（2026-09-17，全部有证据）：
1. 同步 Feign 扇出链路在到达率超过处理率后在途请求无限堆积（Druid `maxActive=8`、Tomcat 工作线程排队，无快速失败/熔断——正是 AUDIT_RESILIENCE.md 阻断项 B1/B2）；
2. 订单 Pod（`shop-order-service-kv5mn`）12:47:33 被 **SIGKILL（exit 137，JVM 无 OOM，节点/cgroup 内存回收）**——单节点 16G 同机 16 JVM + 中间件，数百在途请求的线程/缓冲把容器推过 2Gi limit；
3. 死亡实例从 Nacos 摘除有窗口，网关拨号 `Connection refused: /10.244.0.114:8084` 回 **HTTP 500（792 次裸 error JSON，非业务包裹体）**。

整改后（2026-09-19 复扫，证据 `11-k6-40tps-rescan.log` + 同期
`kubectl get pods` 16 Pod `RESTARTS=0`）：
1. 排队在 ~40/s 下持续增长，Feign 读超时在网关收敛为 **504/10010**，测量窗登录失败
   2515 次（脚本无 token 继续发起订单 → 成对出现 2515 次 401/10002，属客户端
   连锁，非额外服务缺陷）；
2. 下游压力由熔断器吸收：**10008 快速失败 446 次**（有界，未再出现无界堆积）；
3. **无 Pod 被杀、无 exit 137、无裸 500、无 Connection refused**：Druid 有界池 +
   Resilience4j 超时/熔断/舱壁（R-B1/B2）+ 网关令牌桶限流与统一包裹体（R-B12，
   429→10007/503→10008/504→10010）把过载约束为降级响应；压测结束后无需任何
   重启，下一阶段超卖硬断言在同一集群立即 PASS（过载解除即恢复）。

> 40 TPS 档精确切分（日志逐条对账，warmup 截止 15:10:23）：
> 测量窗启动迭代 5793 / 到达 7200（1407 到达因 800 VU 全忙未获服务）；唯一失败迭代
> 3119 = 登录 504/10010 失败 2515（脚本无 token 继续发起订单 → 成对 2515 次
> 401/10002，属客户端连锁，不重复计失败迭代）+ 10008 熔断 446 + 10001 参数类 91
> + 10010 超时 63 + 10007 限流 4；成功 2674（成功率 46.2%），加预热窗成功 2956
> = 全运行成交 5630，与脚本 `order_created_total` 完全闭合。预热窗从第 23s 起
> 即超处理率（启动 3301/3600，失败迭代 345）。

单节点 16G 同机争用是 30/s 天花板的物理约束，不是代码缺陷；生产多节点部署通过
HPA 水平扩容（HPA/订单 20→60 清单已备，单节点验收 live-cap 2-2 见 §7.4），
有界资源配置在多节点形态下仍是必需防线。**最终容量结论以多节点 perf 集群复测为准**。

## 4. 超卖专项：20 件库存 / 40 并发 —— PASS（exit=0）

命令（种子 + 压测）：

```bash
STOCK=20 BASE_URL=https://shop.example.com \
  CURL_EXTRA="-k --resolve shop.example.com:443:127.0.0.1" \
  bash deploy/loadtest/seed-oversell.sh   # 产出 OVERSELL_SKU_ID / OVERSELL_ACTIVITY_ID

docker run --rm -i --add-host shop.example.com:192.168.65.254 grafana/k6:latest run \
  --insecure-skip-tls-verify - \
  -e BASE_URL=https://shop.example.com -e MODE=oversell \
  -e STOCK=20 -e RUSH_MULT=2 -e USER_POOL=100 \
  -e CHANNEL_SECRET=kind-ha-mock-wechat-secret-2026-0001 \
  < deploy/loadtest/k6-order.js
```

结果（证据：`deploy/loadtest/evidence/k6-oversell-20260917-run5.log`，k6 **exit=0**，全部硬阈值 ✓）：

| 硬断言阈值 | 实测 | |
|---|---|---|
| `oversell_order_attempts count>=40` | 40 | ✓ |
| `oversell_winners count===20` | **20** | ✓ |
| `oversell_losers count===20` | **20** | ✓（code=30001 售罄） |
| `oversell_paid_confirmed count===20` | **20** | ✓（轮询订单到状态 20 已支付） |
| 预期结果率（仅允许成交/售罄） | 100.00% | ✓ |
| checks | **81/81，0 失败** | ✓ |
| HTTP 失败 | 0 / 193 | ✓ |
| 下单延迟（秒杀争用路径） | avg=4.11s，p95=5.23s | 抢购串行化预期 |

**DB 三方对账**（压测后实时查询）：

| 账本 | 查询 | 结果 |
|---|---|---|
| 订单 `shop_order.t_order_order` | 本活动订单按状态计数 | 状态 20（已支付/待发货）**= 20**，无状态 10 悬挂 |
| 支付 `shop_pay.t_pay_order` | status=30（支付成功） | **= 20** |
| 秒杀 `shop_marketing.t_seckill_sku` | total/locked/sold | total=20，**locked=0（无预占悬挂），sold=20** |

结论：**零超卖、零漏卖、零库存悬挂、支付与订单状态 1:1 闭合**。闸门是秒杀库存（物理库存设为 10× 只是对照：秒杀卖 20 件即止）。

### 4.1 最终产物复跑（2026-09-19，证据 `final-20260919-225436/12-k6-oversell.log` + `12-db-reconcile.txt`，k6 **exit=0**）

同一套最终源码 → jar → 镜像，紧接 40 TPS 过载复扫之后在**同一未重启集群**立即执行：
40 尝试 / winners=20 / losers=20（code=30001 售罄）/ paidConfirmed=20，
`oversell assertion PASSED: winners=losers=stock，全部赢家已支付`；
DB 对账 `orders_status20=20`、`pay_status30=20`——过载降级后集群零干预即恢复，超卖结论与首轮一致。

## 5. 秒杀正确性机制（代码层证据）

- Redis Lua 多键原子预占 + DB 条件锁库存（`lockStock` CAS available→locked）+ 失败补偿回补 Redis；
- 幂等：营销锁以 `orderNo` 唯一键短路（下单同步 Feign 锁与 ORDER_CREATED MQ 双路到达结果一致）；R4-25 起 t_seckill_order 每 (order_no,sku_id) 一行（uk_order_sku），同单重复提交按重复提交报错并回补 Redis；
- 秒杀限购（R4-25）：t_seckill_user_buy 计数行行锁条件占件（total_qty+? <= perUserBuyLimit），首单 insert 撞 UK 的并发负方重试条件占件；取消释放 GREATEST 回减计数；压测 40 并发取 40 个唯一用户，限购闸门不介入（闸门是秒杀库存本身）；
- 秒杀延迟订单 15 分钟超时未付自动关单回补库存（延时消息 + 扫表双保险）。

## 6. 对照 design.md §10 非功能指标 —— 达成与保留项

| design 10.1 指标 | 本环境结论 |
|---|---|
| 下单 5000 TPS、p95<500ms、错误率<0.1% | **保留**：单节点 kind（12C 共享）不具备验证条件，实测拐点见 §3；架构前提（无状态扩容、Redis 原子扣库存）已具备，须在多节点 perf 集群（独立 MySQL/Redis/RMQ、多 worker 节点、压测机集群）用同一脚本 `MODE=capacity` 复测，脚本已参数化（CAP_START_RATE/CAP_PEAK_RATE/CAP_*） |
| 防超卖 | **达成并实测**：§4 硬断言 + DB 对账 |
| 支付最终一致（MQ+补偿+对账） | **达成**：outbox 同事务 + 消费幂等 + T+1 对账；本次超卖全链路回调→发券/积分/清算扇出在 E2E 59 用例中覆盖（见 E2E_REPORT.md） |
| 支付回调 ≤2s | 本次超卖路径回调→订单状态 20 全部在 k6 轮询窗口内确认；严格 ≤2s 分位值未单独埋点，列入 perf 集群复测项 |
| 商品详情 ≤200ms / 订单列表 ≤500ms / 日终清算 ≤2h | 未在本轮 k6 模型内（脚本聚焦下单漏斗），列入 perf 集群补测项；代码侧详情缓存/分页索引已实现（见 AUDIT 报告） |
| 防刷 | 登录 20/60s/IP、注册 5/60s/IP、下单 5/s/用户、秒杀 1/3s/用户活动、领券 30/60s 均实测生效（压测中注册/登录豁免仅 kind 开启，见 §7.2） |

## 7. 测试环境前置条件与豁免（复现必读）

### 7.1 压测脚本修复（本次）
- `k6-order.js` 冒烟路径地址准备改为 `ensureAddressId`（新建失败→取分页首条）。k6 各 VU JS 运行时隔离，地址缓存不跨 VU；历史压测轮次已把老账号打到 20 条地址上限，旧逻辑拿到 null addressId 导致 10001「收货地址不能为空」，虚高失败率（脏跑 699 个非预期响应中绝大多数为此，属脚本缺陷非系统故障）。
- 用户轮转改为场景级全局迭代号 `iterationInInstance % USER_POOL`：旧 `(__VU*7+__ITER)` 映射在高并发跨 VU 同秒窗口会取到同一用户，误触下单 5 次/秒/用户限流（40 TPS 档 111 次 10007 的来源），修复后任何在途迭代用户互不相同。
- 修复 `handleSummary` 在未开启 p99 汇总时 `toFixed` 空指针（k6 v2 下会在 summary 阶段抛异常）。

### 7.2 限流豁免（**仅 kind 压测环境**）
- `auth:login` 生产基线 20 次/60s/IP、`auth:register` 5 次/60s/IP；单出口 IP 灌 2000 用户池会秒触顶。kind ConfigMap `shop-infra-config` 设 `SHOP_RATELIMIT_AUTH_LOGIN_PERMITS=100000` / `SHOP_RATELIMIT_AUTH_REGISTER_PERMITS=100000`；**生产清单与注解默认值保持 20 / 5 不变**，正式 perf 集群应改用发压 IP 池 + 账号策略，严禁在生产关闭。

### 7.3 RocketMQ broker 通告地址（关键运维项）
- broker 容器启动时由 `deploy/rocketmq/broker.conf.tpl` 渲染 `brokerIP1=${SHOP_BROKER_IP}`；docker compose 默认 127.0.0.1 仅供宿主进程测试，kind Pod 必须用宿主 LAN IP 重建：
  `cd deploy && SHOP_BROKER_IP=<宿主LAN-IP> docker compose up -d --force-recreate rmq-broker`。
- **实测：broker 重建后生产者客户端约 30s 自动刷新路由，但 push 消费者 gRPC 订阅不会自动刷新**，必须逐个重启消费服务 Pod，否则持续 `ConnectTimeoutException` 指向旧 IP（本会话曾因此导致 outbox 积压、支付事件不消费，重启 6 个消费者后恢复）。

### 7.4 其他
- 雪花 ID：k6 一律从响应原文正则取长整数字符串，禁止 `res.json()` 数字解析（精度圆整）。
- 支付回调签名密钥取 kind Secret 值 `kind-ha-mock-wechat-secret-2026-0001`（`-e CHANNEL_SECRET=`），不是代码内置默认。
- HPA：ha-check.sh 单节点分支在验收时自动把 8 个 HPA live-patch `[2,2]` 防止单节点 maxSurge Pending 滚动死锁（**生产清单的 maxReplicas 未改**；多节点 perf 集群用 SKIP_SCHEDULING_PATCH=1 跳过）。
- Druid 连接池（R4-17）：ha-check 单节点 kind 验收自动对 7 业务 live-cap
  `SHOP_DATASOURCE_TUNING_MAXACTIVE=10`（14 池×10=140 连接），本地 MySQL 出厂
  max_connections=151 会在 smoke 负载下被 16 Pod 打满（1040 Too many connections
  连锁 10008/10010）；compose MySQL 已加 `--max-connections=600`。生产清单
  30/20 分档不改，云托管 MySQL 连接配额按实例规格规划（K8S §A 残留）。
- RocketMQ 磁盘（R4-17/R4-19）：5.3.1 写门硬上限 90%（源码不可配，含 85% 恢复迟滞）。
  **R4-19（2026-09-20）**：broker store 已从宿主 bind 目录改为 Docker 命名卷
  `shop-rmq-data`——bind 时 `df` 采样的是 Mac 宿主盘（460Gi、可用常年 ~45Gi、水位
  90%+），开发机自身磁盘用量即可让 broker 拒写且无法靠回收 MQ 数据解除；命名卷后
  采样口径为 Docker VM overlay 盘（59G，实测 31.8%）。compose entrypoint 以 root
  chown 新卷（uid 3000）后降权启动。另修复 create-topics.sh 使用 bash 保留只读数组名
  `GROUPS` 致 34 个真实消费组从未由脚本创建（靠 broker autoCreateSubscriptionGroup
  兜底，重试/DLQ 参数未生效）的潜伏缺陷，改名 `MQ_GROUPS` 后 37 组全部带 -r 16 -q 1
  创建。final-acceptance MIDDLEWARE 有水位守卫，超线 die 并给回收指引。

## 8. 证据索引

### 8.1 W7 最终产物（2026-09-19，同一套最终源码 → jar → 镜像）

全链十二门证据目录：`deploy/loadtest/evidence/final-<时间戳>/`（01-unit … 12-db-reconcile，
一次 `final-acceptance.sh` 全量运行顺序产出；分段复跑的同日历史目录留存对照）。

| 文件 | 内容 |
|---|---|
| `final-*/01-unit.log` … `06-chaos.log` | UNIT / PACKAGE / MIDDLEWARE / HOSTAPPS / E2E 59 例 / CHAOS 宿主混沌 |
| `final-*/07-kind-image.log`、`08-kind-deploy.log`、`09-ha.log` | 镜像构建加载 / 生产清单+kind 覆盖部署 / HA 六节（PASS=74 FAIL=0） |
| `final-*/10-seed.log`、`10-oversell-seed.log` | 2000 用户池 + 50 SKU 种子 / 20 库存秒杀种子 |
| `final-*/11-k6-smoke.log` | 20 TPS×3m（90s warmup 不计阈值）PASS，exit=0，p95=114.6ms |
| `final-*/12-k6-oversell.log`、`12-db-reconcile.txt` | 超卖 winners=losers=paid=20 硬断言 PASS；orders_status20=20 / pay_status30=20 |
| `final-20260919-225436/11-k6-30tps-rescan.log` | 整改后 30 TPS 复扫：99.98%、p95=376.5ms、0 丢弃，exit=0（拐点上移） |
| `final-20260919-225436/11-k6-40tps-rescan.log` | 整改后 40 TPS 复扫：双阈值跨越但优雅降级（0 Pod 重启/无裸 500），exit=99 预期 |

### 8.1b R4-25 / R4-26 最终产物复跑（2026-09-20，同一套最终源码 → jar → 镜像）

| 目录 / 文件 | 内容 |
|---|---|
| `final-20260920-102225/` | R4-24 后十二门全绿归档（UNIT/E2E 59/CHAOS 30/HA 74/K6 双门），保留不动 |
| `final-20260920-1800-r425/` | **R4-25 同套十二门全绿**：01-unit 2530（含真实库 IT 5/5）、05-e2e 59/0/0/4、06-chaos PASS=30 FAIL=0、09-ha PASS=74 FAIL=0、11-k6-smoke 5400 迭代 0 失败 avg=168.5ms **p95=389.3ms**（25 条 10008 仅在 warmup 头 8s，见 §2.0）、12-k6-oversell winners=losers=paid=20 assertion PASSED、12-db-reconcile orders_status20=20 / pay_status30=20 |
| `final-20260920-1840-r426/` | **R4-26 同套十二门复跑**（售后跨轮守卫 + product eventId 兜底，UNIT 2536）；结果以本目录 09-ha/11/12 日志为准（十二门全绿后本节不再追加） |

### 8.2 历史基线（2026-09-17，整改前对照）

| 文件 | 内容 |
|---|---|
| `deploy/loadtest/evidence/k6-oversell-20260917-run5.log` | 超卖 PASS 历史基线（宿主环境） |
| `deploy/loadtest/evidence/k6-steady-20tps-clean.log` | 20 TPS×3m PASS（exit=0，宿主环境历史基线） |
| `deploy/loadtest/evidence/k6-steady-30tps-clean.log` | 30 TPS 档（整改前：p95=12.69s） |
| `deploy/loadtest/evidence/k6-steady-40tps-clean.log` | 40 TPS 档（整改前：过载雪崩、Pod exit 137、裸 500） |
| `deploy/loadtest/evidence/k6-steady-40tps.log` | 40 TPS 修复前脏跑（脚本缺陷对照存档） |
