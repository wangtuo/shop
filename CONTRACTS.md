# 架构契约（所有子代理必须严格遵守）

> 版本 V2.0 · Java 17 · Spring Boot 3.2 · Spring Cloud 2023 · Spring Cloud Alibaba 2023
> 目标：生产级、高可用、功能完备。任何与本文件冲突的写法都视为缺陷。

## 1. 总体架构

| 层 | 选型 | 高可用手段 |
|----|------|-----------|
| 接入层 | Spring Cloud Gateway（无状态、可多副本） | Nacos 服务发现 + LB，JWT 统一鉴权，清洗外部身份头 |
| 业务服务 | user / product / marketing / order / pay / settlement / aftersale，共 7 个无状态 Spring Boot 服务 | 多实例水平扩容；Sentinel 熔断限流；Feign 失败兜底 |
| 注册/配置 | Nacos（standalone 用于本地；生产集群 ≥3 节点） | 节点故障自动剔除 |
| 数据层 | MySQL 8（每服务独立 schema，不允许跨库 JOIN） | 连接池 Druid；乐观锁 version；逻辑删除 deleted |
| 缓存/锁 | Redis 7 + Redisson | 分布式锁、幂等标记、热点库存、序列、登录态；生产哨兵/集群 |
| 消息 | RocketMQ 5.x（Proxy 协议，client-java 5.0.3） | 支付后扇出全部走 MQ；消费幂等 + 失败重试 + 死信；延时消息做超时 |
| 定时任务 | Spring Scheduling + ShedLock（Redis） | 多实例只跑一个，宕机自动漂移 |
| 可观测 | Actuator + Prometheus + 全链路状态日志 | health/prometheus 端点 |

服务划分（schema = Redis DB = 服务名）：

| 服务 | 端口 | schema | Redis DB |
|------|------|--------|----------|
| shop-gateway | 8080 | - | - |
| shop-user-service | 8081 | shop_user | 0 |
| shop-product-service | 8082 | shop_product | 1 |
| shop-marketing-service | 8083 | shop_marketing | 2 |
| shop-order-service | 8084 | shop_order | 3 |
| shop-pay-service | 8085 | shop_pay | 4 |
| shop-settlement-service | 8086 | shop_settlement | 5 |
| shop-aftersale-service | 8087 | shop_aftersale | 6 |

## 2. 工程约定（强制）

1. 包名：
   - 服务代码 `com.shop.<domain>.<feature>.{controller,service,service.impl,entity,mapper,dto,enums,mq,job,config}`
   - 对外契约 `com.shop.api.<domain>.{client,dto,event}`，**api 各域包之间禁止互相 import**（DTO 字段自包含）。
2. 金额：库表、DTO、计算一律 `Long`（单位：**分**），禁止 double/float。分摊一律用 `MoneyUtils.allocate`（最大余数法，合计不差 1 分）。
3. 实体继承 `com.shop.common.model.BaseEntity`（Long 雪花 id / create_time / update_time / deleted）。
   - 表名 `t_<域>_<对象>`，列下划线；必有 `id BIGINT`、`create_time DATETIME`、`update_time DATETIME`、`deleted TINYINT DEFAULT 0`，并发敏感表加 `version INT DEFAULT 0`。
   - Mapper 加 `@org.apache.ibatis.annotations.Mapper`，继承 `BaseMapper<T>`；复杂 SQL 才写 XML（放 resources/mapper）。
4. Controller 返回 `Result<T>` / `PageResult<T>`；请求体 DTO 用 jakarta validation 注解；路径用复数名词（`/orders/{orderNo}`）。
5. 可预期失败一律 `throw new BizException(ErrorCode.X, "具体说明")`，禁止吞异常、禁止 catch Exception 后返回成功。
6. 写接口必须考虑幂等：提交/回调类加 `@Idempotent(prefix, key="#...")`；MQ 消费以 eventId / 业务单号去重（落消费流水表或唯一索引）。
7. 服务间同步调用只用 Feign（`com.shop.api.<domain>.client.*Client`）；跨域状态变更只发 MQ，不允许直接改他库。
8. MQ：`MqProducer.send/sendAsync/sendDelay`，Topic 只用 `MqTopics` 常量；消费者实现 `MqListener<T>`（group 命名 `cg_<域>_<动作>`）。
9. 登录身份：`UserContext.getUserId()`；商家接口读 `UserContext.getMerchantIdOrNull()` 并鉴权数据归属。
10. 所有定时任务方法加 `@Scheduled` + `@SchedulerLock(name="...", lockAtMostFor="...", lockAtLeastFor="...")`。
11. 每个功能点必须同时交付：DDL（`sql/<domain>/V2__xxx.sql`，建库 `CREATE DATABASE IF NOT EXISTS` + `USE`）、实体、Mapper、Service（接口+impl）、Controller、单元测试（JUnit5，纯单元，禁止依赖外部中间件）。
12. 不允许改父 POM、framework、common、其他 agent 负责的目录。

