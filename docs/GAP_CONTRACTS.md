# 阻断性缺口实现契约（B1–B13）— 8 服务并行实现唯一工作依据

> 版本：V1.0 · 日期：2026-09-17 · 性质：只读分析产物（本文件之外未修改任何源码）
> 依据：`design.md` V2.0、`AUDIT_FEATURES.md` §10.2/§10.3、`CONTRACTS.md`、`API_CONTRACTS.md`、现有源码（证据精确到 类:行）
> 读者：WP1–WP8 八个按服务分工的实现代理。**本文件与既有代码冲突时，以本文件为准；本文件未规定的，遵守 `CONTRACTS.md`。**

---

## 0. 使用规则（先读）

1. 实现顺序固定为 **WP0（shop-api + shop-common 契约包）先合入 → WP1–WP8 并行**。WP0 不交付业务功能，只交付编译期契约（常量/事件/DTO/枚举/Feign 方法签名）。WP1–WP8 启动前 WP0 必须已合入主干，禁止任何服务代理自行改动 shop-api/shop-common。
2. 每个 WP 只能修改本文件 §6「文件独占矩阵」中归属自己的文件。矩阵中标记为「扩展点接入」的文件，只允许按指定方式扩展，不得改动既有方法签名。
3. 金额一律 `Long`（分）；事件一律 `extends com.shop.common.model.BaseEvent`（eventId/occurredAt/bizNo）；业务发消息一律在事务内走 `com.shop.framework.outbox.OutboxPublisher.publish/publishDelay`（事务外 fail-fast，见 `OutboxPublisher.java:40-43`）；消费者实现 `com.shop.framework.mq.MqListener<T>`，group 命名 `cg_<域>_<动作>`，幂等一律落本域 `t_<域>_mq_consume`（INSERT IGNORE + 业务条件更新双保险，范式见 `shop-marketing-service .../mq/MqConsumeTemplate.java:20-27`）。
4. 禁止跨库 JOIN；跨域状态变更只发 MQ；同步查询/写只允许 Feign（`com.shop.api.<domain>.client`）。
5. 延时消息不使用 RocketMQ delayLevel，统一 outbox `deliver_at`（`OutboxPublisher.publishDelay`），新增延时 tag 不需要改 framework。
6. DDL 文件归属 `sql/<domain>/V<n>__<biz>.sql`，必须 `CREATE DATABASE IF NOT EXISTS ... USE`（新建文件）或 `USE shop_<domain>;`（迁移文件）头，ALTER 全部用 `information_schema` 存储过程包裹保证可重复执行（范式见 `sql/settlement/V4__settlement.sql`、`sql/pay/V4__pay_multi_attempt.sql`）。
7. 每个 WP 交付物 = DDL + 实体/Mapper + Service(接口+impl) + Controller（如涉外部端点）+ MQ 生产/消费 + 单测；计算/状态机类覆盖率 ≥70%；不写 TODO、不留空方法（`CONTRACTS.md` §7）。

---

## 1. 关键架构决定（争议项最终口径，代理不得再自行选择）

