# GAP_PLAN_USER — 用户域缺口修复规划

> 范围：仅 `shop-user-service` 与 `shop-api` 中 `com.shop.api.user` 包（含 user 侧订阅/生产所必需的 `shop-common` MqTopics 常量、`shop-api/product/event` 新事件的对接声明）。
> 只读声明：本规划不改 Java/pom/yml/SQL，不执行 mvn/docker/kubectl；所有修复为下游执行波的任务卡。跨域生产方（评价/晒单事件、新人礼包发券消费方）仅在本卡声明契约与对接点，由 W0 契约波统一落 shop-api，实现归对应域卡。
> 卡号：B6（拆 B6-a 新人礼包、B6-b 评价/晒单/分享激励）、R-B7（签到事务，已核实修复→降级测试卡）、API-U（user 侧契约硬化）。契约编号 C40–C49。DDL 从 **V3** 起（现状仅 `sql/user/V2__user.sql`）。

## 0. 勘察基线与审计勘误

| 审计口径 | 现状事实（文件:行号） | 影响 / 结论 |
|---|---|---|
| AUDIT_RESILIENCE B7：签到主入口 @Transactional 自调用失效 | **已修复**。`SignInServiceImpl.java:39-41` 注释声明 @Lazy 自注入；`:57-58` 构造器 `@Lazy SignInService self`；`:62` `sign(Long)` 走 `self.sign(userId, today)`；`:60` 已是 `@Transactional(rollbackFor = Exception.class)` | 缺陷不成立，R-B7 **降级为测试硬化卡**（ArchUnit 防回归 + 失败回滚测试） |
| AUDIT_FEATURES B6 / F-USER-11：注册无新人礼包接线 | `AuthServiceImpl.register:54-92` 仅 insert 用户 + `accountService.initAccounts`，无营销调用、无事件；营销侧 `CouponService.java:113,126-127` 已支持 NEW_USER 发券且"每人每券 1 张"，`MarketingClient.java:52` 有 `/inner/marketing/coupon/issue`，但无任何注册事件可供消费 | 缺的是**注册事件生产方**；走 MQ（不走 Feign，避免注册事务内远程调用，B5 同类反模式） |
| AUDIT_FEATURES F-USER-3：GrowthScene.COMMENT(2)/SHOW_ORDER(3) 零调用 | 枚举存在 `shop-api/.../user/enums/GrowthScene.java`；全仓除枚举/签到 SIGN_WEEK 外无 COMMENT/SHOW_ORDER 生产或调用方；`PointsScene.COMMENT=3/SHARE=4/SHOW_ORDER=5` 同理 | 评价/晒单/分享激励链路整体缺失 |
| AUDIT_FEATURES F-USER-5：评价/分享/晒单发分无业务入口 | 计算器与上限已就绪：`PointsCalc.java:26-35`（COMMENT 20/带图+10/日限100、SHARE 10/日限20）、`:107-115` clampByDailyCap；`AccountServiceImpl.grantPoints:311-345` 按 `dailyCapOf(scene)` + `t_user_points_daily` 兜底 | user 侧只需新增订阅/入口并复用 grantPoints，**不重算上限** |
| design 2.1.3：晒单 +20 成长值/单；2.2.2 积分表无晒单行 | `PointsScene.SHOW_ORDER=5` 仅有枚举，`PointsCalc` 无晒单积分常量 | **勘误**：晒单只发成长值、不发积分；规划不得超 design 造积分 |
| design 4.3.1：新人礼包=注册自动发券（每人 1 次），无新人积分 | 券模板 `Coupon.java:30` issueWay=3 新人礼包 | **勘误**：B6-a 只接券，不发新人积分 |
| AUDIT_API_CONTRACT §7.2 风险表 | 表内**无 user-service 控制器条目**；user 域唯一条目是"内部资金/积分命令 `AmountCommand.java:33`、`PointsLockCommand.java:30,34`、`PointsRefundCommand`（零注解）"——三者均在 **shop-api/user/dto** | 注解补上即生效：`InnerUserController.java:67,74,81,88,95,102,109,116,123` 已全量 `@Valid` |
| 审计 2.4 称 user C 端 DTO 已做对 | 核实：`AuthController.java:40,55,67`、`AddressController.java:33,40` 全部 `@Valid @RequestBody`；user 服务写控制器仅 Auth/Address/Inner/Admin 四个，无裸接 body | API-U 不含 C 端控制器缺口；新增分享接口必须自带 @Valid |
| 既有 user MQ 消费基建可复用 | `OrderPaidListener.java:18-37`（MqListener 模式：topic/group/type/onMessage）；`UserPointsMqServiceImpl.java:41-43` group 常量、`:52-56` `consumeService.beginConsume(eventId,topic,group,bizNo)` 幂等闸门（表 `t_user_mq_consume` UK event_id，V2:228-239） | 新 listener 照抄此模式即可 |
| 成长值/积分入账幂等 | `GrowthServiceImpl.java:47-50,63-67` bizNo 查重+UK 双保险（t_user_growth_flow uk_biz_no，V2:177）；`AccountServiceImpl.java:311-347` grantPoints bizNo UK（t_user_points_grant uk_biz_no，V2:122） | 新场景只要构造确定性 bizNo，天然幂等 |
| shop_points_changed topic | 已存在：MqTopics:53、create-topics.sh:17；由 `AccountServiceImpl` 经 outbox 发出（`:581-582`） | 复用，禁止重复造；本规划不新增积分变更 topic |

