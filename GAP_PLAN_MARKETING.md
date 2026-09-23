# 营销域缺口修复架构规划（GAP_PLAN_MARKETING）

> 角色：营销域缺口修复架构规划员。本文件为**只读勘察后的规划产物**，不含任何 Java 改动；
> 业务代理不得自行修改 shop-api / shop-common，所有契约变更统一汇总在 §2，由契约负责人（W0）一次性落地。
> 覆盖缺口：**B1、B2、B3、B4、B5、B6、MQ P1-1（普通库存对账，跨 product）、MQ P1-2（秒杀对账自愈）、
> MQ P2-1（售后超时消息 eventId，仅框架/契约侧诉求）、API-M（营销 API 契约硬化）**，共 10 张任务卡。
> 勘察基线：AUDIT_FEATURES.md §10.2、AUDIT_MQ_CONSISTENCY.md、AUDIT_API_CONTRACT.md、design.md §2.1/§2.2/§4，
> shop-marketing-service / shop-order-service / shop-product-service / shop-user-service / shop-aftersale-service
> 现状代码、deploy/rocketmq/create-topics.sh、sql/ 全量版本（2026-09-17 现场逐文件核对）。

---

## 0. 勘察基线与审计勘误（先读）

| 编号 | 审计/旧报告口径 | 当前代码事实（文件:行号） | 对规划的影响 |
|---|---|---|---|
| B1 | GROUPBUY_EVENT 无任何消费者；失败退款/成功转待发货/30 分钟续期/团长优惠全缺 | **生产端已存在**：`GroupbuyService.sendEvent`（shop-marketing-service `activity/service/GroupbuyService.java:180-190`）已按 tag `1开团 2参团 3成团 4失败` 发 `shop_groupbuy_event`；满员成团在 `joinGroup`→`markSuccess`（同文件 :100-105）逐成员发 type=3；超时失败 `expireGroups()`（:139-167）逐成员发 type=4。**消费端确为零**（全仓库无 `MqTopics.GROUPBUY_EVENT` 的 MqListener）。团长优惠字段也已存在：`ActivityRule.leaderDiscountFen`、`GroupbuyMember.leaderFlag`，但 `PriceEngine` 从不读取。24h 支付超时已配：`PayTimeoutPolicy`（shop-order-service `policy/PayTimeoutPolicy.java:30-39`）GROUPBUY→24h；**成团后 +30 分钟续期无代码**；ORDER_CREATED 的 `expirePaySeconds` 注释（shop-api `order/event/OrderCreatedEvent.java:54`）已写"拼团 24h+30 分钟"但无实现 | B1 分两半：**营销侧半卡**（事件消费后调订单域对接点、团长价计价）本规划落地；**订单流转半卡归 TRADE**（续期/状态推进/退款编排），本卡仅声明对接点与契约 C25 |
| B2 | 三类后台无审核流、秒杀无到点自动结束 | 属实。`ActivityAdminController`/`PromoAdminController`/`CouponAdminController` 仅 `WebIdentity.requirePlatformAdmin()`（marketing `support/WebIdentity.java:25-30`，只认 userType=2），save 后 status 直接可上架；三表 status 注释分别为 t_promo 0/1、t_coupon 0下架/1上架/2作废、t_activity 0下架/1进行中/2已结束/3已取消（sql/marketing/V2__marketing.sql），**无待审核/驳回态**；秒杀活动结束无 Job（现有 job 仅 GroupbuyExpireJob `0 */5 * * * ?`、PresaleFinalJob `0 */10 * * * ?`、CouponExpireJob、SeckillReconcileJob） | 按 B2 卡修：审核态列 + 商户提交/平台审批接口 + 秒杀到点结束 Job |
| B3 | 砍价/抽奖仅空实体 | 属实但比审计描述略多：`BargainRecord`/`LotteryRecord` 实体字段完整（含乐观锁、uk_user_activity、expire_time），`BargainRecordMapper`/`LotteryRecordMapper` 仅继承 BaseMapper；**零 Service/Controller/Job**；规则位已预留：`ActivityRule.bargainExpireRows`、`ActivityRule.lotteryCostPoints`；ActivityTypes 13/14 枚举已有 | 按 B3 卡修，无需新增实体表 |
| B4 | SECKILL_EVENT 无消费者；活动 ID 空则营销锁静默跳过；对账只告警；限购固化 1 单 | 全部属实。① 零消费者：全仓库无 `MqTopics.SECKILL_EVENT` 监听方；生产端在 `SeckillService.java:228`（tag 1锁/2扣/3释）。② 静默跳过：`inner/MarketingTxOps.java`（`if (orderType == 2 && cmd.getActivityId() != null)` 等三分支），activityId 为 null 时不锁任何活动资源、价格引擎按普通单计价；且 MQ 第二道有衍生缺陷：`mq/OrderCreatedListener.java:64` 只从 item 取 seckill/presale，**从不映射拼团 activityId/groupNo**（`OrderItemMessage` 本身也无 groupbuyActivityId 字段，仅有 groupNo :59），MQ 路径拼团锁必为 no-op（当前靠下单同步 Feign 兜底）。③ 限购：`sql/marketing/V3__seckill_user_uk.sql` 以 uk(activity_id,user_id,deleted) 固化"每活动 1 单"；`SeckillService.lock` 靠 DuplicateKeyException 报 LIMIT_PURCHASE。④ P1-2：`job/SeckillReconcileJob.java` 逐行比对仅 warn，且 `if (redisStock == null) continue;`——**Redis key 丢失也不重建**（RELEASE_KEY_MISSING 仅在实时释放路径重建） | 按 B4+P1-2 卡修；限购 N 单可配置采用"新增每活动限购件数 + 单用户累计量校验"，uk 保留为 1 单兜底之外的方案见 DDL-D1 |
| B5 | 定金膨胀只登记不计价；预售库存下单即锁 | 属实。① 营销：`PresaleService.register`（`activity/service/PresaleService.java`）把 depositFen/inflateDeductFen/finalPayFen/finalPayDays 落 t_presale_order，并发 DEPOSIT_PAID 事件 + CANCEL 延时；`PriceEngine` 仅以 presaleFinalStage 做券互斥开关，**inflateDeductFen 不参与任何计价行**。② 商品：`StockServiceImpl.handleOrderCreated`（product `stock/service/impl/StockServiceImpl.java:365-384`）对 stockType=2(PRESALE) 与普通库存同路径 `doLock`（available→locked），`lockStock`(:87-97)/`doLock`(:99-125) 无类型分支；design.md §3.2 明确"预售库存支付后扣减"。③ 定金单与尾款单无父子关联：t_order_order 有 presale_activity_id/presale_final_stage（sql/order/V2__order.sql:113-117）但无 parent/deposit 订单号列；营销 confirm/release 里预售 release 是 no-op（定金不退，符合设计） | 按 B5 卡修：尾款计价减膨胀 + product PRESALE 分支（建单不锁、支付后直扣）+ 父子单关联契约 C23 |
| B6 | 新人礼包未接线；评价/晒单/分享成长值积分无触发方 | 属实。① 新人礼包：`CouponIssueWays.NEW_USER(3)` 与 `CouponService.issue` 每人一次限制（`coupon/service/CouponService.java:126-128`）已就绪，`InnerMarketingController` issue 入口也在；**但 user `AuthServiceImpl.register` 零调用、无 USER_REGISTERED 事件**（shop-api `user/event/` 仅有 PointsChangedEvent）。② 评价：product `CommentServiceImpl.create` 只落库+更 SPU 计数，**不发任何跨域事件**；ProductComment 实体有 imagesJson/videoUrl，可据此区分晒单。③ 分享：无任何端点。④ user 侧发放能力已齐：`UserClient` /inner/user 的 points/grant、growth/add（shop-api `user/client/UserClient.java`），`GrantPointsCommand`(userId,bizNo,points,scene) bizNo 幂等、日限封顶（COMMENT 100/SHARE 20）已在 AccountService 实现；PointsScene（1消费2签到3评价4分享5晒单6补偿）、GrowthScene（1消费2评价3晒单4周签）枚举已存在 | 按 B6 卡修：缺的是事件生产端 + 营销/user 两个消费端，能力与契约枚举均已存在 |
| P1-1 | product 域零对账 Job，终态订单残留 LOCKED 流水无自动恢复 | 属实：`grep -rln "@Scheduled" shop-product-service/src/main` 无结果；`OrderResourceReleaser`（shop-order-service）同步释放三个 catch 仅日志；product 侧 `handleOrderCancelled`/`handleAftersaleChanged` 已具备幂等释放/回库原语可复用 | 新增 product StockReconcileJob（本规划纳入，因与秒杀同属库存一致性，且是 P1-2 的普通库存对应项） |
| P2-1 | 售后超时消息无 eventId、无消费记录 | 属实：`AftersaleTimeoutMessage`（shop-aftersale-service `support/AftersaleTimeoutMessage.java`）是裸 POJO，不继承 BaseEvent；`AftersaleTimeoutListener`（`mq/listener/AftersaleTimeoutListener.java`）直接 dispatch；`AftersaleTimeoutServiceImpl.dispatch:55-66` 五类自动动作无 t_aftersale_mq_consume 写入（该表已存在 sql/aftersale/V2__aftersale.sql:291），仅靠业务 CAS + AftersaleTimeoutJob 60s 扫表双保险 | **本规划只提框架/契约侧诉求**，售后实现归 AFTERSALE 代理（见 P2-1 卡） |
| API-M | admin GET 缺鉴权、保存 DTO 无正数/枚举校验、changeStatus 任意 int、分页无上限、claim 内联 DTO 无 @Valid | 逐项复核属实：① 7 个 GET 无鉴权——ActivityAdminController 仅 page(:40-47) 1 个；PromoAdminController detail(:44)/levels(:49)/targets(:54)/page(:59) 4 个；CouponAdminController targets(:43)/page(:48) 2 个，合计 7；写操作均已有 requirePlatformAdmin。② `ActivitySaveRequest`（activity/dto/ActivitySaveRequest.java:30-39）seckillSkus 无 @Valid、SeckillSkuRequest 仅 @NotNull 无 @Positive/@Min；`CouponSaveRequest` 金额/折扣/数量零符号注解；`PromoSaveRequest.Level`（:40-47）零注解、嵌套 List 无 @Valid/@Size。③ 三个 changeStatus 均 `@RequestParam int status`，service 只做 CAS 不校验目标态白名单（ActivityAdminService:70-81 等），可写 99。④ 三个分页直接透传 long pageSize 给 MyBatis-Plus，未走 `PageQuery.safePageSize/safePageNum`（shop-common `result/PageQuery.java:22-28`，上限 200）。⑤ `CouponCenterController`（coupon/controller/CouponCenterController.java:43-47 + 内联 :57-62）claim 入参无 @Valid、couponId 无 @NotNull、requestNo 无长度上限 | 按 API-M 卡修，纯营销域内改动，零 shop-api 变更；审核状态机白名单与 B2 共用同一张迁移表 |

已落地、本规划**不得重复**的整改：全域 outbox 事务发件箱（FIXES_D/E/F/G）、营销锁 orderNo Redisson 锁 + TCC 锚点 t_marketing_lock、
秒杀 Redis+Lua 与 RELEASE_KEY_MISSING 重建、V3 秒杀用户 UK、V4 merchant_id 默认值、领券 P2-1 短 TTL 修复
（CouponService.java:37-109）、券核销/锁定/过期全套、支付成功 CAS 单方发事件、各域 mq_consume 三重幂等。

---

## 1. 任务卡总览与依赖图

```
 W0 契约统一落地（C20–C29，shop-api/shop-common 一次性合入）
   │
   ├── API-M 契约硬化（营销域内，无外部依赖；审核状态机表被 B2 复用，先于/同批 B2）
   │
   ├── B2 审核流 + 秒杀到点结束（DDL-D1 营销审核列；独立）
   │      └── P1-2 秒杀自愈（复用 B2 的活动状态：自愈可把场次置 3 已取消/暂停，需 B2 状态语义先定）
   │
   ├── B4 SECKILL_EVENT 消费 + 空 activityId 硬失败 + 限购可配置 + 预占回补
   │      ├── P1-2 与 B4 共用 SeckillService/SeckillStockClient 与对账数据口径
   │      └── P1-1 普通库存对账（product 域，独立 Job，模式对齐 P1-2，无代码依赖）
   │
   ├── B1 拼团营销半卡（团长价 C21 + 成团/失败消费组 cg_marketing_groupbuy；
   │      订单流转半卡归 TRADE，依赖 C25 订单 inner 接口先合入才能联调；
   │      C20 OrderItemMessage.groupbuyActivityId 修的是 B4 MQ 路径同源缺陷，随 W0）
   │
   ├── B5 预售膨胀计价 + PRESALE 支付后扣减 + 父子单（C23；marketing 计价与 product 库存可并行，
   │      父子单关联列 order V3 由 TRADE 实施，营销侧先用 activityId+userId+状态0 反查兜底）
   │
   ├── B3 砍价/抽奖（独立玩法；下单复用普通订单链路 + 活动价快照；积分扣减复用 UserClient 既有接口）
   │
   └── B6 新人礼包 + 评价/晒单/分享激励（C22 新事件/topic；user 发放端与 marketing 礼包端并行开发，
          均依赖 W0 事件类先合入；product 评论发事件是触发源）

 P2-1（框架/契约诉求单）独立，归 AFTERSALE 实施，不进入上述依赖。
```