| # | 争议点 | 最终决定 | 理由（证据） |
|---|---|---|---|
| D1 | GROUPBUY_EVENT 成团/失败消费者归属 | **订单侧消费 tag 3/4（新 group `cg_order_groupbuy`）；营销侧仍是团状态唯一事实源，且 SUCCESS 事件改为逐成员各发一条** | 退款必须经 `PayClient.refund`（order 已依赖 pay，`OrderOperateServiceImpl.java:89` 已有调用先例；marketing 当前不依赖 pay）；发货拦截、支付截止时间都在 order 库（`t_order_order`）。营销侧 `t_groupbuy_member` 持有全团 orderNo 清单（`GroupbuyMember` 实体），由它逐成员发事件，order 不回查营销库，符合 §0.4。 |
| D2 | 拼团已支付订单在成团前的状态 | **不新增订单状态码。orderType=3 支付后仍为 20 待发货，但新增 `t_order_order.group_success` 拦截位；商家发货接口校验：拼团单必须 group_success=1，否则报 CONFLICT「拼团未成团，暂不能发货」** | 保留九态状态机（`OrderStatuses` 10/20/30/40/50/60/61/62/70），避免 E2E 59 用例与 885 单测大面积改写；发货拦截点唯一且在商户操作前。 |
| D3 | 成团后 30 分钟支付续期 | **order 消费 tag 3，对仍为 10 待付款 的成员单：条件更新 expire_time=now+1800s，并向既有 `ORDER_PAY_TIMEOUT` topic 再发一条 1800s 延时消息（payload 复用 `OrderDelayMessage`）。`PayTimeoutPolicy` 矩阵不改（24h 基础窗口保留在 :34），续期只由事件驱动 + 扫描 Job 兜底** | 续期是事件语义不是静态矩阵；`PayTimeoutScanJob` 按 expire_time 扫描自动获得新截止时间。 |
| D4 | 拼团失败退款路径 | **tag 4：order 逐单处理——已支付(20) → 调 `PayClient.refund` 全额退（新退款来源 RefundSources.GROUPBUY_FAIL=4），退款成功后经既有 REFUND_SUCCESS 链路回分账/退积分（拼团不可用券与积分，实际为空操作），并由 order 主动调 `ProductClient.returnStock(reason=GROUP_FAIL)` 回补占用仓；未支付(10) → 走 `doCancel(CancelTypes.TIMEOUT)` 既有释放链** | pay 退款成功的四个既有消费者（order/settlement/user/aftersale）中，product 不消费 REFUND_SUCCESS（见 MQ 全图），拼团失败又不产生售后单，故库存回补必须由 order 显式发起。 |
| D5 | 平台管理员「代建直发」 | **保留。商户(userType=1)新建促销/券模板/活动强制走 0草稿→4待审核→(通过0/驳回5)→1上架；平台管理员(userType=2)既有 save + changeStatus 直建直上架路径保留为平台代运营能力，但新增 `creator_type` 列留痕（1商户/2平台）** | 平台代运营是合理运营路径；审核分离针对的是商户提交。新增状态码 4/5 不复用既有 0/1/2/3，PriceEngine 对模板 status=1 的既有判断零影响。 |
| D6 | 秒杀预占回补统一入口 | **营销侧新增 `cg_marketing_seckill_sync` 域内消费者（tag 1/2/3 全订阅，按 t_seckill_order 状态 CAS 幂等），作为事件统一入口；同步 Feign 释放快路径保留；对账 Job 从告警升级为修正 + status=0 滞留扫描（经 OrderClient 查单后回补）** | Redis 预占是营销域资源（`mk:seckill:stock:*`，`SeckillStockClient.java:31`），消费者必须在营销侧；order 无法直接操作营销 Redis。 |
| D7 | 防「普通价买秒杀品」 | **营销侧新增活动绑定查询 Feign（按 skuId 批量查进行中的秒杀/拼团/预售绑定）；order 下单校验：绑定命中的 SKU，orderType 必须等于绑定类型且活动 ID 必须一致，否则拒绝；非活动品（无绑定）允许 activityId 为空走普通单** | 活动配置（`t_activity`+`t_seckill_sku`）是活动标记唯一权威源；商品侧 `ProductSku.seckillPriceFen` 只是冗余价格字段不能作准。 |
| D8 | 预售两单模型 | **维持「定金单 + 尾款单」两个独立 18 位订单（04 业务码），尾款单新增字段 `depositOrderNo` 关联定金单；定金单 payFen=定金总额；尾款单 payFen=原货额−定金−膨胀额（券仅作用于尾款单）；库存：定金支付后扣预售栏，尾款支付后 available→occupied，发货出账** | E2E `MarketingE2ETest.java:124-145` 已按两单试算；PresaleOrder 以定金 orderNo 落库（`PresaleService.java:43-79`）。 |
| D9 | 预售分账时点 | **清算只在尾款支付单（presaleFinalStage=1）触发一次分账，分账口径=整单经济（货额+运费−优惠，买家总付=定金+尾款）；定金支付单清算侧只登记不入账（跳过既有 split/posting）** | 避免定金、尾款双次分账；`ClearingService` 已通过 OrderClient 回查订单（G4-1 现状），尾款单 DTO 带定金/膨胀字段即可。 |
| D10 | 运费模板归属 | **product 侧维护模板（店铺维度，平台兜底默认模板）；order 下单时服务端调 product Feign 计算，**完全忽略**客户端 `freightFen`（字段保留接收但一律以服务端结果覆盖）** | product 持有 SKU 重量/体积（`ProductSku.weightGram:43/volumeCc:46`、`SkuDTO:89/92`）与商品/店铺归属；现状客户端运费可篡改（`OrderCreateServiceImpl.java:241` 直接上送）。 |
| D11 | aftersale 开 Feign 例外 | **新增 `AftersaleClient`（首个售后域 Feign），仅一个只读纠纷存在性查询，供保证金 90 天判定** | 旧契约「settlement/aftersale 无 Feign」的前提是事件驱动够用；B10 明确要求 90 天无纠纷数据源（现状 `DepositService.java:147-149` 自述无数据源，G4-3 挂账 Wave3，本轮关闭）。只读、单方法，不破坏事件写隔离。 |
| D12 | 保证金缴费/退还资金通道 | **缴费：pay 侧新增「商户充值」支付场景（bizScene=2），复用既有支付单+渠道下单+回调链路，成功后发新事件 `MERCHANT_DEPOSIT_PAID`（不发 ORDER_PAID，避免订单扇出误触发）；退款：settlement 侧新增统一打款抽象 `RemitChannelClient`（Mock 实现先行，与 B8 真实渠道改造同 SPI），保证金退款与提现打款共用** | pay 无 order-less 支付概念（`CreatePaymentCommand` 强 orderNo），且 ORDER_PAID 有 5 个消费者；settlement 无任何打款通道（`WithdrawService.remitOne:179-198` 仅状态翻转）。 |
| D13 | 网关限流技术栈 | **gateway 不引 shop-framework（servlet/AOP 污染 webflux）；直接加 `redisson-spring-boot-starter`（版本已在根 pom 管理 3.27.2），自写 webflux GlobalFilter + Lua ZSET 滑动窗口（从 `RateLimiter.java:23-37` 移植）；同时补 micrometer-registry-prometheus** | gateway pom 当前仅依赖 shop-common（无 redis、无 prometheus registry）；framework 是 servlet 栈不能在 webflux 生效。 |
| D14 | 规格主数据历史兼容 | **新增 shop.product.spec.master-required 配置，默认 false：false 时自由文本 specText 放行（历史兼容），仅对「主数据中已存在的规格名」校验其规格值合法；true 时全量强制。不做历史数据回填** | 现存 SKU 全部自由文本（`SkuSaveRequest.java:32`、`SpuServiceImpl.java:185`），强校验会阻断现网数据。 |
| D15 | 发货扣减复用事件 | **不新增事件：product 新增 `ORDER_SHIPPED` 消费者 `cg_product_order_shipped`，占用仓真实出账（occupied−=qty）。售后回库 SQL 同步改为「目标仓 += qty，occupied 按 LEAST/GREATEST 兜底」，兼容发货前/后两种回库** | order 发货已发 `ORDER_SHIPPED`（`OrderPersister.java:97`），现仅 aftersale 消费。 |
| D16 | 成长值/积分触发事件 | **新增一个行为 topic：product 发评价事件（withImage=true 时 user 侧同时结 COMMENT 与 SHOW_ORDER）；order 发分享事件；user 侧消费。GrowthScene 不加 SHARE（design 2.1.3 分享只给积分不给成长值），PointsScene.SHARE=4 已存在直接复用** | design 2.1.3 与 2.2.2 口径不同；评论归属 product（`CommentServiceImpl`），分享是订单动作。 |

