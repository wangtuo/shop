# SMOKE_REPORT — K8s 高可用冒烟验收（kind）

- **结论：PASS=66 / FAIL=0**（`deploy/kubernetes/ha-check.sh` 全 6 节全绿）
- 验收时间：2026-09-17 08:06–08:14 CST
- 证据日志：`deploy/kubernetes/evidence/ha-check-20260917-run9.log`
- 验收脚本：`deploy/kubernetes/ha-check.sh`（context 硬编码保护：只允许 `kind-kind`）

## 1. 验收环境

| 项 | 值 |
|---|---|
| 集群 | kind 单节点 `kind-control-plane`（Debian 13），K8s **v1.37.0**，kubectl v1.36.1 |
| 节点规格 | 12 CPU / 16Gi（稳态请求占用 9150m / 12356Mi ≈ 76%） |
| 入口 | ingress-nginx；kind extraPortMappings 直映宿主 **80/443**；自签 TLS `shop.example.com` + HSTS |
| 工作负载 | 8 个 Deployment × 2 副本（16 Pod），PDB minAvailable=1，HPA ×7（min=2） |
| 镜像 | `registry.example.com/shop/shop-*:2.0.0`（构建于 2026-09-16 22:35 UTC，build-and-load-kind 载入） |
| 中间件 | 宿主 docker compose：MySQL 8 / Redis 7 / RocketMQ 5（broker 通告 `192.168.1.111:18081`）/ Nacos（Pod 独立分组 `shop-ha-kind`） |

## 2. 六节结果

| 节 | 内容 | 结果 |
|---|---|---|
| 1 | apply 生产清单 + kind 覆盖层；Secret 已存在不覆盖；单节点调度兼容补丁；TLS/HSTS/NetworkPolicy | PASS（脚本不阻断 WARN 0） |
| 2 | 8 工作负载 rollout（maxSurge=1/maxUnavailable=0）全部完成 | PASS |
| 3 | HA 资源断言 56 项 | 56 PASS（明细见 §3） |
| 4 | PDB policy/v1 Eviction：第 1 次 ALLOW，收敛后第 2 次 **429 BLOCKED** | 2 PASS |
| 5 | 286 发持续真实 HTTPS 流量下强杀网关 Pod + 业务 Pod + 两次 rollout restart | 3 PASS，**0 失败 / 0.00%** |
| 6 | HTTP 80 → HTTPS 443 强制跳转 | PASS（308） |

## 3. 资源断言明细（节 3，56 项全绿）

每个工作负载 6 项 × 8 = 48：
- `replicas=2` 且 `status.readyReplicas=2`
- `terminationGracePeriodSeconds=60`
- preStop sleep hook（优雅摘流）
- startupProbe（`/actuator/health/liveness`，5s×24 容错慢启动）
- PDB 存在

基线【清单文件】护栏 6 项（防止为 kind 顺手弱化生产清单）：
- `10-services.yaml` 含 `requiredDuringSchedulingIgnoredDuringExecution` 硬反亲和
- `10-services.yaml` 含 `topology.kubernetes.io/zone` 跨可用区 topologySpread
- `00-namespace-config.yaml` 网关清单含硬反亲和
- 7 个业务 HPA 全部存在、gateway Service、namespace bootstrap

## 4. PDB 强制执行（节 4）— 测试方法学修正

```
evict shop-product-service-69d4bccf5b-94bf7 -> ALLOW
PDB 状态已收敛：disruptionsAllowed=0 currentHealthy=1
evict shop-product-service-69d4bccf5b-lchqr (Ready 幸存者) -> BLOCKED
PASS PDB：再驱逐会击穿 minAvailable=1，API Server 429 拒绝
```

前几轮（run5/7/8）节 4 曾出现唯一 FAIL，经对照实验定位为**测试选靶缺陷，非 PDB 故障**：

1. P1 被驱逐后，Deployment 在 1 秒内创建 maxSurge 替换 Pod（未 Ready）。
2. K8s 对**未 Ready** Pod 的驱逐不消耗 PDB 预算（它本就不计入 `currentHealthy`）。
3. 旧脚本事后按名字排序取「另一个 Pod」，随机后缀可能恰好选中这个 surge Pod → 合法 ALLOW。

实测对照（`/tmp/pdb-exp3.sh`，保留方法学）：
- 驱逐 Ready 幸存者（连续两次，无间隔）→ **429 TooManyRequests** 稳定复现；
- 同窗口驱逐未 Ready 的 surge Pod → 201 ALLOW（符合 K8s 语义）。