## 1. 卡总览与依赖图

| 卡 | 标题 | 类型 | 跨域 |
|---|---|---|---|
| B6-a | 注册成功自动发新人礼包（券）：user 生产注册事件，marketing 消费发券 | 功能接线 | 对接 marketing 域消费方（其卡实现） |
| B6-b | 评价/晒单/分享 → 成长值与积分入账：user 订阅评价事件 + 新增 C 端分享上报接口 | 功能/事件 | 对接 product 域评价事件生产方（其卡实现）；分享由 user 自身承接客户端 |
| R-B7 | 签到事务防回归：ArchUnit 自调用禁令 + 多表写入回滚测试 | 测试硬化（审计缺陷已修复） | 无 |
| API-U | shop-api/user 内部命令 DTO 注解硬化（@Positive/@Size），新分享 DTO 约束 | 契约硬化 | 无 |

依赖图：

```
W0 契约波（C40 MqTopics 常量 / C41 UserRegisteredEvent / C42 CommentCreatedEvent / C43-C45 DTO）
        │
        ├─ B6-a：AuthServiceImpl 事务内 outbox 发 USER_REGISTERED ──► （marketing 卡）订阅 cg_marketing_user_registered
        │       └─ 幂等：营销 CouponService NEW_USER 每人每券 1 张（CouponService.java:126-127）+ requestNo=NEWUSER:{userId}:{couponId}
        │
        ├─ B6-b：（product 卡）评价审核通过发 COMMENT_CREATED ──► user 新增 CommentCreatedListener(cg_user_comment_created)
        │       ├─ 评价：积分 COMMENT bizNo=COMMENT:{commentId}（20/带图30，日限100 已有）+ 成长值 +10 bizNo 同前缀
        │       └─ 晒单：仅成长值 +20 bizNo=SHOW:{commentId}（GrowthScene.SHOW_ORDER，无积分）
        │
        ├─ B6-b：ShareController POST /users/shares（客户端/前端在分享完成时回调）
        │       └─ t_user_share_log UK(request_no) → grantPoints SHARE bizNo=SHARE:{logId}（10/次，日限20 clamp 已存在）
        │
        ├─ API-U：shop-api/user 五个内部命令 DTO 补注解（编译期，随时可发）
        └─ R-B7：SignInServiceImpl 事务回滚测试 + ArchUnit 规则（独立，随时可发）
```

## 2. 全局契约清单（shop-api / shop-common 新增，编号 C40–C49）