---

## 2. 新增 / 变更 MQ 事件总表

Topic 常量统一加在 `com.shop.common.constant.MqTopics`（WP0 唯一改动入口）。Tag 命名沿用现状：业务事件用字符串小写实义 tag（如 pay 的 `"paid"/"refund"`、aftersale 的 `"audit"/"insurance"`），营销玩法事件沿用数字操作码（现状 `GroupbuyService.java:190` / `SeckillService.java:228`）。

| 状态 | Topic 常量（字面值） | Tag | Payload（shop-api FQN） | 生产者（类:行） | 消费者 group → 行为 | 幂等键 | 重试/死信 |
|---|---|---|---|---|---|---|---|
| 新增 | `USER_REGISTERED`=`shop_user_registered` | `null`（订阅 *） | `com.shop.api.user.event.UserRegisteredEvent` | user `AuthServiceImpl.register`（:53-92，同事务 outbox） | marketing `cg_marketing_user_registered` → 新人礼包逐张发券（B6） | `bizNo=userId`（t_marketing_mq_consume） | 模板缺失优雅 ACK；其余异常 broker 重试 16 次→%DLQ% |
| 新增 | `USER_BEHAVIOR_EVENT`=`shop_user_behavior_event` | `"comment"` / `"share"` | `com.shop.api.user.event.UserBehaviorEvent` | product `CommentServiceImpl.create`（tag=comment）；order `ShareController`（tag=share） | user `cg_user_behavior` → 成长值+积分（B6） | comment：`CMT:{commentId}`；share：`SHR:{clientToken}` | BizException 终态码 ACK，其余重试→DLQ |
| 新增 | `MERCHANT_DEPOSIT_PAID`=`shop_merchant_deposit_paid` | `null` | `com.shop.api.pay.event.MerchantDepositPaidEvent` | pay `PaymentServiceImpl` 商户充值回调分支（B10） | settlement `cg_sett_deposit_paid` → 保证金入账（替换 `DepositService.doPayDeposit:79-98` mock 直加） | `payNo`（settlement 既有 mq_consume） | 同标准 |
| 变更 | `GROUPBUY_EVENT`=`shop_groupbuy_event`（既有，`MqTopics.java:49`） | `"1"/"2"/"3"/"4"` 既有 | `GroupbuyEvent`（字段不变） | marketing `GroupbuyService`：**tag 3 改为团成功时对每个成员各发一条**（现状 :101-102 只给最后一人发）；tag 1/2/4 不变（tag 4 失败已逐成员，`expireGroups:140-163`） | **新增 order `cg_order_groupbuy`**：tag3→发货位+未支付单 30min 续期；tag4→退款/取消（B1/D1/D3/D4） | order 侧 t_order_mq_consume（eventId）+ 订单状态 CAS | 退款 Feign 失败按非终态异常重试→DLQ（Job 兜底见 B1） |
| 变更 | `SECKILL_EVENT`=`shop_seckill_event`（:47） | `"1"/"2"/"3"` 既有 | `SeckillEvent`（字段不变） | marketing `SeckillService:222-229`（不变） | **新增 marketing 域内 `cg_marketing_seckill_sync`**：tag1/2/3 以 t_seckill_order CAS 幂等重放 Redis/DB 状态（B4/D6） | eventId + seckill orderNo 状态 CAS | 同标准；Redis 缺失走既有 fallback 初始化 |
| 复用 | `ORDER_SHIPPED`=`shop_order_shipped`（:19，payload **加 2 字段**，WP0） | null | `OrderShippedEvent`（+hasFreightInsurance, +insurancePremiumFen） | order `OrderPersister.ship:97`（不变） | aftersale `cg_aftersale_shipped`（既有，改读保险位）；**新增 product `cg_product_order_shipped`** → occupied 出账（B13/D15） | product t_product_mq_consume + stock_log UK | 同标准 |
| 变更 | `ORDER_CREATED`=`shop_order_created`（:13，payload **加 3 字段**，WP0） | null | `OrderCreatedEvent`（+presaleFinalStage, +hasFreightInsurance, +insurancePremiumFen） | order（不变） | product 既有 group：预售按 stage 分流锁/不锁（B5）；marketing 既有 group：尾款单关联校验（B5） | 既有 UK | 同标准 |
| 变更 | `ORDER_PAID`=`shop_order_paid`（:17） | `"paid"` 既有 | `PaymentSucceededEvent`（**字段不变**） | pay：bizScene=1 订单单照发；**bizScene=2 充值单禁止发本 topic**，改发 MERCHANT_DEPOSIT_PAID（D12） | 既有 5 group 全不受影响（收不到充值单） | — | — |
| 复用 | `ORDER_PAY_TIMEOUT`=`shop_order_pay_timeout`（:25） | null | `OrderDelayMessage`（不变） | order：成团续期时 `publishDelay(...,1800)` 再发一条（B1） | `cg_order_pay_timeout` 既有，天然幂等（取消后再到直接 no-op，见 `PayTimeoutListener`） | 既有 mq_consume | — |
| 不变 | PRESALE_EVENT（:51） tag `"3"` | 既有 | `PresaleEvent` | marketing `PresaleService:75-78,135` | `cg_marketing_presale_cancel` 既有 | 既有 | 尾款单自身 3 天超时由 order 侧 ORDER_PAY_TIMEOUT/扫描独立取消，两域各自取消各自单据 |