关键顺序约束：
1. **W0 先行**：C20–C29 全部为向后兼容新增（包装类型字段、新 topic 常量、新事件类、新 Feign 方法），旧消费者不改不感知。
2. **API-M 与 B2 同批评审**：审核流引入的活动/促销/券状态机迁移白名单必须落在同一张状态表，API-M 先建表、B2 扩边。
3. **B1 联调依赖 TRADE**：营销侧消费组成团/失败后的动作（续期 30 分钟、失败退款编排）必须调订单域新 inner 接口（C25）；TRADE 未就绪前营销消费者可先落"事件受理 + 待处理标记"，禁止伪成功。
4. B5 product 侧改动（建单不锁、支付后直扣）需与 order 建单 TCC 编排同步切换；灰度期以 stockType=2 分支隔离。
5. P1-1/P1-2 与 B4 互不阻塞，但对账 Job 的"自动修复动作"必须复用 B4 修复后的 SeckillService 释放/重建方法，避免两套修复逻辑。

---

## 2. 全局契约变更统一清单（shop-api / shop-common，业务代理不得自行改，W0 落地）

> 均为**向后兼容新增**：新字段一律包装类型 + 默认空值语义；新枚举只增码值；新 Feign 方法为增量。

| 编号 | 模块/文件 | 变更 | 服务卡 |
|---|---|---|---|
| C20 | `shop-api` `com.shop.api.order.event.OrderItemMessage` | 新增 `Long groupbuyActivityId`（与现有 groupNo 并列；现有字段 seckillActivityId:56、groupNo:59、presaleActivityId:62）。order 组装事件处（OrderCreateServiceImpl 构造 OrderItemMessage 处）回填订单列 groupbuy_activity_id | B1/B4 |
| C21 | `shop-api` `com.shop.api.marketing.event.GroupbuyEvent` | 新增 `Integer leaderFlag`（1 团长 0 团员；生产端 GroupbuyService.sendEvent :180 从 GroupbuyMember.leaderFlag 透传）。`com.shop.api.marketing.dto.PriceCalcCommand` 新增 `Integer leaderFlag`、`Long groupNo`；`com.shop.api.marketing.dto.CalcItem` 新增 `Long activityPriceFen`（活动价快照：拼团团长价/砍价成交价，分；null 时引擎按现逻辑取 salePriceFen）；`com.shop.api.marketing.dto.PromotionLockCommand` 新增 `Integer leaderFlag`、`String groupNo`、`Long groupbuyActivityId`（与现有 activityId 并存，MQ 第二道路径显式区分活动类型，不再靠"取第一个非空"） | B1/B3 |
| C22 | **以 GAP_PLAN_USER 的 C40/C41/C48 为准（不在本规划重复定义）** | C40：`shop-common MqTopics.USER_REGISTERED="shop_user_registered"`；C41：`shop-api com.shop.api.user.event.UserRegisteredEvent{eventId,userId,registerTime,userType}`（无手机号）；C48：user 注册事务内 outbox 生产。**本规划另需的 `shop_comment_created` / `shop_share_action` 两个 topic 与事件类（CommentCreatedEvent/ShareEvent）仍由 W0 在本契约波一并落地**：`CommentCreatedEvent{commentNo,orderNo,userId,spuId,skuId,withImages,showOrder}`、`ShareEvent{userId,shareTarget,bizId,bizType}`，常量名 MqTopics.COMMENT_CREATED / SHARE_ACTION，需同步 create-topics.sh。复用既有 PointsScene(3评价/4分享/5晒单)、GrowthScene(2评价/3晒单)，**不新增枚举** | B6 |
| C23 | `shop-api` `com.shop.api.order.dto` 建单请求（OrderCreateRequest，实施时定位实际类名）与 `OrderCreatedEvent` | 新增 `String parentOrderNo`（预售尾款单指向定金单 orderNo；null=普通单/定金单）。OrderCreatedEvent 同步携带 parentOrderNo。product `StockItemCommand` 已有 stockType+activityId，无需改；预售"支付后扣减"所需信息 product 可从 t_product_stock_log 与事件 orderType=4 推断，**不加字段** | B5 |
| C24 | 无 shop-api 变更 | 秒杀每用户可购件数为营销域内配置：复用 `t_activity.rule_json` 的 ActivityRule，新增 `Integer perUserBuyLimit`（默认 1；规则解析在 marketing 内部 `activity/support/ActivityRule.java`）；限购 N 件的累计校验在 marketing 内完成，见 D1 | B4 |
| C25 | `shop-api` `com.shop.api.order.client.OrderClient`（现仅有 `GET /inner/order?orderNo`） | **新增 3 个 inner 方法（TRADE 实施，营销 B1 仅调用）**：`@PostMapping("/inner/order/group/renew") Result<Void> renewGroupPayDeadline(@RequestBody GroupPayRenewCommand cmd)`（字段 groupNo、orderNo、plusSeconds=1800；仅 status=10 待付款且已挂成团标记可续，CAS 写 expire_time 并重投 ORDER_PAY_TIMEOUT 延时）；`@PostMapping("/inner/order/group/succeed") Result<Void> markGroupSucceeded(...)`（orderNo/groupNo：已付款单直接 10→20 待发货并发既有履约事件；未付款单挂"已成团"标记等支付回调自然进 20）；`@PostMapping("/inner/order/group/failed") Result<Void> markGroupFailed(...)`（orderNo/groupNo：未付款单按超时取消关单；已付款单触发原路退款编排——退款由 order 域经 PayClient.refund 发起，marketing 不直接碰 PayClient）。配套新增 3 个 command DTO 于 `com.shop.api.order.dto`。**联调未就绪前 marketing 消费者不得跳过事件**（见 B1 残留） | B1（TRADE 半卡） |
| C26 | shop-aftersale `support/AftersaleTimeoutMessage` + 框架注册器 | 见 P2-1 卡：消息体改继承 `com.shop.common.model.BaseEvent`；框架侧 `MqConsumerRegistrar` 对 eventId 缺失仅做兼容期 warn 日志（不 NPE）；无 shop-api 改动 | P2-1 |
| C27 | 无 shop-api 变更 | 砍价/抽奖规则全部在 ActivityRule（marketing 内部）扩展：砍价 `bargainExpireHours`、`bargainCutMin/MaxFen`（单次砍价区间）、`bargainHelpLimit`；抽奖 `List<LotteryPrize> prizes`（prizeCode/prizeName/weight 权重/stock 库存/prizeType 1积分 2优惠券 3谢谢参与/couponId）、`lotteryCostPoints`（已存在）、`lotteryDailyLimit`。下单复用普通订单（orderType=1）携带 C21 的 CalcItem.activityPriceFen 传砍价成交价；**不新增订单类型/库存类型**（砍价专用库存按 design 为独立活动 SKU 的普通库存） | B3 |
| C28 | `shop-api` `com.shop.api.product.enums.StockTypes` | **不改语义、仅补注释**：PRESALE(2) 明确"建单不锁 available/locked，支付成功后 available→occupied 直扣；取消无锁定可释放"。product 分支实现属 B5，枚举码值不动 | B5 |
| C29 | 无 shop-api 变更 | 新人礼包配置：券模板以 issue_way=3（NEW_USER 已存在）标记，营销侧新增"新人礼包券包"读取（t_coupon 增加礼包标记列，见 D1，纯营销库内），无需平台契约 | B6 |

---

## 3. DDL 总清单（无 Flyway；沿用 information_schema 守卫、可重复执行风格，参照 GAP_PLAN_FUNDS DDL）

> 现有版本核对（2026-09-17 现场）：
> marketing：V2/V3/V4 → **续编 V5**；product：仅 V2 → **续编 V3**；order：仅 V2 → **续编 V3（TRADE 实施）**；
> user：仅 V2 → **无需 DDL（B6 全部复用既有积分/成长值流水与 bizNo 幂等）**；aftersale：仅 V2（P2-1 如需由 AFTERSALE 续 V3）。

### D1. `sql/marketing/V5__marketing_gap.sql`（shop_marketing 库）

```sql
USE shop_marketing;

DROP PROCEDURE IF EXISTS marketing_v5_gap;
DELIMITER //
CREATE PROCEDURE marketing_v5_gap()
BEGIN
    -- 1) B2 审核流：促销/券/活动三表加审核态与审核留痕
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_promo'
                     AND COLUMN_NAME='audit_status') THEN
        ALTER TABLE t_promo
            ADD COLUMN audit_status TINYINT NOT NULL DEFAULT 2
                COMMENT '审核态：0草稿 1待审核 2通过 3驳回（存量直接置2通过）' AFTER status,
            ADD COLUMN submit_time DATETIME NULL COMMENT '商户提交时间' AFTER audit_status,
            ADD COLUMN audit_user_id BIGINT NULL COMMENT '审核人（平台运营）ID' AFTER submit_time,
            ADD COLUMN audit_time DATETIME NULL COMMENT '审核时间' AFTER audit_user_id,
            ADD COLUMN audit_remark VARCHAR(255) NOT NULL DEFAULT '' COMMENT '驳回原因' AFTER audit_time,
            ADD KEY idx_audit_status (audit_status);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_coupon'
                     AND COLUMN_NAME='audit_status') THEN
        ALTER TABLE t_coupon
            ADD COLUMN audit_status TINYINT NOT NULL DEFAULT 2
                COMMENT '审核态：0草稿 1待审核 2通过 3驳回' AFTER status,
            ADD COLUMN submit_time DATETIME NULL AFTER audit_status,
            ADD COLUMN audit_user_id BIGINT NULL AFTER submit_time,
            ADD COLUMN audit_time DATETIME NULL AFTER audit_user_id,
            ADD COLUMN audit_remark VARCHAR(255) NOT NULL DEFAULT '' AFTER audit_time,
            ADD COLUMN new_user_gift TINYINT NOT NULL DEFAULT 0
                COMMENT 'B6 新人礼包标记：0否 1是（issue_way=3 的券中仅标记券随注册自动发放）' AFTER audit_remark,
            ADD KEY idx_audit_status (audit_status);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_activity'
                     AND COLUMN_NAME='audit_status') THEN
        ALTER TABLE t_activity
            ADD COLUMN audit_status TINYINT NOT NULL DEFAULT 2
                COMMENT '审核态：0草稿 1待审核 2通过 3驳回' AFTER status,
            ADD COLUMN submit_time DATETIME NULL AFTER audit_status,
            ADD COLUMN audit_user_id BIGINT NULL AFTER submit_time,
            ADD COLUMN audit_time DATETIME NULL AFTER audit_user_id,
            ADD COLUMN audit_remark VARCHAR(255) NOT NULL DEFAULT '' AFTER audit_time,
            ADD COLUMN auto_end TINYINT NOT NULL DEFAULT 1
                COMMENT 'B2 到点自动结束：0否 1是（秒杀默认1）' AFTER audit_remark,
            ADD KEY idx_audit_status (audit_status);
    END IF;

    -- 2) B4 秒杀每用户可购件数：t_seckill_order 增件数汇总索引所需列已齐(activity_id,user_id)，
    --    限购 N 件通过 SUM(qty) 条件实现，不加列；为对账自愈加修复动作留痕表
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_stock_reconcile_log') THEN
        CREATE TABLE t_stock_reconcile_log (
            id            BIGINT NOT NULL COMMENT '雪花主键',
            scope         TINYINT NOT NULL COMMENT '对账域：1秒杀Redis/DB 2秒杀预占回补 3普通库存(product域另建)',
            activity_id   BIGINT NULL COMMENT '秒杀活动 ID',
            sku_id        BIGINT NULL,
            deviation     BIGINT NOT NULL DEFAULT 0 COMMENT '偏差量（Redis-DB，带符号）',
            action        TINYINT NOT NULL COMMENT '处置：0仅告警 1重建Redis 2自动停售 3释放预占',
            metric_before VARCHAR(500) NOT NULL DEFAULT '' COMMENT '处置前指标 JSON',
            create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_scope_time (scope, create_time),
            KEY idx_activity_sku (activity_id, sku_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存对账自愈留痕（P1-2/B4）';
    END IF;

    -- 3) B1 成团事件受理幂等/待办（TRADE 接口未就绪期间的落地锚点，防止事件空转丢失）
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_groupbuy_event_todo') THEN
        CREATE TABLE t_groupbuy_event_todo (
            id           BIGINT NOT NULL COMMENT '雪花主键',
            event_id     VARCHAR(64) NOT NULL COMMENT 'GroupbuyEvent.eventId',
            group_no     VARCHAR(64) NOT NULL,
            activity_id  BIGINT NOT NULL,
            order_no     VARCHAR(64) NOT NULL,
            user_id      BIGINT NOT NULL,
            leader_flag  TINYINT NOT NULL DEFAULT 0,
            op_type      TINYINT NOT NULL COMMENT '3成团 4失败',
            handle_status TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1已通知订单域 2失败待重试',
            retry_count  INT NOT NULL DEFAULT 0,
            create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            UNIQUE KEY uk_event_id (event_id),
            KEY idx_status (handle_status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拼团事件受理待办（B1，TRADE对接缓冲）';
    END IF;

    -- 4) B3 抽奖奖品库存独立表（权重在 rule_json，发奖库存需独立扣减与留痕；砍价复用 t_bargain_record，无需新表）
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_lottery_prize_stock') THEN
        CREATE TABLE t_lottery_prize_stock (
            id           BIGINT NOT NULL COMMENT '雪花主键',
            activity_id  BIGINT NOT NULL,
            prize_code   VARCHAR(64) NOT NULL,
            prize_name   VARCHAR(128) NOT NULL DEFAULT '',
            prize_type   TINYINT NOT NULL COMMENT '1积分 2优惠券 3谢谢参与',
            coupon_id    BIGINT NULL COMMENT 'prize_type=2 时发放的券模板 ID',
            points       INT NOT NULL DEFAULT 0 COMMENT 'prize_type=1 时积分数量',
            weight       INT NOT NULL DEFAULT 0 COMMENT '中奖权重',
            total_stock  INT NOT NULL DEFAULT 0 COMMENT '库存；0=不限',
            issued_count INT NOT NULL DEFAULT 0 COMMENT '已发数量',
            deleted      TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            UNIQUE KEY uk_activity_prize (activity_id, prize_code, deleted),
            KEY idx_activity (activity_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='抽奖奖品配置与库存（B3）';
    END IF;

    -- 5) B3 砍价帮砍留痕（同一帮砍人对同一记录仅一次）
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_marketing' AND TABLE_NAME='t_bargain_help') THEN
        CREATE TABLE t_bargain_help (
            id               BIGINT NOT NULL COMMENT '雪花主键',
            record_id        BIGINT NOT NULL COMMENT '砍价记录 ID',
            helper_user_id   BIGINT NOT NULL COMMENT '帮砍用户 ID',
            cut_fen          BIGINT NOT NULL COMMENT '本次砍下金额（分）',
            create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted          TINYINT NOT NULL DEFAULT 0,
            PRIMARY KEY (id),
            UNIQUE KEY uk_record_helper (record_id, helper_user_id, deleted),
            KEY idx_helper (helper_user_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='砍价帮砍留痕（B3）';
    END IF;
END //
DELIMITER ;
CALL marketing_v5_gap();
DROP PROCEDURE IF EXISTS marketing_v5_gap;
```

