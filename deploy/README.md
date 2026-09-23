# 电商交易系统 V2.0 · 部署与验收手册

## 1. 架构与高可用

见 [ARCHITECTURE.md](ARCHITECTURE.md)。简述：

- 网关无状态多副本 + Nacos 服务发现负载均衡，任一业务实例宕机自动剔除。
- 七个业务服务无状态、可水平扩容；订单/支付峰值通过 HPA 扩到 6 副本。
- 库存、余额、状态机推进使用 Redisson 分布式锁 + DB 乐观锁双保险，防超卖防并发。
- 支付回调、清算登记等跨域操作全部走 RocketMQ：消费幂等（eventId/业务单号）、失败自动重试、死信兜底；生产 Broker 多副本。
- 超时类业务（支付超时、自动收货、售后审核超时）= MQ 延时消息 + ShedLock 数据库扫描双保险，单节点宕机不丢任务。
- 定时任务 ShedLock 选主，多实例只执行一次，宕机自动漂移。
- 支付与订单最终一致：回调幂等 + 本地对账补偿任务（T+1 渠道对账，长短款差错工单）。

## 2. 目录

```
shop-common        基础类（Result/异常/金额/MQ常量/事件基类）
shop-framework     框架（鉴权/Feign/锁/幂等/MP/MQ封装/分布式ID/定时选主）
shop-api           跨域 Feign 契约 + DTO + 事件（见 API_CONTRACTS.md）
shop-gateway       网关（路由/JWT/身份头清洗）
shop-user-service       用户/账户/积分/成长值/地址
shop-product-service    SPU/SKU/分类/库存/评价
shop-marketing-service  促销/优惠券/秒杀/拼团/预售/优惠计算引擎
shop-order-service      购物车/订单/状态机/超时/发票
shop-pay-service        支付单/渠道适配/回调/退款/T+1 对账
shop-settlement-service 清算/分账/结算周期/提现/保证金
shop-aftersale-service  仅退款/退货退款/换货/补发/价保/平台介入/运费险
shop-e2e           跨域场景化测试
sql/<domain>       各域 DDL
deploy/docker-compose.yml  一体化中间件
deploy/kubernetes  生产工作负载
```

## 3. 本地一键启动（功能验收）

前置：JDK17、Maven 3.9+、Docker Desktop ≥4G 内存。

```bash
# 1) 启动基础设施（MySQL/Redis/Nacos/RocketMQ/Sentinel/Prometheus/Grafana）
cd deploy && docker compose up -d

# 2) 构建全部服务（首次会下载依赖）
cd .. && mvn clean package -DskipTests

# 3) 启动 7 个服务 + 网关（各开一个终端，或用 deploy/start-all.sh）
deploy/start-all.sh

# 4) 验收
#    网关:        http://localhost:8080
#    各服务文档:  http://localhost:8081/swagger-ui.html ... 8087
#    Nacos:       http://localhost:8848/nacos  (nacos/nacos)
#    RocketMQ:    http://localhost:8090
#    Sentinel:    http://localhost:8858       (sentinel/sentinel)
#    Prometheus:  http://localhost:9090
#    Grafana:     http://localhost:3000        (admin/admin)
```

测试链路：

```bash
# 注册登录（白名单）
curl -X POST localhost:8080/api/user/auth/register -d '{"username":"u1","password":"Passw0rd!","phone":"13800000001"}'
TOKEN=$(curl -s -X POST localhost:8080/api/user/auth/login -d '{"username":"u1","password":"Passw0rd!"}' | jq -r .data.token)
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/product/spus/...
```

## 4. 生产部署

```bash
kubectl create namespace shop
kubectl apply -f deploy/kubernetes/
```

中间件使用托管版（RDS/云 Redis/消息队列）或独立高可用集群：
MySQL 主从+半同步、Redis Cluster/Sentinel、RocketMQ 多 Master 多 Slave、Nacos 3 节点。

变更密钥：`shop-infra-secret` 中 mysql-password、jwt-secret。

## 5. 验收清单（对照 design.md）

| 模块 | 关键规则 | 测试位置 |
|------|----------|----------|
| 用户 | 等级区间/折扣倍率、积分获取上限/365天过期、余额赠金积分账户 | user-service 单测 |
| 商品 | 八态、四种库存与扣减、预警自动上下架、五价取最低、评价时效 | product-service 单测 |
| 营销 | 叠加顺序、互斥、分摊不差分、券生命周期、拼团/预售规则 | marketing-service 单测 |
| 订单 | 单号规则、状态机、超时、购物车 99 上限、发票 | order-service 单测 |
| 支付 | 验签幂等、七种支付方式、原路/混合退款、T+1 对账 | pay-service 单测 |
| 清算 | 分账公式、四级商户周期、提现费用、保证金、退款冲正 | settlement-service 单测 |
| 售后 | 五类售后、状态流转、超时自动、可退金额、价保、运费险 | aftersale-service 单测 |
| 全链路 | 下单→支付→发货→收货→清算→售后→退款冲正 | shop-e2e |