> 不新增「砍价/抽奖」「审核流」「运费模板」事件：这些链路均为同步接口或域内状态，无跨域状态变更需求。保证金罚款/退款结果不新增 topic，落 `t_sett_deposit_log`/`t_sett_deposit_remit` 与管理端查询（10.3 残留项，后续可再挂 WITHDRAW_RESULT 类通知）。

---

## 3. 新增 DB 迁移文件清单

现有最大版本（已核实，仓库 SQL 根目录是 `/Users/bytedance/bits/shop/sql/`，不存在 shop-common/sql）：user V2、product V2、order V2、aftersale V2；marketing V4；pay V4；settlement V4；公共 common 到 V5（各库通用）。续号如下：

| 文件（绝对路径） | 库 | 缺口 | 要点 |
|---|---|---|---|
| `sql/marketing/V5__marketing_gaps.sql` | shop_marketing | B2/B3/B4 | ① t_promo、t_coupon、t_activity 各加 `submit_time DATETIME NULL`、`auditor_id BIGINT NULL`、`audit_time DATETIME NULL`、`audit_remark VARCHAR(200) NULL`、`creator_type TINYINT NOT NULL DEFAULT 2`；② `t_seckill_order` 删除 V3 加的 `uk_activity_user`，改建普通索引 `idx_activity_user(activity_id,user_id,deleted)`（可配 N 单，UK 与多单互斥，强制由 Service 计数）；③ 新建 `t_bargain_help`；④ `t_bargain_record` 加 `used_order_no VARCHAR(32) NULL`、`lock_expire_time DATETIME(3) NULL`，加 `idx_user_status(user_id,status)`；⑤ `t_lottery_record` 加 `prize_type TINYINT NULL`、`prize_amount BIGINT NOT NULL DEFAULT 0`、`coupon_id BIGINT NULL`、`status TINYINT NOT NULL DEFAULT 0`、`gain_time DATETIME NULL`、`daily_key VARCHAR(16) NULL`，加 `idx_user_day(user_id,daily_key)` |
| `sql/product/V3__product_gaps.sql` | shop_product | B7/B11/B13/B5 | ① t_product_sku 加 `presale_stock BIGINT NOT NULL DEFAULT 0 COMMENT '预售库存（定金支付后扣减的预售额度）'`；② t_product_spu 加 `freight_template_id BIGINT NULL`、`freight_insurance_supported TINYINT NOT NULL DEFAULT 1`；③ 新建 `t_product_category_virtual`、`t_product_spec_name`、`t_product_spec_value`、`t_product_freight_template`、`t_product_freight_template_region`、`t_product_insurance_rule`；④ `t_product_stock_log` 加 `stage TINYINT NULL COMMENT '预售阶段：0定金 1尾款（仅预售）'`（DDL 见各 B 节） |
| `sql/order/V3__order_gaps.sql` | shop_order | B1/B5/B11 | t_order_order 加：`group_success TINYINT NOT NULL DEFAULT 0`、`freight_insurance TINYINT NOT NULL DEFAULT 0`、`insurance_premium_fen BIGINT NOT NULL DEFAULT 0`、`presale_deposit_fen BIGINT NOT NULL DEFAULT 0`、`presale_inflate_fen BIGINT NOT NULL DEFAULT 0`、`deposit_order_no VARCHAR(32) NULL`、KEY `idx_deposit_order_no(deposit_order_no)` |
| `sql/pay/V5__pay_merchant_deposit.sql` | shop_pay | B10 | t_pay_order 加 `biz_scene TINYINT NOT NULL DEFAULT 1 COMMENT '1订单支付 2商户充值'`、`merchant_id BIGINT NULL`、`biz_no VARCHAR(64) NULL COMMENT 'scene=2 时为保证金充值流水号'`，加 `idx_scene_biz(biz_scene,biz_no)`；不新增支付单表（复用 t_pay_order + active_slot 多尝试机制） |
| `sql/settlement/V5__settlement_deposit.sql` | shop_settlement | B10/B9 无 DDL | 新建 `t_sett_deposit_remit`（保证金清退打款单，DDL 见 B10）；枚举型变更（FlowChangeTypes 加值、DepositLogTypes.FINE 已存在 :8）不需 DDL（change_type 为 INT 无 CHECK） |
| `sql/aftersale/V3__aftersale_insurance.sql` | shop_aftersale | B11 | t_aftersale_insurance：`claim_deadline` 改为 NULL 语义（支付生效时 NULL，进入退货退款时回填 +72h）；加 `policy_effective_time DATETIME NULL COMMENT '保单支付生效时间'`、`premium_order_no VARCHAR(32) NULL`；加 `idx_order_status(order_no,status)`（uk_order_no 已存在防重） |
| （无迁移） | shop_user | B6 | 复用 t_user_growth_flow/t_user_points_daily/t_user_mq_consume；t_mq_outbox 已随 common V3 存在（user 已是 POINTS_CHANGED 生产方，`AccountServiceImpl.publishPointsEvent`） |