### D2. `sql/product/V3__product_presale_recon.sql`（shop_product 库，B5 + P1-1）

```sql
USE shop_product;

DROP PROCEDURE IF EXISTS product_v3_presale_recon;
DELIMITER //
CREATE PROCEDURE product_v3_presale_recon()
BEGIN
    -- 预售"支付后扣减"无需新列：t_product_sku 既有 available/locked/occupied（V2:104-106）；
    -- t_product_stock_log.type 已记录 stock_type(PRESALE=2)，预售直扣流水 status 直接置 DEDUCTED(1)，
    -- 仅补对账留痕表：
    IF NOT EXISTS (SELECT 1 FROM information_schema.TABLES
                   WHERE TABLE_SCHEMA='shop_product' AND TABLE_NAME='t_stock_reconcile_log') THEN
        CREATE TABLE t_stock_reconcile_log (
            id           BIGINT NOT NULL COMMENT '雪花主键',
            order_no     VARCHAR(32) NULL,
            sku_id       BIGINT NOT NULL,
            log_id       BIGINT NULL COMMENT '悬挂的 t_product_stock_log.id',
            issue_type   TINYINT NOT NULL COMMENT '1终态订单残留LOCKED 2无有效订单LOCKED 3预售支付后未扣',
            action       TINYINT NOT NULL COMMENT '0告警 1自动释放 2补扣',
            detail       VARCHAR(500) NOT NULL DEFAULT '',
            create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (id),
            KEY idx_issue_time (issue_type, create_time),
            KEY idx_order (order_no)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='普通库存对账留痕（P1-1/B5）';
    END IF;
END //
DELIMITER ;
CALL product_v3_presale_recon();
DROP PROCEDURE IF EXISTS product_v3_presale_recon;
```

### D3. `sql/order/V3__order_groupbuy_presale.sql`（shop_order 库，**TRADE 实施，营销卡仅声明依赖**）

```sql
USE shop_order;
-- 1) B1 成团标记 + 续期次数（OrderClient 新接口 CAS 锚点）
ALTER TABLE t_order_order
    ADD COLUMN group_succeed TINYINT NOT NULL DEFAULT 0
        COMMENT '拼团已成团标记：0否 1是（成团事件到达后置1）' AFTER group_no,
    ADD COLUMN renew_count INT NOT NULL DEFAULT 0
        COMMENT '支付截止续期次数（拼团成团 +30min 至多 1 次）' AFTER group_succeed,
    ADD COLUMN parent_order_no VARCHAR(32) NULL
        COMMENT 'B5 父订单号（尾款单指向定金单）' AFTER presale_final_stage,
    ADD KEY idx_parent (parent_order_no);
-- 若列已存在请按 information_schema 守卫风格包裹（TRADE 实施时对齐 D1 写法）。
```

---

## 4. 任务卡

### 卡 B1（营销半卡）：拼团成团/失败事件消费落地 + 团长优惠计价（订单流转半卡归 TRADE）

**现状证据**

- 生产端完整、消费端为零：`GroupbuyService.sendEvent`（shop-marketing-service `activity/service/GroupbuyService.java:180-190`）同事务发 `shop_groupbuy_event`，tag = type 字符串（1开团/2参团/3成团/4失败）；成团在 `joinGroup` 满员 `markSuccess` 后逐成员发 type=3（:100-105），失败在 `expireGroups()`（:139-167，GroupbuyExpireJob `0 */5 * * * ?` + 24h 时限 `GROUP_EXPIRE_HOURS=24`）逐成员发 type=4。全仓库无任何 `MqTopics.GROUPBUY_EVENT` 的 `MqListener`。
- 团长优惠"有字段无实现"：`ActivityRule.leaderDiscountFen`、`GroupbuyMember.leaderFlag`（团长 1）已落库；`GroupbuyService.insertMember`（:107-120）已写入 leaderFlag；但 `engine/PriceEngine.java` 拼团分支直接取客户端上送 salePriceFen，从不读 leaderDiscountFen；order 建单侧 `OrderCreateServiceImpl.resolveUnitPrice` 拼团同样按 salePriceFen。
- 续期：`PayTimeoutPolicy`（shop-order-service `policy/PayTimeoutPolicy.java:30-39`）拼团固定 24h；成团后 +30 分钟续期、已付款成团直转待发货均无实现（order 无 group_succeed 列，见 D3）。
- MQ 第二道同源缺陷：`mq/OrderCreatedListener.java:64` 只映射 seckill/presale activityId，拼团活动在 MQ 路径永远拿不到活动 ID（依赖 C20+C21 修复）。

**改动模块与精确文件清单（shop-marketing-service）**

新建（包 `com.shop.marketing.mq`）：
- `GroupbuyEventListener.java`：`implements MqListener<GroupbuyEvent>`，`topic()=MqTopics.GROUPBUY_EVENT`，`tag()` 返回 `"3||4"`（RocketMQ 标签表达式，仅成团/失败；1/2 开团参团不触发订单动作），`consumerGroup()="cg_marketing_groupbuy"`，`type()=GroupbuyEvent.class`。
- `GroupbuyEventTodoService.java`（包 `com.shop.marketing.groupbuy`，或并入 activity/service）：`void handle(GroupbuyEvent e)`——eventId 经 `MqConsumeTemplate.runOnce(eventId, topic, orderNo, ...)` 落 t_marketing_mq_consume；同事务写 t_groupbuy_event_todo（uk_event_id），再调订单域对接点；Feign 失败抛异常触发 MQ 重试，todo 行保留 status=2 由补偿 Job 扫。
- `job/GroupbuyEventRetryJob.java`：ShedLock `@SchedulerLock(name="marketing:groupbuyEventRetry", lockAtMostFor="PT5M", lockAtLeastFor="PT30S")`，cron `0 */2 * * * ?`；扫 handle_status IN(0,2) 且 retry_count<10 的 todo 行重新调用订单域，指数退避（retry_count × 60s）。

修改：
- `engine/PriceEngine.java`：拼团分支读取 `cmd.getLeaderFlag()`（C21 PriceCalcCommand 新字段），为团长（leaderFlag=1）时取 `ActivityRule.leaderDiscountFen`：团长活动价 = max(活动拼团价 - leaderDiscountFen, 0)，以该值替换该团员 item 的计价基准；仍走"拼团互斥券/积分"既有分支，守恒断言（引擎内现有价格恒等式）必须包含团长优惠行。活动规则加载复用引擎现有 Activity 读取路径。
- `inner/MarketingTxOps.java`：orderType=3 分支把 `cmd.getLeaderFlag()/getGroupNo()/getGroupbuyActivityId()`（C21 PromotionLockCommand）透传 `groupbuyService.openOrJoin`；开团人 leaderFlag=1、参团人 0，由 GroupbuyService 依据是否新建团决定（openGroup 成员 leaderFlag=1 已存在，营销锁命令侧只透传 groupNo 与 activityId）。
- `mq/OrderCreatedListener.java:43-73`：修复映射——从 `OrderItemMessage` 取 C20 新增的 groupbuyActivityId（配合 groupNo），按 orderType 显式选择活动 ID（3→groupbuyActivityId 且带 leaderFlag/groupNo，2→seckill，4→presale），构造 C21 增强后的 PromotionLockCommand；删除"取第一个非空"的隐式逻辑。
- `inner/InnerMarketingController.java`：无新端点（消费组直接调内部 service）；若营销侧需要团长价试算返回，calculate 已支持 PriceCalcCommand 新字段，无需加路由。

**核心实现步骤（状态码/topic/tag/消费组/方法签名）**

1. 消费：`GroupbuyEventListener.onMessage(GroupbuyEvent e)` → `consumeTemplate.runOnce(e.getEventId(), MqTopics.GROUPBUY_EVENT, e.getOrderNo(), () -> todoService.handle(e))`。tag 过滤 `"3||4"`。
2. handle 内动作（仅营销域职责）：
   - type=3（成团）：`orderClient.markGroupSucceeded(MarkGroupSucceedCommand{orderNo=e.orderNo, groupNo=e.groupNo})`（C25，TRADE 实现：已付款→10/20 推进至 20 待发货；未付款→置 group_succeed=1 并续期 30 分钟重投 ORDER_PAY_TIMEOUT）。
   - type=4（失败）：`orderClient.markGroupFailed(MarkGroupFailedCommand{orderNo, groupNo})`（C25：未付款→按 cancelType=2 超时关单发 ORDER_CANCELLED，营销/库存既有消费者据此释放；已付款→由订单域发起原路退款 PayClient.refund，refundNo 幂等，REFUND_SUCCESS 后回写订单）。**营销域不直接调 PayClient、不直接改订单状态**。
   - Feign 结果落 todo.handle_status：1 已通知 / 2 待重试；事件重放以 uk_event_id 与订单域接口自身幂等双重兜底。
3. 团长计价：PriceEngine 拼团分支对 leaderFlag=1 的请求应用 leaderDiscountFen；营销锁快照 snapshot_json 记录团长价，供订单 price_snapshot 与后续退款分摊使用。
4. 试算链路：order 建单 `OrderCreateServiceImpl` 调 MarketingClient.calculate 时由 order 透传 leaderFlag（开团请求 order 侧可依据是否传 groupNo 判定：无 groupNo=开团=团长）；该 order 侧透传随 TRADE 半卡实施，营销侧 PriceCalcCommand 先行兼容 null（null 时按非团长，不回退现有行为）。

**契约变更**：C20（OrderItemMessage.groupbuyActivityId）、C21（GroupbuyEvent.leaderFlag + PriceCalcCommand/CalcItem/PromotionLockCommand 字段）、C25（OrderClient 三个 inner 方法与 DTO，TRADE 实施）。

