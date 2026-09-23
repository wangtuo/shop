# 生产运维手册（Runbook）

## 1. 部署拓扑

| 层 | 组件 | 形态 |
|---|---|---|
| 接入 | Spring Cloud Gateway | 2 副本 + Service/Ingress，无状态 |
| 应用 | 7 个业务服务 | 2 副本起步，order/product/pay HPA 上限 6，podAntiAffinity 跨节点 |
| 注册/配置 | Nacos | 生产 ≥3 节点集群（本地 standalone 仅验收用） |
| 数据 | MySQL 8 | 云托管主从/分库：每服务独立 schema（shop_user/product/marketing/order/pay/settlement/aftersale） |
| 缓存/锁 | Redis 7.2 | 集群/哨兵；Redisson 分布式锁、幂等 SETNX、秒杀库存 |
| 消息 | RocketMQ 5.x | namesrv ≥2 + broker 主从；生产关闭 autoCreateTopic |
| 流控 | Sentinel Dashboard + 规则持久化 | |
| 可观测 | Prometheus + Grafana + Actuator | 日志：全链路 traceId（X-Trace-Id） |

K8s 清单：`deploy/kubernetes/`；本地 kind 验收：`deploy/kubernetes/kind/`。

## 2. 发布

```bash
# 1) 全量构建+镜像
mvn clean package -DskipTests
TAG=2.0.0 deploy/kubernetes/build-and-load-kind.sh        # kind 验收
# 生产：docker build/push 到 registry.example.com/shop/<module>:$TAG

# 2) 滚动发布（maxUnavailable:0 + PDB minAvailable:1 保证不中断）
kubectl --context <ctx> -n shop set image deployment/shop-order-service \
  app=registry.example.com/shop/shop-order-service:$TAG
kubectl --context <ctx> -n shop rollout status deploy/shop-order-service
```

回滚：`kubectl -n shop rollout undo deployment/<name>`（镜像 tag 不可变，禁止覆盖发布）。

## 3. 健康检查与告警

- 探针：`/actuator/health/liveness`（90s 后）、`/actuator/health/readiness`（40s 后）；探针组已显式开启。
- Grafana 看板「Shop Microservices Overview」：QPS、P95/P99、5xx 率、JVM 堆/GC、HikariCP。
- 关键告警建议：5xx 率 >1% 持续 3 分钟；P99 >1s；服务健康实例 <2；HikariCP pending >0 持续；RocketMQ 堆积；定时任务连续 2 周期未执行（ShedLock）。

## 4. 应急预案

| 故障 | 现象 | 处置 |
|---|---|---|
| 单 Pod 宕机 | readiness 失败、Nacos 摘除 | K8s 自动重建；验证流量切到其余副本 |
| MySQL 主库故障 | 写入报错 | 切换只读/从库提升；服务依赖连接池重连（Druid `testWhileIdle`） |
| Redis 故障 | 锁/幂等/秒杀降级 | 集群自动故障转移；恢复前关闭秒杀活动入口；幂等键失效期间依赖 DB 唯一约束兜底 |
| RocketMQ 故障 | 事件积压 | 生产端本地日志+失败不阻断主链路（支付回调内 DB 落账优先）；恢复后补偿扫描（@SchedulerLock 双保险）追平 |
| Broker 磁盘水位告警（W7 实证） | 生产者报 `50001 … the broker's disk is full [CL: 0.90 …]`，outbox 退避重试、支付事件不扇出 | 见 §4.1 |
| 消息重复 | — | 消费端 mq_consume 唯一键 + 业务条件更新，天然幂等 |
| 超卖风险 | 库存为负/零 | 库存扣减全部条件 UPDATE（stock_num ≥ qty）+ 锁定流水；对账任务每日核对 |
| 保证金不足 | DEPOSIT_ALERT 事件 | 商户限提，运营充值；阈值 <50% 触发 |
| 支付渠道单边账 | T+1 对账差错 | 长款补单/短款追款/金额不符挂起人工；差错表可重试 |

### 4.1 Broker 磁盘水位拒绝写入（W7 实战处置）

RocketMQ broker 对 commitlog 卷做磁盘比例采样：达到写门 `diskSpaceWarningLevelRatio`
（默认 **0.90，含等于**）即拒绝全部 PUT，
客户端表现为 `InternalErrorException [response-code=50001] … disk is full [CL: 0.90 CQ: 0.90 INDEX: 0.90]`，
业务侧 outbox 退避重试、支付成功事件无法扇出（订单停在待支付）。