所有新建表沿用：`id BIGINT` 雪花/自增、`version INT DEFAULT 0`（并发表）、create_time/update_time/deleted 三件套、utf8mb4_unicode_ci、中文 COMMENT。

---

## 4. WP0 前置契约包（shop-api + shop-common，先合入，独占者：集成代理）

WP0 只加不改语义（全部为追加字段/追加常量/追加接口方法），保证 WP1–WP8 拉到后各自可独立编译。以下清单即为 WP0 的完整验收范围。

### 4.1 shop-common（`/Users/bytedance/bits/shop/shop-common/src/main/java/com/shop/common/constant/MqTopics.java`）

```java
public static final String USER_REGISTERED = "shop_user_registered";
public static final String USER_BEHAVIOR_EVENT = "shop_user_behavior_event";
public static final String MERCHANT_DEPOSIT_PAID = "shop_merchant_deposit_paid";
```
（位置接在 REFUND_SHORTFALL :59 之后；不动既有常量。）

### 4.2 user 域（`com.shop.api.user`）

- `enums/PointsScene.java`（final 常量类，现状 1-6）追加：`public static final int LOTTERY = 7; // 积分抽奖消耗/抽奖中奖发放`
- `enums/GrowthScene.java` **不动**（无 SHARE，见 D16）。
- 新增 `event/UserRegisteredEvent extends BaseEvent`：`Long userId`、`String phoneMasked`（掩码串，仅用于礼包日志/画像，营销侧不得据此触达）、`Long registerTime`(epoch millis)。
- 新增 `event/UserBehaviorEvent extends BaseEvent`：

  | 字段 | 类型 | 说明 |
  |---|---|---|
  | userId | Long | 行为用户 |
  | type | Integer | 1 COMMENT 评价 2 SHARE 分享（晒单不独立，comment 且 withImage=true 时由 user 侧同时结 COMMENT+SHOW_ORDER） |
  | orderNo | String | 评价/分享关联订单号（分享商品时可空） |
  | skuId | Long | 评价明细（可空） |
  | withImage | Boolean | 评价是否带图（type=1 用） |
  | targetType | Integer | 分享对象类型（type=2 用）：1 订单 2 商品 |
  | targetId | String | 分享对象 ID |
  | clientToken | String | 分享防重令牌（生成 bizNo 用） |

- 新增 `dto/PointsSpendCommand`（@Data+四注解+Serializable，校验：userId @NotNull、bizNo @NotBlank、points @NotNull @Min(1)、scene 可空）。
- `client/UserClient.java` 追加方法（路径落在既有 `/inner/user`）：
  `@PostMapping("/points/spend") Result<Void> spendPoints(@Valid @RequestBody PointsSpendCommand cmd);`
  语义：直接扣减可用积分（非 TCC 冻结链），以 bizNo 幂等（积分抽奖一次性消耗用）；不足返回 POINTS_NOT_ENOUGH。

### 4.3 product 域（`com.shop.api.product`）

- `enums/StockReturnReasons`（现状 BUYER=1/QUALITY=2/EXCHANGE=3，见 `StockServiceImpl.java:195-197`）追加 `GROUP_FAIL(4, "拼团失败回补")`。
- 新增 `dto/FreightCalcCommand`：

  | 字段 | 类型 | 校验 | 说明 |
  |---|---|---|---|
  | merchantId | Long | @NotNull | 店铺商户 |
  | shopId | Long | @NotNull | 店铺 |
  | provinceCode/cityCode/districtCode | String | province @NotBlank | 收货地区码 |
  | items | List\<Item\> | @NotEmpty @Valid | Item{Long skuId @NotNull; Integer qty @NotNull @Min(1)} |

- 新增 `dto/FreightCalcResult`：`Long freightFen`、`Boolean deliverable`（不可配送时 order 拒单）、`Long templateId`、`Boolean insuranceSupported`、`Long insurancePremiumFen`（不支持为 null/0）。
- `dto/SkuDTO` 追加 `Long presaleStock`（预售余额，仅预售品返回，其他为 null）。
- `dto/StockLockCommand` 既有 `items` 元素 `StockItemCommand` 追加 `Integer stage`（预售 0 定金/1 尾款，其余 null）；命令顶层追加 `Integer presaleFinalStage`（可空）。
- `client/ProductClient.java` 追加：
  `@PostMapping("/freight/calc") Result<FreightCalcResult> calcFreight(@Valid @RequestBody FreightCalcCommand cmd);`

### 4.4 marketing 域（`com.shop.api.marketing`）