**DDL**：D1 中 t_groupbuy_event_todo；订单列 group_succeed/renew_count 在 D3（TRADE）。

**单测用例清单**
- `GroupbuyEventListenerTest`：tag=3 调 markGroupSucceeded 一次；tag=4 调 markGroupFailed 一次；tag=1/2 不触发（订阅过滤 + 单测双验）；同一 eventId 重放只执行一次（mq_consume 幂等）；Feign 抛异常时消息重试且 todo=2；重试 Job 对 status=2 行成功后翻 1、retry_count 达 10 停止并告警。
- `PriceEngineGroupbuyLeaderTest`：团长价 = 拼团价 - leaderDiscountFen；优惠大于拼团价时底价为 0 不出负；团员价不变；团长单仍拒绝券与积分；应用团长价后价格守恒恒等式成立。
- `OrderCreatedListenerMappingTest`：orderType=3 事件（含 C20 groupbuyActivityId、groupNo）构造出的 PromotionLockCommand 活动 ID 为拼团活动且带 groupNo；2/3/4 三类事件活动 ID 互不串台；活动 ID 缺失时按 B4 硬失败语义抛错。

**E2E 验收点（shop-e2e，建议新增 `MarketingGroupbuyE2ETest`，复用 World/DataFactory/Poller）**
1. 2 人团：开团人下单未付→第二人参团满员→轮询两单 group_succeed=1、未付款单 expire_time 较成团时间 +30 分钟（±容差）、RocketMQ 中 ORDER_PAY_TIMEOUT 延时被重投；开团人随后支付成功，订单进 20 待发货。
2. 团 24h（测试环境调小 GROUP_EXPIRE_HOURS 或直接造过期团）未满：两成员订单均关单（50 已取消），若已付款则原路退款完成、REFUND_SUCCESS 仅一条，营销锁/库存均释放（t_marketing_lock.status=2、秒杀外的拼团占用回补）。
3. 团长订单价格快照比团员少 leaderDiscountFen；成团事件重复投递两次，订单侧只续期一次（renew_count=1）。

**残留与跨域声明**
- 订单状态推进、expire_time CAS、延时重投、退款编排均为 **TRADE 半卡**（C25/D3），不在本服务实施；TRADE 未就绪期间营销消费者只落 todo + 重试，不允许吞事件或伪造成功。
- 支付域退款能力（PayClient.refund、refundNo 幂等、REFUND_SUCCESS）已存在，无需新增支付契约。

**风险与回归面**
- 重复续期/重复退款：订单域 CAS（仅 status=10、renew_count=0）+ 营销 eventId 幂等 + refundNo 幂等三层；回归 GroupbuyService 现有开团/参团/expire 全部单测、OrderCreated/Paid/Cancelled 三监听器、拼团价试算。

---

### 卡 B2：促销/券/活动"提交→平台审核"流 + 秒杀到点自动结束（与 API-M 共用状态机）

**现状证据**
- 三 AdminController 写操作已有 `WebIdentity.requirePlatformAdmin()`，但 save 后 status 由请求体直给、可直接上架；无商户角色（marketing `support/WebIdentity.java` 仅 userType=2 平台判定，无 requireMerchantId——settlement 域同名类有该方法可参照）。
- 三表无审核态：t_promo.status 0/1、t_coupon.status 0下架/1上架/2作废、t_activity.status 0下架/1进行中/2已结束/3已取消（sql/marketing/V2__marketing.sql）。
- 秒杀无结束 Job：现有 job 目录仅 GroupbuyExpireJob/PresaleFinalJob/CouponExpireJob/SeckillReconcileJob；`ActivityAdminService.save` 在保存时初始化秒杀 Redis 库存，但到点不回收、活动 status 不翻 2。

**改动模块与精确文件清单（shop-marketing-service）**

新建：
- `support/MarketingAuditStatus.java`（枚举：DRAFT(0) 草稿、PENDING(1) 待审核、APPROVED(2) 通过、REJECTED(3) 驳回）。
- `support/MarketingStatusMachine.java`：三张实体各一张静态迁移白名单 Map。活动：0草稿→1待审核→2通过/3驳回；2通过后随业务 status 走 0下架⇄1进行中（上架仅在 audit_status=2 允许），到点 1→2已结束（Job 自动），任意非终态→3已取消。促销：0草稿→1→2/3，通过后业务 status 0停用⇄1启用。券：0草稿→1→2/3，通过后业务 status 0下架⇄1上架，1/0→2作废（不可逆）。提供 `assertTransition(entity, auditStatus, bizStatus)`，非法迁移抛 `BizException(ErrorCode.PARAM_INVALID, "非法状态迁移")`。**API-M 的 changeStatus 白名单直接引用本类，不另造**。
- 商户端控制器（如平台暂无商户登录态，先以 userType 判定预留）：`controller/merchant/MerchantPromoController.java`、`MerchantCouponController.java`、`MerchantActivityController.java`（`/merchant/promos|coupons|activities`）：save 草稿（audit_status=0）、submit（0/3→1）、查看自己的单；`WebIdentity` 增 `requireMerchant()`（参照 settlement 同名实现，取商户归属）。
- `controller/PlatformAuditController.java`（`/admin/audits`）：`GET /admin/audits?type=promo|coupon|activity&auditStatus=1` 分页待审列表；`POST /admin/audits/{type}/{id}/approve`（1→2，记 auditUserId/auditTime）；`POST .../reject`（1→3，body 必填 remark）。
- `job/SeckillAutoEndJob.java`：`@Scheduled(cron="0 */1 * * * ?")` + `@SchedulerLock(name="marketing:seckillAutoEnd", lockAtMostFor="PT3M", lockAtLeastFor="PT10S")`；扫 t_activity type=10、status=1、auto_end=1、end_time<=now：①CAS `activityMapper.updateStatus(id,1,2)` ②调 SeckillStockClient 读取/删除 Redis 余量键（或置 SOLD_OUT 哨兵，复用 `SeckillStockClient` 常量 SOLD_OUT=-1 与现有方法，不新写 Lua）③释放全部仍 status=0 的秒杀预占（复用 B4 的 SeckillService 预占回补方法）。

修改：
- `promo/service/PromoAdminService.java`、`coupon/service/CouponAdminService.java`、`activity/service/ActivityAdminService.java`：save 时审核态处理——平台管理员保存自动 audit_status=2（保持现有平台直建能力不回退），商户保存为 0、提交为 1；changeStatus 的上架/启用动作先校验 audit_status=2；所有状态写入改经 MarketingStatusMachine。
- 三 AdminController：approve/reject/list 挂 requirePlatformAdmin；page 返回带 audit_status（实体加字段后自动携带）。

**核心实现步骤**
1. 状态机先行（API-M 依赖）：任何写 status/audit_status 的路径统一过 `MarketingStatusMachine.assertTransition`，迁移白名单单测穷举。
2. 审核流：商户 submit 写 audit_status=1 + submit_time；平台 approve（1→2）/reject（1→3 + remark 必填，空 remark 抛 PARAM_INVALID）；驳回后允许商户改后再提交（3→1）。
3. 上架闸门：业务 status 置 1（进行中/上架/启用）必须 audit_status=2，否则抛 CONFLICT "未通过审核"。
4. 秒杀到点结束：分钟级扫描 + CAS；结束动作（Redis 收口、预占释放）必须幂等，重复扫描以 updateStatus 影响行数为门槛。

**契约变更**：无 shop-api 变更（管理端为服务内 HTTP 接口）。

**DDL**：D1（三表 audit_status/submit_time/audit_user_id/audit_time/audit_remark + t_activity.auto_end）。

**单测用例清单**
- `MarketingStatusMachineTest`：三类实体合法/非法迁移穷举（含 99、驳回态上架、作废后复活等）。
- `PromoAuditServiceTest`/`CouponAuditServiceTest`/`ActivityAuditServiceTest`：商户保存为草稿、提交待审、平台通过/驳回、驳回原因空拒绝、未通过审核禁止上架、平台管理员直建自动通过（存量行为不回退）。
- `SeckillAutoEndJobTest`：到点活动翻 2 且 Redis 收口、预占释放被调用；未到点/已结束/auto_end=0 不动；CAS 失败者不执行释放；多实例 ShedLock 单跑。

**E2E 验收点（shop-e2e）**
1. 商户建活动（草稿）→提交→普通用户看不到/不能参与→平台通过→立即可见可参与；驳回带原因，修改后可再次提交。
2. 秒杀活动 end_time 过后 1 个扫描周期内：活动 status=2、Redis 余量键被收口（再查返回 SOLD_OUT/缺键重建亦为 0）、未付款预占单全部释放、商品可售库存回补。

**残留**：商户账号体系（userType 商户码、商户与店铺归属）若网关尚未注入商户身份，商户端三控制器以 WebIdentity 既有头为预留点，缺身份时返回 FORBIDDEN；不阻塞平台审核流主路径。

**风险与回归面**：回归三 AdminService 现有 save/changeStatus/page 全部用例与平台直建流程；Redis 收口误删风险以"仅 end_time 已过且 DB CAS 成功"为门槛。

---

### 卡 B3：砍价玩法 + 积分抽奖完整业务（实体已存在，补 Service/Controller/Job/奖品库存）

**现状证据**
- `activity/entity/BargainRecord.java`（字段 activityId/skuId/userId/orderNo/originPriceFen/floorPriceFen/currentPriceFen/helpCount/status 0砍价中1成交2失效/expireTime/version，表 uk_user_activity(user_id,activity_id,deleted)）、`LotteryRecord.java`（activityId/userId/costPoints/prizeCode/prizeName/orderNo）与两个空 Mapper 已存在；无任何 Service/Controller/Job。
- 规则位 `ActivityRule.bargainExpireRows`、`lotteryCostPoints` 已声明；ActivityTypes BARGAIN(13)/LOTTERY(14) 已存在；design §4.1.3（砍价分享好友砍价、底价购买、砍价专用库存）、§2.2.2（积分抽奖消耗）。

**改动模块与精确文件清单（shop-marketing-service）**

新建（包 `com.shop.marketing.activity` 下，风格对齐 groupbuy/seckill/presale）：
- `service/BargainService.java`：
  - `@Transactional Long startBargain(Long userId, Long activityId)`：读活动(type=13,status=1,时间窗) → 按 uk_user_activity 查/建 BargainRecord（origin=SKU 现价、floor=规则底价、current=origin、expire=now+bargainExpireHours，status=0）；重复发起返回既有进行中记录。
  - `@Transactional long helpCut(Long helperUserId, Long recordId)`：好友帮砍——Redisson 锁 `mk:lock:bargain:{recordId}`（DistributedLockTemplate，watchdog -1 复用既有模式）；校验活动进行中、record 未过期、help_count < bargainHelpLimit、**帮砍人不能是发起人、同一帮砍人对同一 record 仅一次**（靠新表 t_bargain_help 唯一键，见 D1 补列说明——若不新增表则复用 help 留痕，见下）；砍额 = 区间 [bargainCutMinFen, bargainCutMaxFen] 内确定性伪随机（seed=recordId+helpCount，保证重算一致、可单测），current 单调递减且不破 floor；version 乐观锁 CAS 更新；返回砍后价。
  - `@Transactional void markDealt(String orderNo, Long userId, Long activityId)`：底价成交回写 status 0→1 + orderNo（供下单成功事件或 C 端下单前校验调用；成交前必须 current<=floor 或活动允许当前价购买——按 design"底价购买"，仅达 floor 可下单，未达 floor 下单拒绝）。
  - `int expireScan()`：Job 调用，过期 status=0→2。
- `service/LotteryService.java`：
  - `@Transactional LotteryResult draw(Long userId, Long activityId)`：校验活动(type=14) 进行中、用户当日次数 < lotteryDailyLimit（Redis 计数 `mk:lottery:daily:{activityId}:{userId}:{yyyyMMdd}`，TTL 到次日；DB t_lottery_record 日计数兜底）；积分参与时先经 Feign 走既有 `UserClient` 积分链路：**优先 points/lock → points/deduct 两阶段**（/inner/user/points/lock、/deduct 已存在），deduct bizNo=`LOTTERY:{activityId}:{recordId}` 幂等；锁失败（积分不足）抛 BIZ 异常；抽奖在积分 deduct 成功后进行：按 t_lottery_prize_stock.weight 加权随机（total_stock=0 不限量；有量奖品 `issued_count` 条件 UPDATE 占库存，0 行视为该奖品已罄、降级重抽至谢谢参与）；prizeType=1 积分→`UserClient.points/grant`（bizNo=`LOTTERYPRIZE:...`，scene 按既有发放场景）；prizeType=2 券→本服务 `CouponService.issue(userId, couponId, CouponIssueWays.ACTIVITY.getCode(), "LOTTERY:"+recordId)`；prizeType=3 无动作；写 t_lottery_record（costPoints/prizeCode/prizeName）。**禁止在事务内做跨服务长链路**：积分 lock/deduct 与抽奖落库按"先 deduct 成功（独立短事务/Feign 事务边界与 order TCC 一致）再抽奖落库"组织，抽奖失败补偿 points/refund。