> 落地波次：全部由 **W0 契约波** 先合入（shop-api、shop-common 只加不改，向后兼容），业务卡随后实现。

| 编号 | 位置 | 内容 | 消费/生产 |
|---|---|---|---|
| C40 | `shop-common/.../constant/MqTopics.java`（:59 REFUND_SHORTFALL 后）追加两个常量 | `USER_REGISTERED = "shop_user_registered"`；`COMMENT_CREATED = "shop_comment_created"` | 框架常量 |
| C41 | 新增 `shop-api/.../user/event/UserRegisteredEvent.java`（与既有 `PointsChangedEvent` 同包同风格，Serializable） | 字段：`String eventId`、`Long userId`、`Long registerTime`（epoch ms）、`Integer userType`（恒 0，留作校验）、**不含手机号/密码** | user 生产（B6-a）；marketing 消费 |
| C42 | 新增 `shop-api/.../product/event/CommentCreatedEvent.java`（product/event 包，现仅有 StockWarningEvent） | 字段：`String eventId`、`Long commentId`、`Long userId`、`Long orderNo`（评价关联单，可空）、`Long spuId`、`Integer behaviorType`（**1=评价 2=晒单**，W0 与 product 域终态确认；晒单=带图/视频的评价单由生产方按 product 现状 CommentCreateRequest 媒体字段判定）、`Boolean withImage`、`Long eventTime` | **product 域生产（对接点，非本卡实现）**；user 消费（B6-b） |
| C43 | 新增 user 服务内 DTO `com.shop.user.share.dto.ShareCompleteRequest` | `@NotBlank @Size(max=64) String requestNo`（客户端幂等号）、`@NotNull @Min(1) @Max(9) Integer targetType`（1商品 2活动 3拼团…终态 W0 定）、`@Size(max=64) String targetId`（可空） | ShareController 入参 |
| C44 | user 服务新增端点契约 | `POST /api/user/users/shares`（路径随 SignInController 同款 `/users` 前缀 + 网关路由），登录强制 X-User-Id；返回 `ShareResultVO{requestNo, pointsEarned}` | B6-b |
| C45 | shop-api/user/dto 五个既有内部命令补注解（仅加注解，方法签名不变） | 见 API-U 卡明细表 | /inner 契约硬化 |
| C46 | 消费组命名（无新类，仅常量约定） | user：`cg_user_comment_created`（仿 `UserPointsMqServiceImpl.java:41-43`）；marketing 侧：`cg_marketing_user_registered`（在 marketing 卡声明） | — |
| C47 | （声明，不实现）评价事件**生产方对接点** | product 评价审核/创建成功处（`CommentCreateRequest` 入口所在 service，W0 由 product 卡补类名/行号）发 COMMENT_CREATED，eventId 用评价幂等号；**评价被审核删除/隐藏不补发撤销事件**（成长值/积分不追回，写入残留） | product 卡 |
| C48 | （声明，不实现）新人礼包**消费方对接点** | marketing 新增 MqListener(topic=shop_user_registered, group=cg_marketing_user_registered)：查 issue_way=3 的在用券模板，逐张调 `CouponService.issue`（`InnerMarketingController.java:58` 同款内部路径），`CouponIssueCommand{userId, couponId, issueWay=NEW_USER(3), requestNo="NEWUSER:"+userId+":"+couponId}`；模板为空时 warn 跳过 | marketing 卡 |
| C49 | 分享**生产方对接点** | 无后端生产者：前端/客户端在分享动作完成（拉起面板成功/回流）时调 C44；requestNo 由客户端生成（UUID），网络重试复用同号 | 客户端，文档对接 |

**禁止项**：不新增积分变更 topic（shop_points_changed 已在用）；不在 shop-api 新建 Feign 方法发券（事件解耦，注册事务内零远程调用）。

### deploy/rocketmq/create-topics.sh 精确追加

- topic 列表段（`:11-21`）：在 `:17`（`shop_seckill_event shop_groupbuy_event shop_presale_event shop_points_changed`）之后新增一行：
  `  shop_user_registered shop_comment_created`