- `dto/PriceCalcCommand` 追加（全部可空，向后兼容）：`Boolean leaderFlag`（拼团下单人是否团长=开团者，orderType=3 且 groupNo 为空时 true）、`Long bargainRecordNo`（砍价单）、`Long insurancePremiumFen`（保费，order 服务端算好上送，默认 0）。
- `dto/PriceCalcResult` 追加：`Long leaderDiscountFen`（团长优惠合计，默认 0）、`Long presaleDepositFen`、`Long presaleInflateFen`、`Long insurancePremiumFen`。恒等式更新为：
  `payFen = originalProductFen - productPromoFen - shopPromoFen - categoryCouponFen - shopCouponFen - platformCouponFen - pointsDeductFen + freightFen + insurancePremiumFen`（预售两阶段为专用恒等式，见 B5）。
- `dto/ItemPriceDetail` 追加 `Long leaderDiscountAllocFen`。
- `dto/PromotionLockCommand` 追加 `String depositOrderNo`（尾款单传定金单号）、`Long bargainRecordNo`。
- 新增 `dto/ActivityBindingDTO`：`Long skuId`、`Integer activityType`（10 秒杀/11 拼团/12 预售）、`Long activityId`、`Integer perOrderQtyLimit`、`Integer perUserOrderLimit`（ruleJson 缺省分别为 1 件 / 1 单）。
- `client/MarketingClient.java` 追加：
  `@PostMapping("/activity/bindings") Result<List<ActivityBindingDTO>> findActiveBindings(@RequestBody List<Long> skuIds);`
  语义：仅返回 status=1 且当前时间在活动窗口内、且 SKU 在活动配置内（秒杀查 t_seckill_sku，拼团/预售按 ruleJson.skuIds）的绑定；每 SKU 至多 1 条（多活动命中时优先级 秒杀>拼团>预售）。
- 事件类（GroupbuyEvent/SeckillEvent/PresaleEvent）**不动**。

### 4.5 order 域（`com.shop.api.order`）

- `event/OrderCreatedEvent` 追加：`Integer presaleFinalStage`、`Integer hasFreightInsurance`（0/1）、`Long insurancePremiumFen`。
- `event/OrderShippedEvent` 追加：`Integer hasFreightInsurance`、`Long insurancePremiumFen`。
- `dto/OrderDTO` 追加：`Integer presaleFinalStage`、`Integer hasFreightInsurance`、`Long insurancePremiumFen`、`Long presaleDepositFen`、`Long presaleInflateFen`、`String depositOrderNo`、`Integer groupSuccess`。
- `event/OrderItemMessage`、OrderClient **不动**。

### 4.6 pay 域（`com.shop.api.pay`）

- `dto/CreatePaymentCommand` 追加 `Integer bizScene`（可空，缺省 1）。
- 新增 `dto/CreateMerchantPaymentCommand`：`Long merchantId @NotNull`、`String bizNo @NotBlank`（保证金充值流水号 logNo，幂等键）、`Long amountFen @NotNull @Min(1)`、`Integer payMethod @NotNull`、`String terminal`（字符串，可空默认 H5）、`String subject`。
- `client/PayClient.java` 追加：
  `@PostMapping("/merchant-payment") Result<PaymentDTO> createMerchantPayment(@Valid @RequestBody CreateMerchantPaymentCommand cmd);`
  幂等：bizNo 维度（同 bizNo 返回原 PaymentDTO）；支付单号仍走 P+17 位。
- `enums/RefundSources`（1/2/3 现状）追加 `GROUPBUY_FAIL(4, "拼团失败退款")`。
- 新增 `event/MerchantDepositPaidEvent extends BaseEvent`：`String payNo`、`String bizNo`、`Long merchantId`、`Long amountFen`、`String payMethod`(Integer→用 Integer payMethod 与 PaymentSucceededEvent 一致)、`LocalDateTime paidTime`。

### 4.7 aftersale 域（`com.shop.api.aftersale`，首个 client，D11）

- 新增 `client/AftersaleClient`：
  `@FeignClient(name="shop-aftersale-service", path="/inner/aftersale")`
  `@GetMapping("/dispute/exists") Result<Boolean> existsOpenDispute(@RequestParam("merchantId") Long merchantId, @RequestParam("sinceMillis") Long sinceMillis);`
  语义：存在「未终局」售后（status ∈ 10,20,30,40,41,42,43,55,80）或平台介入记录（t_aftersale_dispute 未关闭）且 create_time ≥ since 则 true。

### 4.8 WP0 自身验收

shop-api/shop-common `mvn -am install` 通过；不得新增任何实现类；逐条对照本清单字段名/类型/注解，服务端实现代理以本清单签名为准逐字实现（范式参照 `API_CONTRACTS.md` §0）。

---

## 5. 按服务切分的工作包与依赖顺序

```
WP0 shop-api + shop-common 契约包（4.1–4.7 全部内容）── 必须第一个合入
   │
   ├── WP1 user-service       (B6 消费侧：注册事件生产 + 行为事件消费 + spendPoints)
   ├── WP2 product-service    (B7 运费模板/保费规则 + B13 虚拟类目/规格主数据/发货扣减 + B5 预售库存)
   ├── WP3 marketing-service  (B1 团长价 + B2 审核流/秒杀状态 Job + B3 砍价抽奖 + B4 秒杀加固 + B5 膨胀计价 + B6 新人券)
   ├── WP4 order-service      (B1 团事件消费 + B4 活动绑定校验 + B5 两单 + B6 分享事件 + B7/B11 运费保费接入)
   ├── WP5 pay-service        (B10 商户充值支付场景 + RefundSources=4 受理)
   ├── WP6 settlement-service (B9 守恒修复 + B10 保证金入账/罚款/清退打款 + B5 预售分账时点)
   └── WP7 aftersale-service  (B11 保单生效/理赔衔接 + B10 纠纷查询 Feign)
   └── WP8 gateway            (B12 限流 + prometheus)
```