- `job/BargainExpireJob.java`：`@Scheduled(cron="0 */5 * * * ?")` + `@SchedulerLock(name="marketing:bargainExpire", lockAtMostFor="PT5M", lockAtLeastFor="PT30S")`，调 bargainService.expireScan()。
- `controller/BargainController.java`（`/h5/bargain`，C 端登录）：`POST /start`（activityId）、`POST /{recordId}/help`、`GET /{recordId}`（当前价/剩余时间/帮砍头像列表）。
- `controller/LotteryController.java`（`/h5/lottery`）：`POST /draw`（activityId）、`GET /prizes?activityId=`（剩余库存，谢谢参与不显示余量）。
- `mapper/BargainHelpMapper.java` + 实体 `entity/BargainHelp.java` + DDL（见下"DDL 补充"，并入 V5）。
- `mapper/LotteryPrizeStockMapper.java` + 实体 `entity/LotteryPrizeStock.java`（表 D1 已定义）：`int occupyPrize(Long id)`（`UPDATE ... SET issued_count=issued_count+1 WHERE id=? AND (total_stock=0 OR issued_count<total_stock)`）。

修改：
- `activity/support/ActivityRule.java`：补 bargainExpireHours/bargainCutMinFen/bargainCutMaxFen/bargainHelpLimit、List<LotteryPrize>（后台保存时落 t_lottery_prize_stock）、lotteryDailyLimit 字段（C27）。
- `activity/service/ActivityAdminService.java`：type=14 保存时把 prizes 写入奖品库存表；type=13 校验砍价字段完整（floor>0、floor<origin、砍价区间为正）。
- `engine/PriceEngine.java`：新增"砍价成交"计价入口语义——普通下单 item 携带 C21 `CalcItem.activityPriceFen` 时，引擎在商品级以该价为基准（活动价快照优先于 salePriceFen），并仍允许常规满减/券（砍价不属秒杀/拼团互斥类；如评审要求砍价也互斥券，在引擎开关处显式声明，规划按"可叠加店铺/平台券、不与秒杀同单"实现）。

**核心实现步骤（状态码/键/幂等）**
- BargainRecord.status：0砍价中 1已成交 2已失效；帮砍 uk(record_id,helper_user_id)；每活动每用户仅 1 条进行中（uk_user_activity 已存在，重复 start 返回旧记录不报错）。
- 抽奖幂等：积分 deduct/grant/发券全部以 bizNo/requestNo 幂等；t_lottery_record 不设强唯一（允许一人多次），日次数以 Redis+当日 count 双控。
- 砍价下单：C 端走普通下单链路（orderType=1），下单前营销试算必须校验该用户该砍价记录已达底价且未过期；支付成功后 ORDER_PAID 由营销既有 OrderPaidListener 路径之外新增轻量回写（或下单锁时 BargainService.markDealt 预占 status=1，推荐在营销 lock 阶段以 record 版本 CAS 预占，取消单不回滚成交资格——已成交未支付过期按取消处理，记录保持 1）。

**契约变更**：C21（CalcItem.activityPriceFen）、C27（ActivityRule 扩展，营销内部）；积分扣减/发放复用 UserClient 既有 9 个 inner 方法，无新契约。

**DDL**：D1 的 t_lottery_prize_stock；**补充并入 V5**：t_bargain_help（id、record_id、helper_user_id、cut_fen、create_time、UNIQUE KEY uk_record_helper(record_id,helper_user_id,deleted)、KEY idx_helper(helper_user_id)）。

**单测用例清单**
- `BargainServiceTest`：发起幂等（同用户同活动再发起返回同一记录）；帮砍价递减且不破底价；帮砍人=发起人拒绝；同一帮砍人二次拒绝；超限次拒绝；过期 Job 0→2；达底价才能 markDealt；乐观锁并发帮砍仅一次生效。
- `LotteryServiceTest`：积分不足拒绝且不写记录；积分扣减成功后中奖库存 CAS（含最后一个奖品被并发抽走时降级谢谢参与）；prizeType=1/2/3 三条发奖路径的 bizNo/requestNo 幂等；抽奖后异常积分 refund 补偿；日次数上限（Redis 丢键时 DB count 兜底）；权重分布在大样本下偏差 < 阈值。
- 引擎：activityPriceFen 生效且价格守恒。

**E2E 验收点**
1. 用户发起砍价→两位好友各帮砍一次（重复帮砍 403/业务错）→达底价→以底价普通下单支付成功，订单价=底价，记录 status=1。
2. 积分抽奖：扣积分→中奖发券（t_user_coupon 可查、requestNo 前缀 LOTTERY）/中积分（积分流水到账）/谢谢参与三分支；当日超次数拒绝；活动结束后拒绝抽奖。

**残留**：无外部系统依赖（权重抽奖为应用内实现，风控/实物奖品发货不在 design 承诺范围；奖品类型仅积分/券/谢谢参与，实物发货留扩展码不实现）。

**风险与回归面**：跨服务积分事务一致性（lock/deduct/refund 与抽奖落库的补偿顺序）、奖品库存超发（条件 UPDATE 为唯一闸门）；回归 PriceEngine 普通单计价与券叠加。

---

### 卡 B4（含 MQ P1-2）：SECKILL_EVENT 消费闭环 + 活动 ID 空锁硬失败 + 限购可配置 + 预占回补扫描 + 秒杀对账自愈

**现状证据**
- 零消费者：生产端 `SeckillService.java:228` 发 `shop_seckill_event`（tag 1锁/2扣/3释），全仓库无监听方；topic 已在 create-topics.sh:17 预建。
- 静默跳过：`inner/MarketingTxOps.java` orderType 2/3/4 三分支均带 `cmd.getActivityId() != null` 条件，null 即跳过活动资源锁定，可按普通价/普通库存下成活动单；MQ 路径 `OrderCreatedListener.java:64` 还有拼团不映射缺陷（B1 修）。
- 限购固化：V3 迁移以 uk_activity_user(activity_id,user_id,deleted) 固化每活动 1 单；`SeckillService.lock` 以 DuplicateKeyException 报 LIMIT_PURCHASE。
- 对账只告警：`job/SeckillReconcileJob`（cron `0 */15 * * * ?`，ShedLock `marketing:seckillReconcile` PT5M/PT30S）逐行比 Redis 余量与 DB `total-locked-sold`，偏差仅 warn；且 `if (redisStock == null) continue;`——**key 缺失也不重建**。实时链路仅有 release 时 RELEASE_KEY_MISSING（SeckillStockClient 常量 Long.MIN_VALUE）单键重建。

**改动模块与精确文件清单（shop-marketing-service）**

新建：
- `mq/SeckillEventListener.java`：`MqListener<SeckillEvent>`，topic `MqTopics.SECKILL_EVENT`，`tag()` 返回 `"1||2||3"`（全量），消费组 **`cg_marketing_seckill`**；经 `MqConsumeTemplate.runOnce(e.getEventId(), topic, e.getOrderNo(), ...)` 幂等。用途为**对账/监控数据闭环与修复触发**（见下），不重复执行业务扣减（业务动作已在 SeckillService 同步链路完成，消费侧只校验与修复）：
  - tag=1：登记秒杀预占监控点（t_stock_reconcile_log scope=2 心跳或内存指标，不每单落库——只在后续扫描发现悬挂时落 action=3）。
  - tag=2/3：校验 t_seckill_order 状态与事件一致，不一致落偏差日志（action=0 告警），不反向改状态（权威以同步 DB CAS 为准）。
- `job/SeckillOccupancyReclaimJob.java`（预占回补）：`@Scheduled(cron="0 */5 * * * ?")` + `@SchedulerLock(name="marketing:seckillReclaim", lockAtMostFor="PT5M", lockAtLeastFor="PT30S")`；扫 t_seckill_order status=0 且 create_time 早于 N 分钟（建议 20 分钟，长于秒杀 15min 支付超时）的记录，**经 OrderClient 既有 `GET /inner/order?orderNo=` 反查订单**：订单已取消(50)/已关闭(70) 或不存在 → 调 `seckillService.release(orderNo)`（现有 0→2 + releaseStock + Redis 释放，幂等）；订单已支付但 t_seckill_order 仍 0 → 调 `confirm(orderNo)` 追平；订单仍 10 待付款未超时 → 跳过。每行处置落 t_stock_reconcile_log。
- 自愈在 `job/SeckillReconcileJob.java` 内改造（修改而非新建，见下）。

修改：
- `inner/MarketingTxOps.java`：**删除静默跳过**——`lockInTx` 开头：orderType ∈ {2,3,4} 时 `activityId`（以及 orderType=3 的 groupbuyActivityId/groupNo）必须非空，否则 `throw new BizException(ErrorCode.PARAM_INVALID, "活动订单缺少活动 ID，拒绝按普通资源下单")`；硬失败在订单建单 TCC 编排里表现为 lockPromotion 失败→整单回滚（编排本就 fail-fast），堵住"活动普通价"旁路。order 建单侧 `OrderCreateServiceImpl.resolveActivityId` 同步加同参校验（TRADE 侧一行校验，列入对接点）。
- `activity/service/SeckillService.java`：
  - 限购可配置：规则读 `ActivityRule.perUserBuyLimit`（默认 1）。perUserBuyLimit=1 时保留现有 uk 单插入路径；>1 时锁前先 `SELECT COALESCE(SUM(qty),0) FROM t_seckill_order WHERE activity_id=? AND user_id=? AND status IN(0,1) AND deleted=0`，累计 + 本次 qty > limit 抛 LIMIT_PURCHASE；插入不再依赖 uk 作为业务闸门（uk 保留防重复下单 orderNo 维度——uk_order_no 已存在；uk_activity_user 在 N>1 场景与多行冲突，故限购 N 件的活动放开用户维度唯一约束的方式：**不做破坏性 DDL**，改为 N>1 时活动规则在保存期拒绝（V5 阶段 perUserBuyLimit 仅允许 1 或"按件数 N 且同一用户多行"——多行与 uk_activity_user 冲突）。结论：**本期可配置语义落地为"每用户可购件数"而非"可购 N 单"**：同一用户每活动仍 1 行 t_seckill_order（uk 不动），该行 qty 可为 N，下单数量校验 `qty <= perUserBuyLimit`（默认 1）；锁定/Redis/DB CAS 本就按 qty 批量，天然支持。C24 即此语义，无 DDL 破坏。
  - `lock(...)` 增加 qty 上限校验（perUserBuyLimit，默认 1，上限封顶场次库存）。
- `activity/support/ActivityRule.java`：新增 `Integer perUserBuyLimit`（C24）。
- `job/SeckillReconcileJob.java` 自愈改造：
  1. **缺键重建**：删除 `if (redisStock == null) continue;` 的跳过语义；key 缺失（currentStock 返回 null / NOT_INITIALIZED）时以 DB 为权威 `initStock` 覆盖重建（新增"强制重建"方法，见 SeckillStockClient 改动），落 action=1。
  2. **偏差自愈**：Redis 余量 != DB `total-locked-sold` 时：连续偏差计数（内存/Redis 计数器 key `mk:seckill:recon:deviation:{activityId}:{skuId}`，连续 2 个周期（30 分钟）不一致）→ 以 DB 权威**强制覆盖重建** Redis（SET 余量，Lua 原子），落 action=1 与 deviation/metric_before；重建后下一周期仍偏（真实持续漂移，可能超卖中）→ **自动暂停售卖**：CAS 置活动 status=3 已取消（经 ActivityMapper.updateStatus(1→3)，复用 B2 状态机里"进行中→已取消"合法边）+ 删除/置 SOLD_OUT Redis 键 + P1 告警日志/指标，落 action=2，等待人工介入。
  3. 自愈动作全部包 Redisson 锁 `mk:lock:seckill:reconcile:{activityId}:{skuId}`，与实时锁定/释放互斥。
- `activity/support/SeckillStockClient.java`：新增 `Long forceRebuild(Long activityId, Long skuId, long dbRemaining)`（Lua 原子 SET 余量键并刷新 sold 计数键，区别于 setIfAbsent 的 initStock；仅对账 Job 调用）；保留 NOT_INITIALIZED/SOLD_OUT/RELEASE_KEY_MISSING 哨兵语义。