- 消费组段（`:34-40`）：
  - `:34` `cg_user_order_paid cg_user_order_cancel cg_user_refund` 行尾追加 `cg_user_comment_created`；
  - `:37` `cg_marketing_order_created cg_marketing_order_paid cg_marketing_order_cancelled cg_marketing_presale_cancel` 行尾追加 `cg_marketing_user_registered`。

## 3. DDL（user V3+，现状仅 V2）

新文件 `sql/user/V3__user_gap.sql`：
- 幂等风格：文件头 `DROP PROCEDURE IF EXISTS p_user_v3;` + `DELIMITER` 存储过程 + `information_schema.TABLES/STATISTICS` 判定后再 CREATE TABLE / CREATE INDEX（与既有仓库迁移波一致；V2 为裸 CREATE，V3 起按本规划要求守卫）。
- `USE shop_user;`

新增表（仅 1 张）：

```sql
-- 分享行为流水（B6-b）：客户端分享完成回调落表；request_no 为客户端幂等键
CREATE TABLE t_user_share_log (
  id            BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL,
  request_no    VARCHAR(64)  NOT NULL COMMENT '客户端幂等号',
  target_type   TINYINT      NOT NULL COMMENT '分享目标类型：1商品 2活动 3其他',
  target_id     VARCHAR(64)           DEFAULT NULL COMMENT '目标ID（可空）',
  points_earned BIGINT       NOT NULL DEFAULT 0 COMMENT '本次实际入账积分（日限 clamp 后可能为0）',
  share_time    DATETIME     NOT NULL,
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_request_no (request_no),
  KEY idx_user_time (user_id, share_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户分享行为流水';
```