## 3. 跨域同步契约（Feign Client，Wave-1 必须逐字实现）

所有 client 注解 `@FeignClient(name="shop-xxx-service", path="/inner/xxx")`，**服务端必须在对应 controller 的同路径实现内部接口**（由 Wave-2 对应 agent 完成），方法签名必须与这里完全一致：

```java
// com.shop.api.user.client.UserClient
Result<UserDTO> getUser(@RequestParam("userId") Long userId);
Result<UserLevelDTO> getLevel(@RequestParam("userId") Long userId);
Result<AddressDTO> getAddress(@RequestParam("addressId") Long addressId);
Result<Void> lockPoints(@RequestBody PointsLockCommand cmd);      // 下单预扣（冻结）
Result<Void> deductPoints(@RequestBody PointsDeductCommand cmd);  // 支付成功扣冻结
Result<Void> releasePoints(@RequestBody PointsReleaseCommand cmd);// 取消释放
Result<Void> refundPoints(@RequestBody PointsRefundCommand cmd);  // 退款按比例退回
Result<Void> grantPoints(@RequestBody GrantPointsCommand cmd);    // 消费/评价/签到等发放
Result<Void> addGrowth(@RequestBody GrowthCommand cmd);
Result<Void> debitBalance(@RequestBody AmountCommand cmd);        // 余额支付扣款
Result<Void> creditBalance(@RequestBody AmountCommand cmd);       // 退款入余额
Result<Void> debitGift(@RequestBody AmountCommand cmd);           // 赠金扣款

// com.shop.api.product.client.ProductClient
Result<SkuDTO> getSku(@RequestParam("skuId") Long skuId);
Result<List<SkuDTO>> listSkus(@RequestBody List<Long> skuIds);
Result<Void> lockStock(@RequestBody StockLockCommand cmd);        // TCC-try：可售→锁定
Result<Void> confirmDeduct(@RequestBody StockDeductCommand cmd);  // TCC-confirm：锁定→占用
Result<Void> releaseStock(@RequestBody StockReleaseCommand cmd); // TCC-cancel：锁定→可售
Result<Void> returnStock(@RequestBody StockReturnCommand cmd);    // 售后回库
Result<Boolean> saleable(@RequestParam("skuId") Long skuId, @RequestParam("qty") Integer qty);

// com.shop.api.marketing.client.MarketingClient
Result<PriceCalcResult> calculate(@RequestBody PriceCalcCommand cmd);          // 确认订单页/下单前试算
Result<Void> lockPromotion(@RequestBody PromotionLockCommand cmd);              // 预核销券/锁秒杀库存
Result<Void> confirmPromotion(@RequestBody PromotionConfirmCommand cmd);        // 支付成功核销
Result<Void> releasePromotion(@RequestBody PromotionReleaseCommand cmd);        // 取消释放

// com.shop.api.order.client.OrderClient
Result<OrderDTO> getByOrderNo(@RequestParam("orderNo") String orderNo);

// com.shop.api.pay.client.PayClient
Result<PaymentDTO> createPayment(@RequestBody CreatePaymentCommand cmd);
Result<PaymentDTO> getByPayNo(@RequestParam("payNo") String payNo);
Result<RefundDTO> refund(@RequestBody CreateRefundCommand cmd);
```

Settlement / Aftersale 不提供 Feign：对外完全事件驱动（见 §5）。

## 4. 统一状态码

- 用户类型：`-1 游客 / 0 普通 / 1 商户 / 2 平台运营`
- 会员等级：`L0(0-99) L1(100-999) L2(1000-4999) L3(5000-19999) L4(20000+)`；等级折扣 1.0/0.98/0.95/0.92/0.90；积分倍率 1/1.1/1.5/2/3
- 商品状态：`0 草稿 1 待审核 2 审核拒绝 3 已上架 4 已下架 5 售罄 6 违规下架 7 已删除`
- 订单类型：`1 普通 2 秒杀 3 拼团 4 预售 5 换货`
- 订单状态：`10 待付款 20 待发货 30 待收货 40 已完成 50 已取消 60 退款中 61 退货退款中 62 换货中 70 已关闭`
- 支付单：`10 待支付 20 支付中 30 成功 40 失败 50 已关闭 60 退款中 70 已退款`
- 退款单：`10 待退款 20 退款中 30 成功 40 失败 50 已冲正`
- 售后类型：`1 仅退款 2 退货退款 3 换货 4 补发货 5 价保`
- 售后状态：`10 待商家审核 20 待买家退货 30 商家收货中 40 退款中 41 待换货发货 42 换货已发货 43 换货待收货 50 已完成 55 已拒绝(待用户处理) 80 平台介入中 90 已撤销`
- 优惠券：`0 未使用 1 已使用 2 已过期 3 已作废`；券类型 `1 满减券 2 折扣券 3 无门槛券 4 免邮券 5 品类券 6 店铺券`
- 营销活动类型：`1 满减 2 满折 3 满赠 4 第N件优惠 5 限时折扣 10 秒杀 11 拼团 12 预售 13 砍价 14 抽奖`
- 库存锁定状态：`0 锁定中 1 已扣减 2 已释放 3 已回库`；库存类型 `1 普通 2 预售 3 秒杀 4 拼团`
- 清算阶段：`10 待清算(支付成功) 20 待结算(已收货) 30 已结算(可提现) 40 已冲正(退款)`
- 售后审核超时：仅退款/退货退款/换货 2 天自动同意；商家确认收货 3 天；换货发货 5 天转退款。