**核心实现步骤（消费组/状态/键）**
- 消费组 `cg_marketing_seckill` 订阅 shop_seckill_event tag 1/2/3，eventId 落 t_marketing_mq_consume；create-topics.sh 消费组区追加该组（topic 已存在，仅加 CG 行）。
- 预占回补：订单终态判定以 OrderClient 反查为准（事件体自包含原则的例外已用既有 GET 接口，不新增契约）；release/confirm 本身幂等可安全重放。
- 自愈三级：缺键即重建（无阈值）；偏差两周期→DB 覆盖重建；重建后仍偏→暂停售卖。三级均落 t_stock_reconcile_log（scope=1）。

**契约变更**：C24（ActivityRule.perUserBuyLimit，营销内部）；无 shop-api 变更。OrderClient 复用既有 GET。

**DDL**：D1 t_stock_reconcile_log（scope 1/2）。

**单测用例清单**
- `MarketingTxOpsActivityRequiredTest`：orderType=2/3/4 缺 activityId 抛 PARAM_INVALID 且不写 t_marketing_lock；正常活动单不受影响。
- `SeckillServiceLimitTest`：perUserBuyLimit=1 下第 2 件拒绝；=3 下 1+1+1 成功、第 4 件拒绝、并发两请求累计不超 3（Redis+DB 双闸门）；默认值 1 行为不回退。
- `SeckillEventListenerTest`：tag 1/2/3 各受理一次；重复 eventId 跳过；状态不一致只告警不改状态。
- `SeckillOccupancyReclaimJobTest`：status=0 且订单 50→release；订单已支付未 confirm→confirm 追平；订单 10 未超时跳过；订单不存在（异常单）释放；ShedLock 单跑。
- `SeckillReconcileJobSelfHealTest`：key 缺失→forceRebuild；首次偏差仅计数不动作；连续两周期偏差→覆盖重建并落 action=1；重建后仍偏→活动 1→3、Redis 置 SOLD_OUT、action=2；自愈锁与业务锁互斥；DB 余量为负（超卖）时直接停售不走覆盖。

**E2E 验收点**
1. 构造 activityId 缺失的秒杀建单请求 → 建单失败回滚，普通库存不被扣。
2. 下单不支付，手工把订单置终态（或等 15 分钟超时）→ 回补 Job 周期内 t_seckill_order 0→2、Redis/DB 余量回补。
3. 手工 DEL Redis 余量键 → 下个对账周期自动重建为 DB 值；人为制造偏差并保持两个周期 → Redis 被 DB 覆盖；持续偏差 → 活动自动停售（status=3）、新下单被拒、日志/留痕表可查。
4. 限购 3 件活动：单用户下单 3 件成功、4 件拒绝。

**残留**：告警通道（P1 监控）依赖部署侧监控采集日志/指标，本地 kind 仅验证留痕表与日志文本；不停靠人工的真实超卖仲裁流程为运维残留。

**风险与回归面**：强制覆盖 Redis 方向错误会放大事故——三道门槛（DB 权威、连续两周期、重建后仍偏才停售）+ 分布式锁；回归秒杀全部现有单测（Lua 批量、补偿、RELEASE_KEY_MISSING、RateLimit 1 次/3s）、SeckillReconcileJob 现有比对逻辑。

---

### 卡 B5：预售定金膨胀计入尾款价格 + 商品侧预售库存"支付后扣减" + 定金/尾款父子单对接

**现状证据**
- 营销：`PresaleService.register` 已落 deposit/inflateDeduct/finalPay/finalPayDays(默认3)，发 DEPOSIT_PAID + CANCEL 延时（`activity/service/PresaleService.java:76,135`）；`PriceEngine` 预售仅以 presaleFinalStage 做券开关，**inflateDeductFen 不参与计价**；尾款应付字段 t_presale_order.final_pay_fen 已存但无消费方。
- 商品：`StockServiceImpl.handleOrderCreated`（:365-384）对所有类型同路径 doLock；`lockStock`/`doLock`(:87-125) 无 PRESALE 分支；confirmDeduct(:127-157) 要求先有 LOCKED 流水，预售支付后无锁定流水会抛 CONFLICT。design §3.2"预售库存支付后扣减"。
- 订单：t_order_order 有 presale_activity_id/presale_final_stage（V2:113-117）无 parent_order_no；尾款单与定金单无关联（尾款如何发起：下单链路第二期，规划按 orderType=4 + presaleFinalStage=1 的尾款单挂 parentOrderNo=定金单）。

**改动模块与精确文件清单**

shop-marketing-service（修改）：
- `engine/PriceEngine.java`：presaleFinalStage=1（尾款阶段）时，商品级先减膨胀抵扣：尾款商品应付 = 尾款原价 - inflateDeductFen（inflateDeduct 从活动规则/试算上下文取，按 item 金额占比分摊，复用引擎既有最大余数分摊工具），再允许券（仅尾款可用券，既有互斥开关保留）与积分；presaleFinalStage=0（定金阶段）按 depositFen 口径，券/积分互斥维持现状。守恒断言纳入膨胀行：`定金 + 尾款实付 + 膨胀抵扣 = 商品原价(+运费)`。
- `activity/service/PresaleService.java`：尾款营销锁（orderType=4 + finalStage）时校验 t_presale_order 存在且 status=0、当前时间在 final_start/final_end 窗内；confirm 尾款支付后 markFinalPaid 已有，补膨胀金额入锁快照 snapshot_json；register 入参增加 deposit 单 orderNo 与尾款单关联的读取支持（反查兜底：parentOrderNo 缺失时按 activityId+userId+status=0 反查 t_presale_order，找到唯一记录即关联，多记录报错）。
- `inner/MarketingTxOps.java`：orderType=4 分支按 presaleFinalStage 区分 register（定金）/尾款校验，不再同一 no-op 语义；预售 release 维持 no-op（定金买家原因不退，符合 design §4.5）。

shop-product-service（修改，B5 商品半卡，本规划一并细化，由 PRODUCT 代理实施）：
- `stock/service/impl/StockServiceImpl.java`：
  - `handleOrderCreated`(:365) 与 `lockStock`：stockType=PRESALE(2) **不执行 doLock**：仅写一条 t_product_stock_log（type=2、status 置特殊占位——为避免扩枚举，方案：预售建单**不写流水**，支付时直插 DEDUCTED 流水；幂等用 selectByUk(orderNo,skuId,2) 判定）；即 PRESALE 分支直接 return（记录幂等检查保留）。
  - `handleOrderPaid`(:388)：对预售订单（按 t_presale_order 或事件 orderType=4 推断——支付事件无 items/orderType，故 product 侧对"无 LOCKED 流水但存在预售登记"的判断需营销契约支撑；**采用更简方案**：营销在尾款/定金支付成功后已有自身事件，product 新增对 `shop_presale_event` 无依赖，改为 product 在 handleOrderPaid 时：selectLockedByOrder 为空则查是否存在 type=2 的建单占位；为此建单时仍写一条 type=2、status=0 的**占位流水但不动 available/locked**，handleOrderPaid 对 type=2 占位执行新的 `directDeduct`：`available→occupied` 条件 UPDATE（新 mapper SQL）并占位→DEDUCTED）。
  - 新增 mapper：`ProductSkuMapper.directDeductPresale(skuId,qty)` = `UPDATE ... SET available_stock=available_stock-#{qty}, occupied_stock=occupied_stock+#{qty} WHERE available_stock>=#{qty}`；库存不足（预售超卖）抛 STOCK_NOT_ENOUGH 并 P0 告警（预售库存理论上在建单期不锁，超卖只可能来自总库存被普通单耗尽——design 口径下预售货量应由商家预留，本期以支付时失败 + 告警/人工处理为残留，不做预留占压）。
  - `handleOrderCancelled`：type=2 占位行直接置 RELEASED（无库存动作，仅终结流水），与营销"定金不退"不冲突（库存从未扣）。
- product DDL：D2（留痕表）；t_product_stock_log 不加列（type=2 + status 复用）。

shop-order-service（TRADE 对接点，本卡仅声明）：
- 尾款单创建携带 C23 parentOrderNo（=定金单 orderNo）与 presaleFinalStage=1；OrderCreatedEvent/OrderItemMessage 透传；PriceCalcCommand 已有 presaleFinalStage，补透传 inflateDeductFen 所需的活动 ID（已有 presaleActivityId）。
- 定金单支付超时矩阵已为 3 天（PayTimeoutPolicy），尾款 CANCEL 延时已在 PresaleService.register 投 PRESALE_EVENT；PresaleEventListener（cg_marketing_presale_cancel，仅消费 CANCEL）既有链路保留。

**核心实现步骤（顺序）**
1. 引擎尾款计价减膨胀（营销独立可发版，旧尾款单 inflate=0 不回退）。
2. product PRESALE 占位/直扣分支与营销 register 时序对齐：建单（占位不扣）→定金支付（不扣库存）→尾款支付成功 ORDER_PAID → product directDeduct（available→occupied）→发货占用扣减仍走 WMS 既有 occupied 路径（design §3.2）。
3. 父子单关联：TRADE 落 parent_order_no（D3/C23）；营销反查兜底如上。

**契约变更**：C23（parentOrderNo）、C28（StockTypes 注释）。

**DDL**：D2（product V3 留痕表）；D3 中 parent_order_no 列（TRADE）。

**单测用例清单**
- `PriceEnginePresaleTest`：尾款价 = 尾款原价-膨胀（50 抵 100 用例）；膨胀大于尾款原价时底价 0；定金单不用券、尾款单可用券一张；定金+尾款+膨胀守恒；预售不参与积分抵现阶段开关正确。
- `StockServiceImplPresaleTest`：预售建单 available/locked 均不变且有占位流水；尾款支付成功 available→occupied（directDeduct CAS）；定金支付成功不扣库存；尾款超时取消占位→RELEASED 不动库存；并发直扣不超卖；普通/秒杀/拼团库存路径零回退。
- `PresaleServiceFinalTest`：尾款窗外拒绝、status=1 后重复支付幂等、parentOrderNo 缺失反查唯一/多记录报错。

**E2E 验收点**
1. 预售商品下单付定金：订单 10→支付后停在定金已付态、product available/locked/occupied 三列无变化、t_presale_order=0。
2. 尾款期内支付尾款：尾款价体现膨胀（50 抵 100）+ 一张尾款券；支付成功 available 减、occupied 增、t_presale_order=1；发货走既有履约。
3. 尾款超 3 天未付：PRESALE CANCEL 事件到达，订单/预售单取消，定金不退（无退款单），占位流水 RELEASED、库存三列不变。

**残留**：design 的"预售预留货量"（建单期从普通可售中预留以防普通单卖超）无对应库存列与规则，本规划按 design 明文"支付后扣减"实现，超卖防护仅支付时 CAS + P0 告警；如需硬预留需 design 补口径，列为业务决策残留（非外部系统阻塞，可二期加 presale_reserved 列）。

**风险与回归面**：预售与普通库存共用 t_product_sku 三列，分支判定错误会误扣普通单——以 stock_log.type=2 全程贯穿 + 全类型参数化单测；回归现有 handleOrderCreated/Paid/Cancelled 全部用例。

---

### 卡 B6（营销消费半卡 + 触发事件声明）：新人礼包自动发券 + 评价/晒单/分享的积分成长值触发链路

> 边界：积分/成长值**发放**在 user 服务（GAP_PLAN_USER 落地消费半卡）；本卡负责①新人礼包的**营销发券消费半卡**
> ②product 评论/分享**事件生产端**的对接声明（实施归 PRODUCT 代理，本卡给出事件契约与验收口径）。
> 注册事件生产端在 user 注册事务 outbox（GAP_PLAN_USER C48），契约 C40 topic / C41 事件类由 W0 统一落地，本规划不重复定义。

**现状证据**
- 发券能力已齐：`CouponService.issue(userId, couponId, NEW_USER.getCode(), requestNo)`（coupon/service/CouponService.java:116-163）含 NEW_USER 每人一次校验（:126-128）、库存扣减、UK(user_id,coupon_id,issue_way,request_no) 兜底；`InnerMarketingController` issue 端点（/inner/marketing/coupon/issue）已存在。
- 触发缺失：user `AuthServiceImpl.register` 不调营销、注册事务不发事件（GAP_PLAN_USER 已规划 shop_user_registered 生产）；product `CommentServiceImpl.create` 不发跨域事件；无分享端点。
- 发放能力（user 侧，已核实存在，本卡不实施）：UserClient points/grant、growth/add；GrantPointsCommand/GrowthCommand bizNo 幂等；日限 COMMENT 100（带图 +10）、SHARE 20；成长值评价 +10、晒单 +20（design §2.1.3/§2.2.2）。

**改动模块与精确文件清单（shop-marketing-service）**

