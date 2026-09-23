# RocketMQ 本地部署说明（docker compose）

## 端口与通告地址（必读）

容器 `shop-rmq-broker` 以 `mqbroker --enable-proxy`（local 模式）同时运行 broker 与 gRPC proxy。

- gRPC proxy：容器内监听 **18081**（见 `rmq-proxy.json` 的 `grpcServerPort`），宿主映射 `18081:18081`。
  历史上映射为 18081→8081 是为避让 shop-user-service 的 8081 端口；现统一为直通 18081。
- broker remoting：10911 / 10909 直通。
- **broker 通告 IP `brokerIP1`**：local 模式下 proxy 下发给客户端的路由地址恒定为
  `brokerIP1:grpcServerPort`，与请求来源无关，因此该地址必须被三种客户端同时可达：
  1. 宿主机上的 Java 进程（本地开发形态）
  2. 同一 docker 网络内的兄弟容器
  3. kind(K8s) Pod（经 host.docker.internal/宿主 IP）

  满足条件的是**宿主机 LAN IP**（本机为 `100.81.23.64`），由 `broker.conf.tpl`
  的 `@BROKER_IP@` 占位符在容器启动时替换，取值来自 compose 的 `SHOP_BROKER_IP`
  环境变量（默认 100.81.23.64）。**换网络/Wi-Fi/公司后 LAN IP 变化时必须同步更新**：

  ```bash
  # 查当前 LAN IP
  ipconfig getifaddr en0
  # 用新 IP 重建 broker
  SHOP_BROKER_IP=<新IP> docker compose up -d rmq-broker
  # kind 覆盖层同步改 deploy/kubernetes/kind/00-kind-infra.yaml 的 ROCKETMQ_ENDPOINTS
  ```

- `rmq-proxy.json` 是 proxy 专属配置（`broker.conf` 的同名键不会注入 ProxyConfig），
  通过 compose 只读挂载到 `/home/rocketmq/rocketmq-5.3.1/conf/rmq-proxy.json`。

## Topic / Group 预建

RocketMQ 5.x gRPC PushConsumer **不会**自动创建 Topic 与订阅组（即使 broker 开了
autoCreate），缺失时消费者启动报 `PushConsumerImpl FAILED`，应用上下文退出。
broker/namesrv 首次启动后执行：

```bash
./deploy/rocketmq/create-topics.sh
```

Topic/Group 元数据持久化在 Docker **命名卷 `shop-rmq-data`**（容器内
`/home/rocketmq/store`），重建容器不丢失；删除该卷后需重跑该脚本。

> **R4-19：store 禁止 bind 挂载宿主目录。** broker 写门（源码硬上限 90%、85% 迟滞，
> 见 RUNBOOK §4.1）按 store 所在文件系统采样：bind 挂载时采样的是 Mac 宿主盘
> （virtiofs0，460Gi、可用常年仅 ~45Gi），开发机自身磁盘用量达 90% 即让 broker
> 拒写（50001），与 MQ 负载无关且无法靠回收 MQ 数据解除。命名卷落在 Docker VM
> overlay 盘（~59G、实测 ~34%），彻底脱离宿主盘水位。旧 bind 目录
> `deploy/rocketmq/data`（如系历史版本残留）保留不删、不再挂载。

## 验证

```bash
# 宿主进程视角（需要 rocketmq-client-java jar）
java -cp ... MqTest8 127.0.0.1:18081      # 期望 CONSUMER STARTED OK
# 容器视角
docker run --rm --network shop-infra_default -v ... apache/rocketmq:5.3.1 \
  java -cp ... MqTest8 host.docker.internal:18081
```

## 生产形态差异

- proxy 独立部署（container 模式，至少 2 副本），broker 主从 + DLedger；
- `autoCreateTopicEnable=false`、`autoCreateSubscriptionGroup=false`，Topic/Group 走运维流程；
- 启用 RocketMQ ACL（plain_acl.yml），生产者/消费者配 AK/SK；
- 通告地址为 broker/proxy 的 Service 名或 LB，不依赖宿主机 IP。