## 5. 事件契约（Topic → 事件类，均在 com.shop.api.<domain>.event）

| Topic（MqTopics） | 生产者 | 事件类（字段必须含） | 消费者 |
|---|---|---|---|
| ORDER_CREATED | order | OrderCreatedEvent：orderNo,userId,orderType,status,items[{skuId,spuId,merchantId,shopId,qty,salePriceFen,productTotalFen,seckillActivityId,groupNo,presaleActivityId}],usedPointsFen,userCouponId,freightFen,expirePaySeconds | product（锁库存）、marketing（锁券/秒杀确认） |
| ORDER_CANCELLED | order | OrderCancelledEvent：orderNo,userId,items,usedPointsFen,userCouponId,cancelType(1用户 2超时),seckillActivityId | product 释放、marketing 释放、user 释放冻结积分 |
| ORDER_PAID | pay | PaymentSucceededEvent：payNo,orderNo,userId,payMethod,amountFen,paidTime | order→待发货；product 锁定转占用；marketing 核销券/秒杀扣减；user 扣冻结积分+发放积分成长值；settlement 登记待清算 |
| ORDER_SHIPPED | order | OrderShippedEvent：orderNo,userId,logisticsNo,items,autoConfirmDeadline | - |
| ORDER_CONFIRMED | order | OrderConfirmedEvent：orderNo,userId,items,merchantId,productPayFen,freightFen | settlement 生成待结算 |
| ORDER_COMPLETED | order | OrderCompletedEvent：orderNo,userId,merchantId | settlement 结算完成转可提现（B 级商户） |
| REFUND_SUCCESS | pay | RefundSucceededEvent：refundNo,orderNo,aftersaleNo,userId,amountFen,payMethod,refundType(1全额 2部分) | settlement 清算冲正；user 退积分/余额到账；order 更新售后结果 |
| AFTERSALE_CHANGED | aftersale | AftersaleChangedEvent：aftersaleNo,orderNo,userId,merchantId,type,oldStatus,newStatus,refundFen | order 更新明细售后状态；product（退货入库在买家责任事件） |
| STOCK_WARNING | product | StockWarningEvent：skuId,available,threshold | 落预警表 + 日志告警 |
| CLEARING_REVERSE | aftersale/pay 不直接发，由 settlement 消费 REFUND_SUCCESS 内部冲正 | - | - |
| SECKILL_EVENT / GROUPBUY_EVENT / PRESALE_EVENT / POINTS_CHANGED | marketing/user | 各状态事件 | 域内状态推进 + 对账单据 |

关键链路：
- 下单（同步）：订单校验 → `ProductClient.lockStock`(TCC-try) → `MarketingClient.lockPromotion`(券预核销/秒杀库存锁定) → `UserClient.lockPoints`(有积分时) → 落订单(待付款) → 发 ORDER_CREATED + 延时消息（支付超时）。任一步失败按逆序补偿。
- 支付成功（全异步扇出）：pay 只做验签/幂等/落单，发 ORDER_PAID；各域自行幂等消费。
- 超时关单：RocketMQ 延时消息 + DB 扫描补偿任务（双保险，ShedLock 选主）。

## 6. 订单号（order 域实现）

`YYMMDD + 业务类型2位 + 用户ID后4位 + 6位日内序列`（共 18 位），序列用 Redis `INCR shop:order:seq:{yyMMdd}:{bizType}`，TTL 48h；超 999999 时雪花兜底。支付单号 `P`+17位，退款单 `R`+17位，售后单 `AS`+yyyyMMdd+10位序列，清算单 `CL`，结算单 `ST`，提现单 `WD`。

## 7. 验收标准（每个 agent 自测）

1. `mvn -pl <你的模块> -am compile` 通过；
2. 核心业务规则必须有单测且通过（计算类/状态机类覆盖率 ≥ 70%）：用例要覆盖 design.md 中每一条规则（等级、积分、叠加互斥、分摊、分账公式、退款金额、超时等）；
3. 每个对外 HTTP 接口具备：参数校验、鉴权、幂等/防重、归属鉴权（商户只能看本店单）；
4. SQL 可在 MySQL 8 直接执行，字段注释中文写明含义；
5. 不写 TODO、不留空方法；异常路径有明确错误码。