- WP1–WP8 **编译只依赖 WP0**，可并行编码、并行提 PR；联调依赖见下，不阻塞开发：
  - WP4 联调依赖 WP3 的 `findActiveBindings` 实现、WP2 的 `calcFreight` 实现；WP3 依赖 WP1 的 `spendPoints`。
  - WP6 联调依赖 WP5 的 MERCHANT_DEPOSIT_PAID 与 WP7 的 `AftersaleClient` 实现。
  - WP7 联调依赖 WP4 发货/支付事件新字段（WP0 已定义）。
- 每个 WP 的 SQL 文件互不重名（§3 已按库隔离），合并无 DDL 文件冲突。

### 5.1 各包改动一览

**WP1 user（B6）**：`AuthServiceImpl.register`（:53-92）末尾同事务发 USER_REGISTERED；新增 `UserBehaviorMqListener`（cg_user_behavior）+ `UserBehaviorService`：comment→GrowthScene.COMMENT(+10)+`PointsCalc.commentPoints(withImage)`（20/30，日上限 100），withImage 再加 GrowthScene.SHOW_ORDER(+20)；share→PointsScene.SHARE(+10，日上限 20，复用 `AccountServiceImpl.dailyCapOf:379-390` 已有的 SHARE 分支)；`InnerUserController` + `AccountServiceImpl` 实现 `spendPoints`（直接扣可用积分、PointsChangeType.CONSUME 流水、bizNo 幂等，scene=LOTTERY）。无 DDL。

**WP2 product（B7/B11/B13/B5 商品侧）**：运费模板 3 实体+Service+商户/平台 Controller+`ProductInnerController.calcFreight`；保费规则表与计价（并入 calcFreight 返回）；虚拟类目关联实体/校验/树装配；规格主数据 2 实体+管理端点+SPU/SKU 保存校验（配置开关，D14）；`StockServiceImpl` 预售分流（lock/paid/cancel 三态）、新增 `OrderShippedStockListener` 发货出账、回库 SQL 兼容；SkuDTO/StockLockCommand 字段落地。DDL：V3。

**WP3 marketing（B1/B2/B3/B4/B5/B6 营销侧）**：`GroupbuyService` SUCCESS 逐成员发事件 + ruleJson 读 groupPriceFen；`PriceEngine` 团长优惠层、砍价价、保费恒等式、预售定金/膨胀计价；三类审核流（状态机+商户端 3 个 Controller+平台 audit 3 端点+审核字段）；`SeckillStateJob` 到点开始/结束；`SeckillService` 可配限购 + UK 移除后的计数校验；`SeckillEventListener`（域内同步消费者）；`SeckillReconcileJob` 升级修正+滞留扫描（调 OrderClient）；砍价/抽奖全套 Service/Controller + `spendPoints` 扣积分 + `CouponService.issue:116-163` 发券；新人券 `UserRegisteredListener`（查 issue_way=3 模板，无则跳过）；`InnerMarketingController.findActiveBindings`；尾款单锁定校验。DDL：V5。

**WP4 order（B1/B4/B5/B6/B7/B11）**：新增 `GroupbuyEventListener`（cg_order_groupbuy，tag3/4）+ 退款/续期/发货位逻辑；`OrderCreateServiceImpl.prepare` 加活动绑定校验、服务端运费（忽略客户端 freightFen）、保费校验与金额构成、预售两单与 depositOrderNo、砍价记录透传；`CreateOrderRequest` 加 3 个字段；`OrderPersister` 事件填新字段；`OrderOperateServiceImpl.ship` 拼团发货拦截；新增分享端点。DDL：V3。

**WP5 pay（B10/B1 退款侧）**：支付单 bizScene 贯通（创建、回调路由、事件发布分支）；`PayInnerController.createMerchantPayment` + service 新方法（bizNo 幂等、商户充值不做 OrderClient 回查、回调成功发 MERCHANT_DEPOSIT_PAID 且不发 ORDER_PAID；保留 15min 主动查单）；`RefundServiceImpl` 受理 source=4（金额校验仍按原订单实付累计上限）。DDL：V5。

**WP6 settlement（B9/B10/B5）**：SplitEngine 公式 + 断言；SettleClearingExecutor 复核；保证金充值改事件入账（删 `doPayDeposit:79-98` mock 直加路径，`MerchantDepositController` 改为下单返回 payUrl）；`AdminController` 罚款端点；`RemitChannelClient` SPI + Mock 实现 + `WithdrawService.remitOne` 接入 + 保证金清退退款走打款（t_sett_deposit_remit）；`DepositRefundJob` 接入 AftersaleClient 纠纷判定；预售定金单清算跳过、尾款单整单分账。DDL：V5。