修正（`ha-check.sh`）：两个 Pod 在第一次驱逐**之前快照**，P2 固定为快照中的 Ready 幸存者；
保留 `disruptionsAllowed=0` 轮询（消除 PDB 控制器传播竞态），并在第二次驱逐前再确认 P2 仍 Ready。
另注：试图用 `maxSurge=0/maxUnavailable=0`「冻结补员」被 K8s 准入拒绝（二者不允许同时为 0），该方案已弃用。

## 5. 真实流量连续性（节 5）

扰动序列（约 4 分钟，2 req/s HTTPS 打 Ingress 443，商品列表只读接口）：
强杀网关 Pod → 15s → 强杀订单 Pod → 15s → `rollout restart` 网关并等成 → 5s → `rollout restart` 订单并等成。

| 运行 | 请求数 | 失败 | 失败率 | 基线窗口(前10) | 备注 |
|---|---|---|---|---|---|
| run7 | 252 | 0 | **0.00%** | 0/10 | PDB 选靶缺陷修前 |
| run8 | 252 | 0 | **0.00%** | 0/10 | 同上（流量节本身全绿） |
| **run9** | **286** | **0** | **0.00%** | **0/10** | **全节 PASS，最终采信** |

判据 `<2%`，实测 0%。优雅终止链路（preStop + 60s grace + readiness 摘流 + maxUnavailable=0 滚动）成立。

## 6. HPA 内存阈值调优（本轮发现并修复）

现象：无流量稳态下 HPA 持续把业务副本扩到 3–4，单节点内存请求推到 99%，surge Pod 全部
Pending，滚动发布死锁。实测（requests memory=768Mi，limit=2Gi）：

| 服务 | 稳态 RSS/request | 旧阈值 70% 下 HPA desired |
|---|---|---|
| order | 582Mi = **74%** | 4 |
| product | 510Mi = 63% | 3 |
| user | 500Mi = 62% | 3 |
| pay | 493Mi = 61% | 3 |
| marketing | 498Mi = 61% | 4（向上取整滞留） |

修复：7 个 HPA 的内存阈值 70%→**85%**（触发线 653Mi，仍仅为 2Gi limit 的 32%，
距 OOMKill 1.35Gi；流量扩容由 CPU 65% 兜底），并修正清单里过时的风险注释。
改后 7 个 HPA 在无流量下全部稳定 `desiredReplicas=2`，节点请求占用回落至 76%。
变更文件：`deploy/kubernetes/10-services.yaml`（7 处阈值 + 7 处注释）。

## 7. 单节点豁免（生产语义不受影响）

生产清单保持 **required 硬反亲和（hostname）+ 跨 zone topologySpread DoNotSchedule** 不变；
ha-check 仅在单节点 kind 上以 JSON 补丁临时放宽为 preferred + ScheduleAnyway，并自动把
仍带硬反亲和的旧 RS 缩 0（脚本内有死锁机理注释：反亲和对现存 Pod 对称评估）。
多节点集群应导出 `SKIP_SCHEDULING_PATCH=1` 验证真反亲和——此项列入最终验收残差。

## 8. 关联证据：中间件混沌（宿主直跑形态）

`deploy/loadtest/chaos.sh` run6（2026-09-17 06:34）：**PASS=25 FAIL=0**
（证据：`deploy/kubernetes/evidence/chaos-20260917-run6.log`）。
- 持续流量 427 发 / 0 失败：Redis 宕机 20s、RocketMQ broker 宕机 45s 窗口及恢复后双 30s 稳态全绿；
- 7 库 outbox 在 broker 恢复后同一 90s deadline 内并行清空；7 库 V5 慢车道挂起探针 120s 内全部自愈。

## 9. 最终验收前仍需执行（残差，不影响本报告结论）

1. **最终 jar 重建复跑**：本轮镜像是 2026-09-16 22:35 UTC 构建；其后源码合入了
   B6（三处 Feign 空响应失败化）、B7（签到 self-invocation 事务代理）、B9（分账资金守恒
   公式修正）等改动。最终验收必须 8 模块重新 `mvn package` → build-and-load-kind 重建镜像
   → 在**同一组最终 jar**上重跑全量单测/E2E/chaos/ha-check/k6。
2. k6 性能与超卖闸门（`PERF_REPORT.md`）：经 kind Ingress 443（seed 脚本已支持
   `CURL_EXTRA="-k --resolve shop.example.com:443:127.0.0.1" BASE=https://shop.example.com`）。
3. 多节点 kind 真反亲和验收（见 §7）。