> **R4-19 又一根因（2026-09-20，macOS Docker Desktop 专属）：store 采样的是哪个盘取决于挂载方式。**
> broker store 若 **bind 挂载宿主目录**（旧版 `./rocketmq/data:/home/rocketmq/store`），
> 容器内 `df /home/rocketmq/store` 采样的是 **Mac 宿主盘**（virtiofs0，460Gi，开发机
> 自身数据占 369Gi、可用常年仅 ~45Gi，水位 90%+）——此时 broker 拒写与 MQ 负载完全
> 无关，且宿主数据不可删，§4.1 的回收手段最多回收几 GB，**永远无法降到 0.85 以下**，
> 冷启动也无效。修复是把 store 改为 Docker **命名卷 `shop-rmq-data`**（compose 已改），
> 采样口径变成 Docker VM overlay 盘（59G，实测 ~32%）。旧 bind 目录保留不删、不再挂载。
> 注意：Docker 新建命名卷属主是 root，broker 进程是 uid 3000，compose entrypoint 已
> 以 root 先 `chown -R rocketmq:rocketmq /home/rocketmq/store` 再 runuser 降权启动，
> 裸 `docker run -v 新卷` 复现实验必须自行 chown，否则 `store/lock Permission denied` 崩溃循环。
>
> **R4-17 核对 RocketMQ 5.3.1 官方源码的三个事实**：
> 1. 写门 0.90 是**源码硬上限**（DefaultMessageStore$CleanCommitLogService，
>    `> 0.90 一律截到 0.90`），broker.conf 的 `diskMaxUsedSpaceRatio`（只是过期文件
>    清理阈值，≤95）与 `-Drocketmq.broker.diskSpaceWarningLevelRatio` 都无法抬高；
> 2. 标志带**迟滞**：一旦 mark disk full，必须回落到 0.85 以下才恢复（0.898 重启前
>    仍拒写）——所以回收后必须让 broker **冷启动**（force-recreate），不能只 restart；
> 3. broker.conf.tpl 已关 commitlog 1GB 预分配/预热，防止滚动新文件瞬时打穿水位。
> final-acceptance.sh MIDDLEWARE 阶段有水位守卫（≥90% 直接 die 并打印本回收清单）。

本地 Docker Desktop 形态处置（**只清可重建物，禁止删 volume/在用镜像**）：

```bash
docker system df                              # 先看 Build Cache / Images 可回收量
docker builder prune -af                      # 清全部 build cache（W7 一次回收 2.1GB）
docker image prune -f                         # 清 dangling 镜像
docker volume prune -f                        # 清 orphan 卷（shop 数据卷在用不会被删）
docker exec shop-rmq-broker sh -lc 'rm -rf /home/rocketmq/logs/rocketmqlogs/otherdays/*'
# broker 容器内视角（与宿主 df 因 virtiofs 统计口径略有差异，以 broker 采样为准）：
docker exec shop-rmq-broker sh -lc 'df /home/rocketmq/store | awk "NR==2{print \$3*100/\$2\"%\"}"'
# 降到 90% 以下后【冷启动】（restart 不保证重放启动状态判定，force-recreate 最稳）：
cd deploy && SHOP_BROKER_IP=<宿主LAN-IP> docker compose up -d --force-recreate rmq-broker
docker exec shop-rmq-broker sh -lc 'cd /home/rocketmq/rocketmq-5.3.1/bin && sh mqadmin sendMessage -n rmq-namesrv:9876 -t shop_order_created -p probe'  # 输出 SEND_OK 即恢复
```

恢复验证：pay/order 日志 OutboxRelayDispatcher 不再出现「disk is full」，积压行 `t_mq_outbox.status` 由 0 转 1；
新下单一笔支付后订单状态到 20。注意 pending=0 不代表有问题——延迟消息（支付超时/自动确认/售后窗口）`deliver_at` 在未来，到期前必然是 0。

生产：broker 卷容量与水位必须纳入容量监控（使用率 80% 预警、85% 扩容/清理 commitlog），禁止依赖运行时手工 prune。

## 5. 数据一致性兜底

- 下单：TCC 补偿（库存/优惠/积分锁定，失败逐项释放）。
- 支付→履约：MQ 事件最终一致 + 每个超时点"延迟消息 + DB 扫描"双保险。
- 日终清算：ShedLock 保证多副本只跑一次；分页批量（design 要求 ≤2h）。
- 所有补偿/扫描任务必须可重入。

## 6. 本地验收环境

```bash
deploy/start-all.sh        # 中间件（compose）
deploy/local/start-apps.sh # 8 个应用进程
deploy/loadtest/seed.sh    # 压测数据
docker run --rm --network host -i grafana/k6 run - < deploy/loadtest/k6-order.js
deploy/loadtest/chaos.sh   # 故障演练
```

RocketMQ 本地排障（gRPC proxy 通告地址、换网络后更新 brokerIP1、Topic/Group 预建）见
`deploy/rocketmq/README.md`；消费者启动报 `PushConsumerImpl FAILED` 先查该文档。