**WP7 aftersale（B11/B10）**：ORDER_PAID 保单生效消费（OrderClient.getByOrderNo 读保险位，写 t_aftersale_insurance status=10/claim_deadline=NULL）；退货退款成功回填 claim_deadline 衔接既有 72h Job；保单失效（ORDER_COMPLETED 消费，未理赔→30）；`InnerAftersaleController` 实现纠纷存在性查询。DDL：V3。

**WP8 gateway（B12）**：pom 加 redisson-spring-boot-starter + micrometer-registry-prometheus；Redis 配置（DB 7）；`RateLimitGlobalFilter`（Lua ZSET，全局/路径/用户+IP 维度，yml 规则，白名单，429 体）；actuator prometheus 端点验证。无 DDL。

### 5.2 风险点总览（逐包细化见 §8）

- E2E 59 个用例（11 个测试类，PayE2ETest 6 渠道参数化）中：拼团/预售试算（MarketingE2ETest 7 例）、秒杀、下单金额守恒（OrderCreateE2ETest 7 例）、SettlementE2ETest（7 例）、AftersaleE2ETest（6 例）是高冲突面；E2E 造数 helper（`shop-e2e/.../support/World.java`）与用例修改权统一归 WP4（见 §6）。
- 885 个现存单测：B2 状态码、B5 库存分流、B9 公式、B4 限购是既有断言热点，按各 B 节「受影响测试」清单同步修改，禁止删断言保绿。

---

## 6. 跨工作包文件独占矩阵（防并行编辑冲突）

| 文件/目录 | 独占 WP | 其他 WP 接入方式（禁止直接编辑） |
|---|---|---|
| `shop-common/.../constant/MqTopics.java` | WP0 | 只引用常量 |
| `shop-api/**` 全部 client/dto/event/enums | WP0 | 只实现接口/消费事件；确需补字段回到本契约走 WP0 补丁 |
| `shop-marketing-service/**/engine/PriceEngine.java`、`CalcLine.java`、`ActivityRule.java`、`PromoAdminService.java`、`CouponAdminService.java`、`ActivityAdminService.java`、`SeckillService.java`、`SeckillStockClient.java`、`SeckillReconcileJob.java`、`GroupbuyService.java`、`PresaleService.java`、`MarketingTxOps.java`、`InnerMarketingController.java`、`CouponService.java` | WP3 | WP4 只经 MarketingClient + PriceCalcCommand 新字段（leaderFlag/bargainRecordNo/insurancePremiumFen/depositOrderNo）接入 |
| `sql/marketing/V5__marketing_gaps.sql` | WP3 | — |
| `shop-order-service/**/order/dto/CreateOrderRequest.java`、`OrderCreateServiceImpl.java`、`OrderPersister.java`、`OrderOperateServiceImpl.java`、`PayTimeoutPolicy.java`、`PayTimeoutScanJob.java`、`OrderResourceReleaser.java` | WP4 | B11 保险位、B5 预售字段、B3 砍价号一律由 WP4 按本契约加字段；WP7 只读事件/OrderDTO；WP3 只经命令 DTO 传值 |
| `sql/order/V3__order_gaps.sql`、`shop-e2e/**`（含 `support/World.java` 与全部 E2E 类） | WP4 | 其他包联调需改 E2E 时提需求给 WP4，不自行编辑 |
| `shop-product-service/**/stock/**`、`category/**`、goods 的 spu/sku 保存、spec/freight 新包、`sql/product/V3__product_gaps.sql` | WP2 | WP4 只经 ProductClient；WP3 不碰商品 |
| `shop-product-service/**/comment/service/impl/CommentServiceImpl.java` | **WP1**（仅注入 OutboxPublisher 发 USER_BEHAVIOR_EVENT 一处） | WP2 本批不改评论；新增依赖经构造器，不改 create 签名 |
| `shop-user-service/**`（AuthServiceImpl、AccountServiceImpl、GrowthServiceImpl、InnerUserController、新 listener） | WP1 | WP3 经 UserClient.spendPoints/grantPoints 接入；无 SQL |
| `shop-pay-service/**`（PaymentServiceImpl、PayInnerController、RefundServiceImpl、channel/**）、`sql/pay/V5__pay_merchant_deposit.sql` | WP5 | WP4 只调 PayClient.refund（source=4 经 WP0 枚举编译可见）；WP6 只消费事件 |
| `shop-settlement-service/**`（SplitEngine、SettleClearingExecutor、DepositService、WithdrawService、AdminController、新 Remit*）、`sql/settlement/V5__settlement_deposit.sql` | WP6 | WP5 不反向依赖；WP7 不碰结算 |
| `shop-aftersale-service/**`（AftersaleInsurance 相关、AftersaleMqServiceImpl、AftersaleClaimJob、新 InnerAftersaleController）、`sql/aftersale/V3__aftersale_insurance.sql` | WP7 | WP4 不写售后库 |
| `shop-gateway/**`（pom、yml、全部 filter/config） | WP8 | 服务层 `@RateLimit`/`RateLimitAspect` 保持不动；其他服务不碰网关 |
| `shop-framework/**`、根 pom.xml、除 gateway 外各服务 pom、`CONTRACTS.md`、`API_CONTRACTS.md`、deploy/** | 无人改 | 限流 Lua 在 WP8 网关内复制实现（D13） |

冲突仲裁：矩阵未列出的同模块文件归该模块 WP 独占；两个 WP 同模块的唯一例外（CommentServiceImpl）已显式分配。