新建：
- `mq/UserRegisteredListener.java`：`implements MqListener<UserRegisteredEvent>`，`topic()=MqTopics.USER_REGISTERED`（C40="shop_user_registered"），`consumerGroup()="cg_marketing_user_registered"`，`type()=UserRegisteredEvent.class`（C41：eventId/userId/registerTime/userType）。`onMessage`：
  ```
  consumeTemplate.runOnce(e.getEventId(), topic(), String.valueOf(e.getUserId()), () ->
      newUserGiftService.issueGift(e.getUserId()));
  ```
  **只发券、不发积分**（design 4.3.1 新人礼包=券包）；消费侧禁止在事务内 Feign（本消费为本地事务，无 Feign）。
- `gift/NewUserGiftService.java`：`@Transactional void issueGift(Long userId)`——查 t_coupon 中 `new_user_gift=1 AND status=1 AND issue_way=3`（D1 新列）的券模板，逐张调 `couponService.issue(userId, couponId, CouponIssueWays.NEW_USER.getCode(), "NEWUSER:" + userId + ":" + couponId)`；单张失败不影响其他张（逐张 try/catch + 失败落日志重试，或整包同事务——**按券包语义整包同事务**：任一张失败则抛错重试，幂等键保证不重发；模板库存不足跳过并 warn）。requestNo 格式 `NEWUSER:{userId}:{couponId}` 保证每人每券恰 1 张（与 :126 既有 countHeld 校验双保险）。

修改：
- `coupon/service/CouponAdminService.java`：保存券模板时支持 new_user_gift 标记写入（CouponSaveRequest 增字段由 API-M 卡一并加 @NotNull(false)/@Min(0) 校验）；上架为新人礼包券时校验 issue_way=3。

**create-topics.sh 变更（配置，随 W0 契约波）**：第 37 行消费组区行尾追加 `cg_marketing_user_registered`（topic 本身 shop_user_registered 随 C40 追加在 topic 区）。

**product 侧触发端（实施归 PRODUCT，本卡声明对接点，无 shop-marketing 代码）**
- product `comment/service/impl/CommentServiceImpl.create`：评论落库同事务 outbox 发 `MqTopics.COMMENT_CREATED`，事件 CommentCreatedEvent{commentNo,orderNo,userId,spuId,skuId,withImages(imagesJson 非空),showOrder(带图/视频晒单标记)}；bizNo=commentNo。
- product 新增分享上报端点（如 `POST /h5/share`，C 端登录，@RateLimit 防刷）：发 `MqTopics.SHARE_ACTION`，ShareEvent{userId,shareTarget,bizId,bizType}，bizNo=`{bizType}:{bizId}:{userId}:{yyyyMMdd}` 承载每日上限去重提示（日限由 user 发放端 clamp）。
- user 侧消费（GAP_PLAN_USER 范围）：新增消费组订阅 comment/share topic → PointsScene.COMMENT(3，20 分、带图 +10、日 100)/SHARE(4，10 分、日 20) 与 GrowthScene.COMMENT(2，+10)、晒单 GrowthScene.SHOW_ORDER(3，+20) + PointsScene.SHOW_ORDER(5) ；bizNo 幂等全部复用既有 GrantPointsCommand/GrowthCommand。

**核心实现步骤（消费组/幂等键）**
- cg_marketing_user_registered：t_marketing_mq_consume UK(event_id) + requestNo `NEWUSER:{userId}:{couponId}` + countHeld 三重幂等；user 服务重发/relay 重投同一注册事件不产生重复券。
- 时序：注册事务提交→outbox relay→营销消费；用户注册后极短时间内领券中心可见新人券（E2E 轮询）。

**契约变更**：C40/C41/C48 引用 GAP_PLAN_USER；COMMENT_CREATED/SHARE_ACTION 两 topic 与事件类为 C22 中仍由 W0 落地部分。

**DDL**：D1 t_coupon.new_user_gift 列（已含）。

**单测用例清单**
- `UserRegisteredListenerTest`：收到注册事件→每张新人券发一张（t_user_coupon 行数=券包模板数、issue_way=3、requestNo 前缀正确）；同一 eventId 重放零新增；同一 userId 不同 eventId（relay 重复生产）仍零新增（requestNo/countHeld 兜底）；券模板下架/库存为 0 时跳过且其他张正常；事件消费全程无 Feign 调用。
- `NewUserGiftServiceTest`：多模板部分失败整包重试后最终一致；非 NEW_USER issueWay 的模板即使误标也不发放（校验）。
- product/user 侧用例由各自规划承担；E2E 见下。

**E2E 验收点（shop-e2e）**
1. 新用户注册成功 → 轮询 `GET /coupons/my`：券包全部到账（issue_way=3）；再次人工触发同事件（测试通道）券不重复。
2. 完成订单后发表带图评价 → user 积分 +30（20+10，日限内）、成长值 +10；发纯文评价 → +20 积分/+10 成长值；带图晒单 → 成长值另 +20（晒单口径按 design：晒单 +20，与评价 +10 叠加或择一按 user 规划口径，验收以 GAP_PLAN_USER 定义为准，本卡验收事件生产正确：withImages/showOrder 标记准确）。
3. 分享上报：当日前两次 +10 积分，第三次触发日限 20 不发；分享事件 bizNo 可追溯。

**残留**：无（全部为应用内事件与既有发放能力）。

**风险与回归面**：回归 CouponService.issue 五种发放方式现有全部用例（尤其 ACTIVE_CLAIM 短 TTL 与 POINTS_EXCHANGE 不限量）；注册事件爆发（批量导入用户）时发券风暴——relay 逐条消费 + 模板库存条件扣减天然背压，不需额外处理。

---

### 卡 API-M：营销管理端/领券 API 契约硬化（A1 + M-1/M-2/M-3 + N-2 + 分页/状态机/领券校验）

**现状证据（逐项复核，行号均有效）**
- A1：7 个 GET 缺平台校验——ActivityAdminController.page(:40-47)；PromoAdminController.detail(:44-47)、levels(:49-52)、targets(:54-57)、page(:59-66)；CouponAdminController.targets(:43-46)、page(:48-55)。写操作（save/changeStatus）均已有 requirePlatformAdmin。
- M-1/N-2：ActivitySaveRequest.java:30 `List<SeckillSkuRequest> seckillSkus` 无 @Valid；:33-39 内嵌类 skuId 无 @NotNull、seckillPriceFen 仅 @NotNull（无 @Positive）、totalStock 仅 @NotNull（无 @Min）。
- M-2：CouponSaveRequest 全字段零符号注解（faceValueFen/thresholdFen/maxDiscountFen/discountBp/totalCount/perUserLimit）；targets 嵌套 List 无 @Valid/@Size。
- M-3：PromoSaveRequest.Level(:40-47) thresholdFen/reduceFen/discountBp/nthIndex/giftSkuId/giftQty 零注解；levels/targets 无 @Valid/@Size。
- changeStatus：三控制器均 `@RequestParam int status`，三 service 只 CAS 不验白名单（ActivityAdminService:70-81 等），status=99 可写入尝试（CAS 失败才报错，非法枚举不拦）。
- 分页：三个 page 直接接收 long pageNum/pageSize 透传 MyBatis-Plus，未走 PageQuery.safePageSize/safePageNum（shop-common PageQuery.java:22-28，上限 200）。
- claim：CouponCenterController.java:43-47 `@RequestBody ClaimRequest req` 无 @Valid；内联 ClaimRequest(:57-62) couponId 无 @NotNull、requestNo 无长度约束。

**改动模块与精确文件清单（shop-marketing-service，纯域内，零 shop-api 变更）**

修改：
- `activity/controller/ActivityAdminController.java`：page 首行加 `WebIdentity.requirePlatformAdmin();`；changeStatus 入参改 `@RequestParam Integer status`（包装类型 + 由状态机校验，非法抛 PARAM_INVALID）；pageNum/pageSize 改经 `PageQuery` 绑定或手工 `new PageQuery()` 后 safe 取值（上限 200）。
- `promo/controller/PromoAdminController.java`：detail/levels/targets/page 四处加鉴权；同上状态入参与分页收敛。
- `coupon/controller/CouponAdminController.java`：targets/page 两处加鉴权；同上。
- `activity/dto/ActivitySaveRequest.java`：type 加自定义枚举白名单校验（10-14，新增校验注解 `@InActivityType` 或 service 内白名单，非法 10001；推荐 Bean Validation 自定义注解放 marketing `support/validator`）；`@Valid @Size(max=200) List<SeckillSkuRequest> seckillSkus`；SeckillSkuRequest：`@NotNull Long skuId`、`@NotNull @Positive Long seckillPriceFen`、`@NotNull @Min(1) Integer totalStock`；startTime/endTime 加 `@AssertTrue` 或 service 校验 end>start。
- `coupon/dto/CouponSaveRequest.java`：name @NotBlank；type @InCouponType(1..6)、scopeType @InCouponScope(1..5)、validType @InValidType(1,2)、issueWay @InIssueWay(1..5)；faceValueFen @PositiveOrZero、thresholdFen @PositiveOrZero、maxDiscountFen @PositiveOrZero、discountBp @Min(1)@Max(1000)（注意现有默认 1000，>1000 放大折扣必须拒绝）、totalCount @PositiveOrZero、perUserLimit @PositiveOrZero；`@Valid @Size(max=500) List<Target> targets`，Target.targetType @NotNull @Min(1)、targetId @NotNull；时间窗 service 交叉校验；新增 `@Min(0) Integer newUserGift`（B6 字段，0/1）。
- `promo/dto/PromoSaveRequest.java`：type @InPromoType(1..5)、scopeType @InPromoScope；`@Valid @Size(max=50) List<Level> levels`：thresholdFen/reduceFen @PositiveOrZero、discountBp @Min(1)@Max(1000)、nthIndex @Min(2)、giftQty @PositiveOrZero、giftSkuId 与 giftQty 共存校验（service）；`@Valid @Size(max=500) List<Target> targets`。
- `coupon/controller/CouponCenterController.java`：claim 改 `@Valid @RequestBody ClaimRequest req`；内联 ClaimRequest 提为独立文件 `coupon/dto/ClaimRequest.java`：`@NotNull Long couponId`、`@Size(max=64) String requestNo`。
- 三个 AdminService.changeStatus：目标状态统一交 **B2 的 `MarketingStatusMachine.assertTransition`** 白名单（同一张迁移表，不重复造）：活动目标态 ∈{0,1,2,3}、券 ∈{0,1,2}、促销 ∈{0,1}；非法即 PARAM_INVALID。API-M 先合入时状态机类先建（仅含现有业务 status 边），B2 再扩审核态边。
- 三个 page service/controller：分页参数统一 `PageQuery.safePageNum()/safePageSize()`（pageSize 上限 200）。

**核心实现步骤**
1. 鉴权补齐：7 个 GET 首行统一加鉴权（与写操作同一行风格）。
2. 校验注解：金额/库存符号 + 折扣区间 + 枚举白名单 + 嵌套 @Valid/@Size 四组一次补齐；全局异常处理器既有 MethodArgumentNotValidException→10001 映射保持。
3. 状态机：changeStatus 不再信任入参 int；非法迁移（含 99、作废→上架）10001/CONFLICT。
4. 分页与 DTO 外提。

**契约变更**：无（纯服务内 HTTP 入参约束）。**DDL**：无。

**单测用例清单**
- 控制器层（MockMvc + 普通用户 token userType=0）：7 个 GET 各一例 403/10003；平台 token 200；写操作越权回归仍 403。
- DTO 校验：seckillPriceFen=负数/0、totalStock=0/-1、discountBp=1001/0/-5、负面额、totalCount=-1、perUserLimit=-1、type=99/0、scopeType/validType/issueWay 越界、targets/levels 超 @Size、嵌套对象非法字段——全部 10001。
- changeStatus：99/越界状态、合法迁移（活动 0→1、券 0→1→2）、非法迁移（券 2→1 作废复活、活动 2 已结束→1）被拒。
- 分页：pageSize=999999 实际查询上限被夹到 200；pageNum=0/-1 按 1 处理。
- ClaimRequest：couponId 缺失 10001；requestNo 65 字符 10001；合法请求行为不回退（与 CouponService 现有短 TTL 用例串联）。

**E2E 验收点（shop-e2e MarketingE2ETest 增补）**
1. 普通用户 token 调 7 个 admin GET 全部 403；平台账号全通。
2. 平台建券 discountBp=1200 → 10001；建秒杀负价 → 10001；status=99 上下架 → 10001；大 pageSize 不导致慢查询（响应正常、分页 size=200）。
3. 领券不带 couponId → 10001；正常领券链路不回退。

**残留**：无。

**风险与回归面**：注解加严可能拒绝历史脏数据的编辑请求（存量下架/作废模板编辑）——service 对"仅改名称等不触碰非法字段"的请求不重新全量校验库存/折扣历史值（校验仅作用于上送字段）；回归三个 AdminController 与领券全部现有 E2E。