不需要新表/改表的部分（勘误备查）：
- 评价/晒单积分与成长值：`t_user_points_grant`(V2:107-122 uk_biz_no)、`t_user_growth_flow`(V2:165-177 uk_biz_no)、`t_user_points_daily`(V2:130-140 uk_user_date_scene，COMMENT/SHARE 日限已由该表+grantPoints 兜底）直接复用。
- B6-a user 侧不落业务表；注册事件走**既有 outbox**——已确认：`sql/common/V3__outbox.sql:13` 的 `t_mq_outbox` 按"每个业务库一份"部署（文件头 :4-5 明示含 shop_user），user 服务 `AccountServiceImpl.java:581` 已在用，V3 无需为 outbox 加任何 DDL。

## 4. 任务卡

### 卡 B6-a：注册事件 → 新人礼包（券）

- 现状证据：`AuthServiceImpl.java:54`（register @Transactional rollbackFor=Exception）、`:88-89`（insert+initAccounts 后直接 return）；营销侧 `CouponService.java:113,126-127`、`MarketingClient.java:52`、`Coupon.java:30`；无 USER_REGISTERED topic/事件（MqTopics grep 无注册项）。
- 精确文件清单：
  - shop-common：`MqTopics.java`（C40）
  - shop-api：`user/event/UserRegisteredEvent.java`（C41，新增）
  - shop-user-service：`auth/service/impl/AuthServiceImpl.java`（注入 OutboxPublisher；register 末尾、return 前、**同一事务内** outboxPublisher.publish(USER_REGISTERED, null, event, "REGISTER:"+userId)）
  - deploy：`create-topics.sh`（§2 两处行尾）
  - 跨域（marketing 卡，不在本仓本卡提交）：C48 listener
- 核心步骤：
  1. eventId 取 "REGISTER:"+user.getId()（注册本身幂等：UK phone/username，重复注册在前置校验已拒）；outbox 同事务写入，随注册提交，无注册成功事件丢失。
  2. 严禁在 register 事务内 Feign 调营销（B5 事务内远程调用反模式）；不引入 MarketingClient 依赖。
  3. userType 固定 NORMAL（与 :83 服务端强制一致），事件中带 userType 供消费方再校验。
  4. 隐私：事件只放 userId/时间/userType，**不放手机号**。
- 单测：AuthServiceImplTest 增补——注册成功断言 outbox 收到 USER_REGISTERED 且 bizNo=REGISTER:{id}；注册冲突（重复手机号）路径断言 outbox 无写入（事务回滚）。
- E2E 黑盒验收点：
  1. 新手机号注册成功 → marketing 消费后该用户持有全部 issue_way=3 在用券模板各 1 张（marketing 侧查询接口验证）。
  2. 同用户事件重投（mqadmin 重置位点）→ 不重复发券（requestNo UK + NEW_USER countHeld 双闸）。
  3. 无新人券模板（营销侧全部下架）→ 注册/登录正常，marketing 仅 warn 日志，无 DLQ 堆积。
- 残留：发券最终一致（注册成功到券到账有秒级延迟），C 端"我的券包"需容忍空窗；失败重试/DLQ 行为随营销 listener 卡。
- 风险回归面：register 事务时长（outbox 为本地 insert，影响极小）；既有 AuthServiceImplTest mock 需补 OutboxPublisher。

### 卡 B6-b：评价/晒单/分享 → 成长值与积分

- 现状证据：零触发方（grep GrowthScene.COMMENT/SHOW_ORDER 仅枚举）；可复用 `OrderPaidListener.java` 全文模式、`UserPointsMqServiceImpl.java:52-90` 入账编排、`AccountServiceImpl.java:311-347`（日限 clamp）、`GrowthServiceImpl.java:40-70`；枚举与计算器齐备（GrowthScene、PointsScene、`PointsCalc.java:26-35,67,107-115`）。
- 精确文件清单：
  - shop-common：MqTopics（C40 COMMENT_CREATED）
  - shop-api：`product/event/CommentCreatedEvent.java`（C42，新增）
  - shop-user-service 新增：
    - `mq/listener/CommentCreatedListener.java`（implements MqListener<CommentCreatedEvent>，仿 OrderPaidListener）
    - `mq/service/UserBehaviorMqService.java` + `impl/UserBehaviorMqServiceImpl.java`（handleCommentCreated，@Transactional(rollbackFor=Exception.class)）
    - `share/controller/ShareController.java`、`share/dto/ShareCompleteRequest.java`、`share/dto/ShareResultVO.java`、`share/service/ShareService.java`+impl、`share/entity/UserShareLog.java`、`share/mapper/UserShareLogMapper.java`
  - 常量：UserBehaviorMqServiceImpl 内 `GROUP_COMMENT_CREATED = "cg_user_comment_created"`
  - sql：V3（§3）；deploy：create-topics.sh 两处
  - 跨域（product 卡）：C47 生产方
- 核心步骤（评价事件消费）：
  1. `beginConsume(event.eventId, COMMENT_CREATED, GROUP_COMMENT_CREATED, "COMMENT:"+commentId)` 闸门，重复直接 ACK。
  2. behaviorType=1 评价：`grantPoints(GrantPointsCommand{userId, bizNo="COMMENT:"+commentId, points=PointsCalc.commentPoints(withImage)=20/30, scene=PointsScene.COMMENT})`——日限 100 由 grantPoints+daily 表 clamp，超限返回 0 不报错；`addGrowth(GrowthCommand{userId, bizNo="COMMENT:"+commentId, growth=10, scene=GrowthScene.COMMENT})`。
  3. behaviorType=2 晒单：仅 `addGrowth(bizNo="SHOW:"+commentId, growth=20, scene=GrowthScene.SHOW_ORDER)`；**不发积分**（design 无此规则）。
  4. 未知 behaviorType：warn 并 ACK（不抛异常避免无限重试）；user 不存在：warn 返回（仿 handleOrderPaid:68-71）。
  5. 同一评价单既是评价又是晒单的口径以 product 事件终态为准（两条消息则两套 bizNo，天然并存）。
- 核心步骤（分享接口）：
  1. POST `/users/shares`，X-User-Id 登录态；@Valid ShareCompleteRequest。
  2. ShareService：insert t_user_share_log（DuplicateKeyException on uk_request_no → 查回原记录幂等返回旧 pointsEarned）。
  3. `grantPoints(bizNo="SHARE:"+log.id, points=PointsCalc.SHARE_POINTS(10), scene=PointsScene.SHARE)`；日限 20 clamp 为 0 时回写 points_earned=0，接口仍成功（幂等返回）。
  4. 不发放成长值（design 2.1.3 无分享成长值）。
- 单测：
  - UserBehaviorMqServiceImplTest：评价带图/不带图积分与成长值 bizNo、晒单仅 +20 成长值无 grantPoints 调用、重复 eventId 闸门 no-op、未知类型 ACK、日限打满 grantPoints 返回 0 的编排正确。
  - ShareServiceImplTest：首次发放 10、同日第 3 次起 0（clamp）、重复 requestNo 幂等返回不重复入账。
  - CommentCreatedListener 仿 OrderPaidListener 透传测试。
- E2E 黑盒验收点：
  1. product 侧造一条带图评价 → 用户积分 +30（日限未满）、成长值 +10、等级/倍率正确刷新；晒单 → 成长值 +20、积分不变。
  2. 同消息重放 2 次 → 积分/成长值不重复增加（mq_consume + bizNo UK 三重证明：t_user_points_grant/t_user_growth_flow 行数=1）。
  3. 当日评价积分超 100 后新评价 → 积分 0、成长值照常 +10（成长值无日限）。
  4. 登录态连续调分享 3 次（不同 requestNo）→ 积分 +20 封顶；同一 requestNo 重试 → 不重复加分；未登录 401。
- 残留：评价审核删除后积分/成长值不追回（无撤销事件，C47 已声明）；分享依赖客户端如实回调，可被刷但每日最多 +20 且 requestNo/t_user_share_log 留痕，风控不属本卡；targetType 白名单为软约束。
- 风险回归面：grantPoints/addGrowth 被新 scene 调用——确认 `dailyCapOf` 对 COMMENT=100、SHARE=20、SHOW_ORDER 不走发分；既有签到/消费发分链路零改动。

### 卡 R-B7：签到事务防回归（审计缺陷已修复，降级测试卡）

- 现状证据（核实通过）：`SignInServiceImpl.java:60` `@Transactional(rollbackFor = Exception.class)` 在 `sign(Long,LocalDate)`；`:62` 入口经 `self` 代理；`:57` @Lazy 构造自注入；`:122-137` 签到记录 insert 后同线程调 `accountService.grantPoints`（AccountServiceImpl:311 @Transactional REQUIRED，加入外层事务）与 `growthService.addGrowth`（同），三写同事务；UK(user_id,sign_date)（V2:158）+ grant/growth bizNo（`:159,163` 前缀 SIGN:/SIGNW:）幂等。
- 精确文件清单：
  - `shop-user-service/src/test/.../signin/SignInServiceImplTest.java`（增补）
  - 新增 `shop-user-service/src/test/.../archunit/TransactionSelfInvocationTest.java`（若模块未引入 archunit，改为反射/结构测试或在现有测试中用 Mockito 验证 `sign(Long)` 调用了 self 代理）
- 核心步骤：
  1. 失败回滚测试：mock accountService.grantPoints 抛 RuntimeException → 断言 t_user_sign_in 无当日记录、user 连续天数未增（集成测试或验证事务回滚交互），growth 未被调用。
  2. 代理生效测试：验证入口 sign(Long) 经由注入的 self（mock self 验证调用），杜绝回退为 this. 调用。
  3. ArchUnit 规则：禁止同类中一个 @Transactional 方法直接调用另一个 @Transactional 方法（覆盖 AUDIT_RESILIENCE:218 建议；user 域重点即签到）。
- E2E 黑盒验收点：连续两日签到积分/连签/成长值正确；重复签到幂等返回（既有行为不回归）。
- 残留（记录不改）：Redisson 锁在事务方法内部（`:64` lockTemplate.execute 在 @Transactional 方法体内），锁先于事务提交释放，极端并发下另一线程可能在提交前读旧状态——由 applySignIn CAS（`:91` rows==0 回查）与 UK 兜底，现状安全，仅注释提示。
- 风险回归面：无生产代码变更；若未来有人移除 @Lazy self 构造参数，编译即失败。

### 卡 API-U：内部命令 DTO 契约硬化

- 现状证据：InnerUserController 九处入口全部 @Valid（`:67-123`），但 DTO 注解缺失——`PointsRefundCommand.java:26-32` 零注解；`AmountCommand.java:37-38` amountFen 仅 @NotNull；`PointsLockCommand.java:36-41` points/deductFen 仅 @NotNull；`PointsDeductCommand.java:26-31`、`PointsReleaseCommand.java:26-31` 仅 userId/bizNo；列宽 biz_no VARCHAR(64)（V2:70,110,168），无 @Size。
- 精确文件清单（均在 shop-api/.../user/dto/）：
  | 文件 | 增补注解 |
  |---|---|
  | PointsRefundCommand.java | userId @NotNull @Positive；bizNo @NotBlank @Size(max=64)；points @NotNull @Positive |
  | AmountCommand.java | amountFen 补 @Positive（借记/贷记均为正向金额，方向由端点决定）；bizNo 补 @Size(max=64)；remark @Size(max=128) |
  | PointsLockCommand.java | points @PositiveOrZero（无积分下单允许 0）；deductFen @PositiveOrZero；bizNo @Size(max=64)；scene 保持 @NotNull 现状或补枚举校验 |
  | PointsDeductCommand.java | bizNo @Size(max=64)（points 来自冻结记录，不新增字段约束） |
  | PointsReleaseCommand.java | 同上 |
  | GrantPointsCommand.java / GrowthCommand.java（已较好） | bizNo 补 @Size(max=64) 对齐列宽；scene @Min(1) 可选 |
- 核心步骤：纯加注解；message 沿用中文风格；服务层既有手工校验保留（纵深防御，不退化为仅靠注解）。
- 单测：InnerUserController 注解生效测试（points/refund 传 null/负数/65 位 bizNo → 参数异常 Result，而非走到服务层）；正常内部命令回归通过。
- E2E 黑盒验收点：内部令牌 + 非法 body → 10001/参数错误（非 500、非 DataIntegrityViolation）；现有下单抵现/退款退分全链路回归无破坏。
- 残留：scene 为 Integer 而非枚举（跨服务序列化惯例），不做枚举类型改造；非法 scene 仍由服务层拒绝。
- 风险回归面：仅校验加严，历史正常调用方全部传正值，理论无破坏；order/aftersale 既有调用在 W0 合入后跑一遍集成回归。

## 5. 执行顺序

1. **W0 契约波（先行，独立可合）**：C40（MqTopics 两常量）、C41 UserRegisteredEvent、C42 CommentCreatedEvent、C45 五个 DTO 注解；create-topics.sh 两处追加随契约波或部署波合入。
2. **V3 DDL**：t_user_share_log 建表（outbox 表 t_mq_outbox 已确认覆盖 shop_user，无需重建）。
3. **B6-a**：AuthServiceImpl outbox 发事件 + 单测；同步向 marketing 域交付 C48 对接声明（其 listener 卡并行排期；user 侧可先独立上线，事件无消费者时仅落 topic）。
4. **B6-b**：先等 product 域 C47 生产方合入（或与其同波）→ user 侧 CommentCreatedListener + UserBehaviorMqService；ShareController 链路独立，可与上者并行。
5. **R-B7 / API-U**：纯测试/注解，任何时间点独立合入，建议随 W0 同波清掉。
6. 回归门禁：user 全量单测（现存 11 个测试类）+ 签到/注册/下单抵现/退款退分 E2E 主链路；新 topic 上线前确认 broker 已执行 create-topics.sh（防 autoCreateTopic 关闭导致投递失败，参照 AUDIT_MQ_CONSISTENCY P0-1 教训）。