---

### 卡 P1-1（声明卡，实施归 PRODUCT）：普通库存对账 Job + 同步释放失败留痕

**现状证据**：AUDIT_MQ_CONSISTENCY.md P1-1 属实——shop-product-service `grep -rln "@Scheduled"` 无任何结果；`OrderResourceReleaser`（shop-order-service）三个 catch 仅日志；product 已具备幂等释放原语：`releaseExisting`（StockServiceImpl.java:172-190）、`selectLockedByOrder`、`handleOrderCancelled`(:401-420)；product DDL 已备（本规划 D2 t_stock_reconcile_log）。

**对接点声明（PRODUCT 代理实施，营销/订单规划无代码）**
1. product 新建 `stock/job/StockReconcileJob.java`：`@Scheduled(cron="0 */10 * * * ?")` + `@SchedulerLock(name="product:stockReconcile", lockAtMostFor="PT5M", lockAtLeastFor="PT30S")`；
   - 扫描 t_product_stock_log status=0(LOCKED) 且创建超过 20 分钟的行：经 **OrderClient 既有 GET /inner/order** 反查——订单已 50/70 终态 → 调 `releaseExisting`（自动释放，issue_type=1/action=1）；订单不存在 → 告警 + 自动释放（issue_type=2）；订单 40 已完成却仍 LOCKED（支付事件丢失）→ 补 confirm（issue 扩展，action=2 语义复用 directDeduct/confirmExisting）；订单仍 10/20/30 活跃 → 跳过。
   - B5 预售 type=2 占位行：不纳入 LOCKED 释放扫描（库存从未锁定），单独扫描"已支付超 N 分钟仍未 DEDUCTED 的预售单"（issue_type=3，调 directDeduct 或告警）。
2. shop-order-service `support/OrderResourceReleaser.java`：三个 catch 块补 ERROR 日志 + 指标 + 失败落库（订单侧无库存表时至少落可查日志/告警表），纳入对账扫描输入（对账 Job 本身即可兜底，落库为可观测增强）。
3. 处置逐行独立短事务（参照资金规划 P2-4 教训：禁止一个大事务循环全部差异），单行失败不影响整批。

**契约/DDL**：无新契约（OrderClient GET 既有）；D2 由本规划随产品卡提供。

**单测/E2E 用例（PROXY 实施时）**：终态订单 LOCKED 自动释放、活跃单跳过、预售已付未扣补扣、单条坏数据不阻断整批、ShedLock 单节点；E2E：订单超时关单后模拟营销/库存同步释放全部失败（断 Feign），10 分钟对账周期内库存自动回补。

**残留**：无外部依赖。

---

### 卡 P2-1（仅框架/契约侧诉求，实施归 AFTERSALE）：售后超时延时消息补 eventId 与消费记录

**现状证据**：`AftersaleTimeoutMessage`（shop-aftersale-service/support/AftersaleTimeoutMessage.java）裸 POJO（aftersaleNo/kind/insuranceId），不继承 BaseEvent；`AftersaleTimeoutListener`（cg_aftersale_timeout）直接 `timeoutService.dispatch`；`AftersaleTimeoutServiceImpl.dispatch:55-66` 五个分支（KIND_AUDIT 自动同意 / KIND_RECEIVE 自动确认收货 / KIND_EXCHANGE 换货转退款 / KIND_EVIDENCE 举证关闭 / KIND_INSURANCE 运费险理赔）无 t_aftersale_mq_consume 写入（表已存在 sql/aftersale/V2__aftersale.sql:291）；幂等仅靠各业务 CAS + AftersaleTimeoutJob 60s 扫表。

**对框架/契约层的诉求（C26，本规划不实施售后代码）**
1. 消息体改为继承 `com.shop.common.model.BaseEvent`（获得 eventId/occurredAt/bizNo；bizNo=aftersaleNo 或 insuranceId）；售后生产端 `AftersaleMqServiceImpl`/AftersaleServiceImpl 的 publishDelay 调用点（审计附录：AftersaleServiceImpl.java:845、AftersaleMqServiceImpl.java:230）构造消息后由 relay 投递——延时消息同走 outbox，eventId 自动生成。
2. 监听侧改为与其他域一致的消费记录模板：dispatch 包一层"INSERT IGNORE t_aftersale_mq_consume UK(event_id) 与业务同事务"（aftersale 已有自己的 consume 模板/Mapper 时复用；没有则参照 marketing `mq/MqConsumeTemplate.runOnce`），重复 eventId 直接 ACK。
3. 框架兼容（shop-framework，归平台 W0 评估）：`MqConsumerRegistrar` 反序列化/投递链路对"历史无 eventId 消息体"做兼容——eventId 缺失时生成稳定兜底 ID（如 topic:bizNo:kind 哈希）并 warn 计数，避免切换期老延时消息 NPE/重复执行；兼容期一个版本后移除。
4. 60s 扫表双保险保留不变（消息丢失兜底）；两条路径的幂等收敛到同一 eventId/业务 CAS，不改变五类自动动作语义。

**契约变更**：C26（消息继承基类属售后域内类，但消费模型属框架约定，故在此登记）。**DDL**：无（表已存在）。

**验收口径（AFTERSALE 实施）**：同一超时消息重放两次，五类动作各只生效一次且 t_aftersale_mq_consume 有一条记录；无 eventId 的历史消息在兼容期不报错；AftersaleTimeoutJob 扫表路径回归不回退。

**风险**：切换期在途延时消息（最长 72h 运费险）为旧体——框架兜底 ID 必须稳定可重放；建议旧体到达只 warn 不拒绝。

---

## 5. 执行顺序、跨域联调矩阵与残留总表

### 5.1 波次执行顺序

| 波次 | 内容 | 前置 |
|---|---|---|
| W0（契约波，平台契约员） | C20、C21、C22（COMMENT_CREATED/SHARE_ACTION；USER_REGISTERED 引用 GAP_PLAN_USER C40/C41/C48）、C23、C25 方法签名、C26 框架兼容、C28 注释；create-topics.sh 追加 shop_comment_created、shop_share_action（shop_user_registered 随 USER 波）与消费组 cg_marketing_groupbuy、cg_marketing_seckill、cg_marketing_user_registered | 无 |
| W1（营销域内，无外部依赖可并行） | API-M（先建 MarketingStatusMachine 基表语义）→ B2（扩审核边/秒杀结束）；B4（硬失败/限购/消费组/回补）；P1-2（B4 内）；B3；B6 发券消费半卡；B1 营销侧消费组+团长计价+事件 todo | W0 |
| W2（跨域并行实施） | B5 营销尾款计价（随 W1）；product：B5 库存分支 + P1-1 对账 Job；product：B6 评论/分享事件生产；user：B6 消费发积分成长值（GAP_PLAN_USER） | W0 |
| W3（TRADE 联调波） | C25 三个 OrderClient inner 实现 + D3 订单列；OrderCreatedListener 所需 order 侧字段回填（C20/C21/C23）；B1 端到端成团续期/失败退款联调；B4 建单 activityId 同步校验；B5 尾款单父子链路 | W0、W1 |
| W4（AFTERSALE） | P2-1 消息基类化 + 消费记录 + 框架兼容窗口 | W0(C26) |

### 5.2 跨服务联调矩阵（Feign/事件对接点）

| 对接点 | 对方服务 | 现状 | 动作 |
|---|---|---|---|
| 成团/失败订单动作（续期/转待发/退款编排） | order | **不存在**，OrderClient 仅 GET /inner/order | 新增 C25 三方法（TRADE）；营销侧 cg_marketing_groupbuy + todo 表先行 |
| 团长/砍价活动价、拼团活动 ID 透传 | order→marketing（MarketingClient.calculate/lockPromotion 既有） | calculate/lock 已存在，字段缺 | C20/C21 字段随 W0；order 建单侧透传（TRADE） |
| 秒杀预占回补反查订单 | order | GET /inner/order 已存在 | 直接复用，无契约 |
| 新人礼包 | user→(MQ)→marketing | 发券能力齐备（issue NEW_USER + UK），事件缺 | cg_marketing_user_registered 消费；事件 C40/C41/C48 随 USER 波 |
| 评价/分享积分成长值 | product→(MQ)→user | 双端均缺事件/消费；发放接口 points/grant、growth/add 已存在 | product 发 COMMENT_CREATED/SHARE_ACTION（C22）；user 消费（GAP_PLAN_USER） |
| 积分抽奖扣减/发奖 | marketing→user（Feign） | points lock/deduct/release/refund/grant 全部已存在 | 直接复用，bizNo 幂等 |
| 预售库存支付后扣减 | marketing/order→product | ProductClient.lockStock/confirmDeduct/release 语义为"先锁后扣" | product 内部 PRESALE 分支，不改 Feign 签名（C28 仅注释） |
| 拼团失败退款 | order→pay | PayClient.refund（refundNo 幂等）、REFUND_SUCCESS 已存在 | 由订单域发起，营销不直连 |
| 售后超时消息 | aftersale/framework | 表 t_aftersale_mq_consume 已存在，消息无 eventId | P2-1：基类化 + 框架兼容 |
| RocketMQ 资源 | deploy | shop_groupbuy_event/shop_seckill_event topic 已建（create-topics.sh:17）；shop_user_registered/shop_comment_created/shop_share_action 待建 | W0 随契约波补 topic 与 3 个 CG |

### 5.3 残留项总表（仅列确实无法在本仓库闭环者）

| 残留 | 所属卡 | 原因与兜底 |
|---|---|---|
| 真实超卖/资金异常的人工仲裁与告警通道（监控系统对接） | P1-2/B4 | 仓库内只能落 t_stock_reconcile_log 与 ERROR/P1 日志；监控采集与告警规则属部署侧。自动停售已能阻止扩大事故 |
| 秒杀/拼团/预售活动的商家端真实商户身份（商户登录态/店铺归属注入） | B2 | 网关现仅注入平台 userType=2；商户端控制器先以预留鉴权点实现，缺身份 403，不阻塞平台审核主流程 |
| 砍价实物奖品、抽奖实物发货 | B3 | design 仅承诺积分抽奖；奖品类型限定积分/券/谢谢参与，实物留 prizeType 扩展码不实现（非外部系统阻塞，属范围外） |
| 预售货量硬预留（建单期预留防普通单卖超） | B5 | design 明文"支付后扣减"未定义预留列；按 design 实现，支付时 CAS 失败 + P0 告警兜底；若产品要硬预留需 design 补口径后二期加 presale_reserved |
| P2-1 在途旧消息兼容期外的清理 | P2-1 | 最长 72h 在途延时消息为旧体；框架生成稳定兜底 eventId，兼容一个版本周期 |
| 抽奖风控（设备/IP 反作弊） | B3 | design 未要求；仅实现日次数与积分成本闸门，风控系统为外部依赖 |

### 5.4 审计勘误汇总（结论）

1. **B1 非全空**：GROUPBUY_EVENT 生产端、24h 成团/失败判定与事件（含逐成员 type=4）、团长优惠字段（leaderDiscountFen/leaderFlag）均已落地；缺的是消费者、+30 分钟续期、团长价计价应用与订单编排。
2. **B3 非"仅空实体/Mapper"**：实体字段、唯一键、乐观锁、过期索引、ActivityRule 规则位、ActivityTypes 枚举均已就绪；缺 Service/Controller/Job 与奖品库存表。
3. **B4 审计遗漏两点**：① MQ 第二道 `OrderCreatedListener:64` 不映射拼团活动（OrderItemMessage 也缺 groupbuyActivityId 字段）；② SeckillReconcileJob 不仅"只告警"，对 Redis 缺键是直接 continue 不重建。
4. **B6 能力侧已就绪**：NEW_USER 发券一人一次、积分/成长值发放（bizNo 幂等 + 日限 clamp）、场景枚举全部存在；缺口精确为"事件生产端 + 三个消费端"，注册事件已由 GAP_PLAN_USER 统一规划，本规划只写消费半卡。
5. **B5 预售比审计多一块**：除膨胀不计价、库存下单即锁外，定金单/尾款单无父子关联（parentOrderNo 缺列），尾款链路无锚点；已补入 B5 与 D3。
6. **P1-2 topic 不缺**：shop_seckill_event / shop_groupbuy_event topic 均已在 create-topics.sh:17 预建，缺的只是消费者与 CG 行；不存在建 topic 阻断。
7. **API-M 行号全部有效**，无证伪项；但 changeStatus 非法目标态当前会在 CAS 阶段报 CONFLICT 而非被拒绝（99 不会落库但错误语义不准），硬化仍必要。
8. **P1-1 属实但修复原语齐备**：product 无需新建释放逻辑，复用 releaseExisting/confirmExisting/directDeduct 即可。





