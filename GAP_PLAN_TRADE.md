# GAP_PLAN_TRADE — 订单/商品域缺口修复规划

> 范围：shop-order-service、shop-product-service、shop-api（order/product 包）、shop-common 对应包；卡 B1-订单侧 / B5-库存侧 / B7 / B13 / P1-1 / API-T（order/product 部分）。
> 本文件为**只读规划**：不改 Java/pom/yml/SQL，不执行 mvn/docker/kubectl。所有类名、方法签名、状态码、topic 均以现状代码为证；标注"实施时以现状为准"处为跨卡对接面，由 W0 波次统一对齐，不在本卡内反向勘察。
> SQL 基线：order 现状 V2（FUNDS 卡已占 V3，本规划 order DDL 从 **V4** 起）；product 现状 V2，本规划从 **V3** 起；common 现状 V3/V5。

## 0. 勘察基线与审计勘误

| 审计口径 | 现状事实（文件:行号） | 影响 / 处置 |
|---|---|---|
| B1：GROUPBUY_EVENT 全仓无消费者（AUDIT_FEATURES.md:57,186） | 生产端 `shop-marketing-service/.../activity/service/GroupbuyService.java:184-191`（outbox 发 GROUPBUY_EVENT）；事件契约 `shop-api/.../marketing/event/GroupbuyEvent.java`（type 1开团/2参团/3成团/4失败，orderNo+groupNo 幂等）；order 域 mq 目录仅 6 个 Listener（RefundSucceeded/OrderPaid/AftersaleChanged/PayTimeout/AutoConfirm/AftersaleWindow），无 groupbuy 消费者；create-topics.sh:11-21 有 topic 但 :34-40 无对应消费组 | 审计成立，无需降级 |
| B1：成团后 30 分钟支付续期无代码（F-ORD-8，AUDIT_FEATURES.md:74） | `shop-order-service/.../policy/PayTimeoutPolicy.java:28-38` 拼团统一 24h，无成团后续期分支；t_order_order 有 expire_time 列（V2__order.sql:94） | 审计成立 |
| B1：团长优惠仅字段无计价（AUDIT_FEATURES.md:57） | `OrderCreateServiceImpl.java:521-527` resolveUnitPrice 仅秒杀取 seckillPriceFen，拼团回落 salePriceFen；t_order_order.price_snapshot 列已存在（V2__order.sql:111） | 团长优惠须由营销试算结果服务端落快照，订单侧不自行计价（对接 MARKETING 卡，W0） |
| B5：预售库存下单即锁（F-PROD-16，AUDIT_FEATURES.md:43） | `shop-product-service/.../stock/service/impl/StockServiceImpl.java:365-384` handleOrderCreated 对 type=2 预售与普通同路径 doLock；同步入口 lockStock 同逻辑（:85-96）；:466-473 仅做类型映射 | 审计成立 |
| F-PROD-8：第四仓是残次仓非预售，无预售库存数量 | `ProductSku.java:70-88` 四列为 available/locked/occupied/defect，presaleFlag+stockType 仅标记 | B5 必须新增 presale_stock 数量列，不能复用 occupied 语义硬凑 |
| B7：运费客户端上送默认 0（AUDIT_FEATURES.md:72,192） | `CreateOrderRequest.java:40` freightFen 默认 0 且无任何服务端覆写；PriceEngine 仅 applyFreight 分摊入参运费（`shop-marketing-service/.../engine/PriceEngine.java:421-435`），无模板取价 | 审计成立 |
| B13：库存无"发货扣减"节点（AUDIT_FEATURES.md:198） | ORDER_SHIPPED 已由 `OrderPersister.java:89-97` 同事务发出；但 product 域 stock/mq 仅 4 个 Listener（Created/Paid/Cancelled/Aftersale），create-topics.sh:34-40 无 cg_product_shipped；t_product_stock_log 状态注释仅 0锁定/1扣减/2释放/3回库（V2__product.sql:138） | 审计成立：占用仓不出账 |
| P1-1：product 域无任何 @Scheduled 对账（AUDIT_MQ_CONSISTENCY.md:42-52） | product 源码无 stock 对账任务；乱序窗口实证 StockServiceImpl.java:401-418（无 LOCKED 流水即跳过、消费记录已落）；order 侧吞异常 `OrderResourceReleaser.java:33-69` 三步 catch 仅 log | 审计成立；ShedLock 已在 order 域使用（`PayTimeoutScanJob.java:33` net.javacrumbs @SchedulerLock，父 pom 管理），product 域引锁提供方时以 order 现状配置为准 |
| API-T：orderType/source 非法值"静默按普通单落库"（AUDIT_API_CONTRACT.md:287 表行，OrderCreateServiceImpl.java:527-543） | **部分证伪**：stockTypeOf（:540-543）/resolveActivityId（:529-535）default 分支确按普通单；但 `PayTimeoutPolicy.java:30-38` 对未知 orderType 抛 PARAM_INVALID。下单链路是否在落库前调用 expirePaySeconds 决定非法值能否真正落库——实施时先补单测证伪：若前置已抛错，则本项性质降为"错误码/400 契约一致性"；白名单仍须显式化 | 行号有效，结论需单测确认严重度；不取消白名单改动 |
| API-T：CreateOrderRequest 注解行 :25,28,33,40,43,64 | 现状（CreateOrderRequest.java:22-61）：orderType 仅 @NotNull（:24-25）、source 无注解（:27-28）、items 仅 @NotEmpty+@Valid（:31-33）、freightFen/usePointsFen 无非负注解（:39-43）、fromCartIds 无 @Size（:59-60） | 行号微偏（字段声明行），事实成立 |
| API-T：内部库存命令/IN 查询无 @Size | `shop-api/.../product/dto/StockLockCommand.java:37-39`、`StockDeductCommand.java:37-39` items 仅 @Valid 无 @Size；`ProductInnerController.java:43-46` @RequestBody List<Long> 裸参；`AdminGoodsController.java:50-56` violation remark 为 @RequestParam 无 @Size（类上需 @Validated 才生效） | 审计成立 |
| API-T：SpuSaveRequest skus/detailJson/attrsJson/images | `SpuSaveRequest.java:38-54`：mainImage 有 @Size(512)；images（:43）无元素约束、detailJson（:46）/attrsJson（:49）无大小约束、skus（:53-54）无 @Size | 审计成立 |
| API-T：CartSelectRequest.ids | `CartSelectRequest.java:16` List<Long> ids 无 @Size；selected 已 @NotNull | 审计成立 |

> 行号失效处置：以上行号为 2026-09-17 现状基线。实施时若行号漂移，以"类名+方法名/字段名+审计语义"重新定位并在 PR 描述中更新；若现状已被其他卡修复（注解已存在/消费者已存在），该子条直接关闭并在 ACCEPTANCE 记录证伪，不得重复改动。

## 1. 卡总览与依赖图

```
                     W0 契约对齐波（跨卡，不单独交付业务能力）
        ┌──────────────────────────────────────────────────────────┐
        │ C30 GROUPBUY 消费契约 / C31 支付事件预售字段(向后兼容新增) │
        │ C33 订单状态批量查询 / C37 试算返回团长优惠字段 / 退款原语  │
        └──────────────────────────────────────────────────────────┘
             │ 事件字段以 shop-api 与 MqTopics 常量为准；退款以 FUNDS 卡为准
   ┌─────────┼───────────────────────────────┐
   ▼         ▼                               ▼
[API-T]   [B7 运费模板+服务端取价]      [B13 类目挂载/规格主数据/发货出账]
 契约硬化   product V3 DDL                product V4 DDL（部分）+ cg_product_shipped
   │         │ 试算与建单均调 product       │ 占用仓出账 status=4
   │         ▼                               ▼
   │    PriceCalc(MARKETING 卡) ◄────── [B5 预售两阶段库存]
   │         免邮券/试算编排             presale_stock 列、定金扣/尾款回补
   │                                      │
   │                                      ▼
   └──────────────────────────────► [P1-1 库存对账 Job] ◄── C33 订单状态
                                          ▲
                       [B1 拼团订单流转]───┘ 失败退款依赖 FUNDS 退款原语

[C-COMMENT 评价/晒单事件生产]（product 域，B13 相邻小卡）── shop_comment_created ──► USER(cg_user_comment_created，GAP_PLAN_USER C40/C42/C47)
```

先后说明：
- **API-T** 零外部依赖，最先做，给后续卡提供 @Size/@Validated 基座（C35/C36 常量与注解先行合入）。
- **B7** 仅依赖 product V3 DDL 与 C32；营销试算 PriceCalc 的调用编排属 MARKETING 卡，订单/商品卡只保证"取价能力存在、建单不信任客户端"。
- **B13 发货出账**与 **B5** 共用 product V4 库存列/状态码/StockServiceImpl 改造，同波次合并实施避免流水状态机二次返工。
- **P1-1** 必须在 B5 之后：预售新增状态与两阶段流水后，对账 Job 的状态机判定才完整。
- **B1** 最后：消费者逻辑依赖 C30/C31/C37 对齐与 FUNDS 退款原语；topic 已存在（create-topics.sh:19 shop_groupbuy_event），仅需补消费组。
- 跨文件依赖：MARKETING（GroupbuyService 生产端字段、PriceCalc 试算编排、PresaleFinalJob）、FUNDS（拼团失败/预售尾款违约的退款原语、运费险保费，本规划只引用不实现）、PLATFORM（@SchedulerLock 提供方/告警指标通道复用现状）、USER（无直接改动；积分回退沿 ORDER_CANCELLED 既有链路；**评价/晒单成长值事件生产半在本域，见 §4.6 C-COMMENT，消费半与 topic/消费组脚本归 GAP_PLAN_USER**）。

## 2. 全局契约清单（shop-api / shop-common）

> 原则：所有新增字段一律包装类型（Boolean/Integer/Long）+ null/默认空值语义，禁止改既有字段类型与必填性；新枚举仅追加常量。MQ 事件名/tag/消费组以 `shop-common`（或 shop-api）中 MqTopics 现状常量为唯一字面量来源，代码中不允许硬编码字符串。

| 编号 | 变更 | 兼容性 |
|---|---|---|
| **C30** | GROUPBUY_EVENT 订单侧消费契约：topic=`MqTopics.GROUPBUY_EVENT`（shop_groupbuy_event，已存在），tag=GroupbuyEvent.type 字符串（"3"/"4"），新消费组 `cg_order_groupbuy`；幂等键 = consume_record(topic, eventId)，业务推进幂等键 `groupNo + "#" + type`；只消费 type=3/4，1/2 落消费记录后 no-op。事件字段以 `GroupbuyEvent.java` 现状（activityId/groupNo/userId/orderNo/type/requiredPeople）为准，生产端是否逐团员发事件 W0 与 MARKETING 卡对齐；消费端对"单事件/批量事件"均以 groupNo 回查全团订单兜底 | 纯新增消费者 |
| **C31** | `PaymentSucceededEvent` 追加 `Integer orderType`、`Boolean presaleFinalStage`（包装类型，null 视为普通单/历史消息）；OrderShippedEvent/OrderCancelledEvent 字段以现状为准，B5/B13 仅消费不改结构；如发现 ORDER_CANCELLED 缺 orderType，同规则追加可空字段 | 向后兼容新增 |
| **C32** | shop-api 新增 `FreightCalcRequest`（orderNo 可空、List<FreightItem>{skuId,qty}、province/city/district 或 addressId 二选一）与 `FreightCalcResponse`（freightFen、规则快照 JSON）；`ProductClient` 追加 `POST /inner/freight/calc`（@Valid @RequestBody）。服务端取价为唯一权威口径 | 纯新增 |
| **C33** | OrderClient 新增订单状态批量查询：`POST /inner/orders/status`（@RequestBody List<OrderNoKey> 或 List<String>，@Size(max=100)），返回 Map<orderNo,{status,orderType,presaleFinalStage,gmtCreate}>。若 order 服务已有等价 inner 端点（实施时以现状为准）则仅补 shop-api Feign 方法，不重复建端点 | 纯新增 |
| **C34** | 发货出账契约：消费既有 topic `MqTopics.ORDER_SHIPPED`（shop_order_shipped，已存在），新消费组 `cg_product_shipped`；库存流水新增状态码 4=已出账（占用仓出账）、5=预售回补（定金扣减后尾款违约回补预售池），常量加在 shop-api product 库存状态枚举（StockLockStatuses 或等价类，以现状包名为准），仅追加 | 追加枚举 |
| **C35** | shop-common 新增 `BatchSizes` 常量类（ITEMS_MAX=100、CART_IDS_MAX=100、SKUS_MAX=100、IN_IDS_MAX=100、IMAGES_MAX=10）供 Bean Validation 编译期常量引用；shop-api `StockItemCommand` 补 @NotNull skuId、@Positive/@NotNull qty；StockLockCommand/StockDeductCommand/StockReleaseCommand items 补 `@Size(min=1,max=BatchSizes.ITEMS_MAX)` | 注解新增，旧非法大报文由穿透改为 400 |
| **C36** | shop-common 新增媒体 URL 约束 `@MediaUrl`（或 @Pattern+@Size(512) 组合常量）：仅 http/https、单条 ≤512；作用于 List 时配合 `@ListMediaUrl`（元素校验 + 列表 @Size(max=10)）。先落 product 域（SpuSaveRequest.mainImage/images），order 域无媒体字段，不扩散 | 纯新增注解 |
| **C37** | 营销试算响应（PriceCalc/TrialCalcResult，类名以 MARKETING 卡现状为准）对拼团单追加可空字段 `Long leaderPriceFen`、`Boolean leaderFlag`；订单建单以试算结果写入 price_snapshot，客户端上送价不参与（与 B7 同一服务端定价原则）。字段名/位置 W0 对齐 | 向后兼容新增 |
| **C38** | 库存类型/阶段契约：StockTypes.PRESALE=2 已存在（StockServiceImpl.java:466-473），不新增；预售语义改由 orderType + presaleFinalStage（C31）驱动；StockLog 追加 `refOrderNo`（尾款流水回指定金流水 orderNo，可空）随 C34 状态码一同落地 | DDL+DTO 可空新增 |
| **C39** | deploy/rocketmq/create-topics.sh 精确追加（**本规划仅追加消费组**）：GROUPS 数组（:34-40 区块）末尾按现有风格追加两行 `cg_order_groupbuy`、`cg_product_shipped`；幂等性由脚本既有 updateSubGroup 语义保证，发布前本地 bash -n 校验。**TOPICS 数组本卡不追加**：shop_groupbuy_event 已在 :19、shop_order_shipped 已在 :11；评价 topic `shop_comment_created`（create-topics.sh:17 后追加）及其消费组 `cg_user_comment_created` 由 GAP_PLAN_USER 统一落（W0 协调，禁止两规划各改一行） | 部署脚本新增 |

## 3. DDL 清单

> 风格：建表用 `CREATE TABLE IF NOT EXISTS`（同 V2 现状）；存量表加列/加索引一律 `DROP PROCEDURE IF EXISTS ...` + `information_schema` 守卫 + `CALL` + `DROP PROCEDURE` 的幂等写法。字符集/引擎与 V2 对齐（utf8mb4 / InnoDB）。

### 3.1 order 域：无 DDL（不产生 V4 文件）

- B1 全部诉求可由现状列承载：`group_no`（V2__order.sql:114）、`expire_time`（:94）、`status`（:78，10/20/50 等）、`price_snapshot`（:111）、`cancel_type`（:104）。
- `cancel_type` 追加枚举值 **4=拼团失败**：TINYINT 无需变更，仅在 shop-api 常量类追加常量；列注释漂移在后续 order DDL（其他卡）中顺手修正，本规划不为注释单独发版。
- 文件号 V4 空置保留：若 W0 对齐发现事件字段必须落库（如尾款流水关联），优先复用 product V4 的 ref_order_no，不占 order V4；任何 order V4 新增必须在本文件追加登记，避免与 FUNDS 的 V3 之后编号冲突。

### 3.2 product V3__freight_template.sql（B7）

```sql
-- 运费模板（商家维度，一个店铺可多模板，一个默认模板）
CREATE TABLE IF NOT EXISTS t_product_freight_template (
    id              BIGINT       NOT NULL COMMENT '模板 ID（雪花）',
    merchant_id     BIGINT       NOT NULL COMMENT '商家 ID',
    name            VARCHAR(64)  NOT NULL COMMENT '模板名称',
    charge_type     TINYINT      NOT NULL DEFAULT 1 COMMENT '计费方式：1 按件 2 按重量 3 按体积',
    default_first   INT          NOT NULL DEFAULT 1 COMMENT '默认首件数/重(g)/体积(cm³)单位数',
    default_first_fee   BIGINT  NOT NULL DEFAULT 0 COMMENT '默认首费（分）',
    default_add     INT          NOT NULL DEFAULT 1 COMMENT '默认续件单位数',
    default_add_fee BIGINT       NOT NULL DEFAULT 0 COMMENT '默认续费（分）',
    free_condition_fen  BIGINT  NOT NULL DEFAULT 0 COMMENT '满额包邮门槛（分），0 不包邮',
    is_default      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否店铺默认：0 否 1 是',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '0 停用 1 启用',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_merchant_status (merchant_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运费模板';

-- 区域运费规则（可配送区域 + 指定区域费率；省/市/区县编码列表 JSON）
CREATE TABLE IF NOT EXISTS t_product_freight_region (
    id              BIGINT       NOT NULL COMMENT '规则 ID（雪花）',
    template_id     BIGINT       NOT NULL COMMENT '运费模板 ID',
    merchant_id     BIGINT       NOT NULL COMMENT '商家 ID（冗余）',
    region_codes    JSON         NOT NULL COMMENT '适用行政区划编码列表（省/市/区，编码以 USER 地址字典为准）',
    first_unit      INT          NOT NULL COMMENT '首件单位数',
    first_fee_fen   BIGINT       NOT NULL COMMENT '首费（分）',
    add_unit        INT          NOT NULL COMMENT '续件单位数',
    add_fee_fen     BIGINT       NOT NULL COMMENT '续费（分）',
    deliverable     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否可配送：0 不可配送(拒单) 1 可配送',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_template (template_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='区域运费规则';
```
说明：SKU/SPU 与模板绑定先用店铺默认模板（is_default=1）；如 MARKETING/商品卡 W0 决议需要 SPU 级模板，在 product 后续版本加 `t_product_spu.freight_template_id`（本规划不提前发列）。

### 3.3 product V4__catalog_stock.sql（B13 + B5 + P1-1）

```sql
-- 1) 虚拟类目多二级挂载：SPU 与类目多对多（主挂载仍由 t_product_spu.category3_id 表达）
CREATE TABLE IF NOT EXISTS t_product_spu_category (
    id            BIGINT   NOT NULL,
    spu_id        BIGINT   NOT NULL COMMENT 'SPU ID',
    category_id   BIGINT   NOT NULL COMMENT '挂载类目 ID（通常为二级虚拟类目，一级也允许）',
    category_level TINYINT NOT NULL COMMENT '挂载类目层级冗余：1/2/3',
    mount_type    TINYINT  NOT NULL DEFAULT 1 COMMENT '挂载类型：1 实体归属 2 虚拟挂载',
    create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_spu_category (spu_id, category_id),
    KEY idx_category (category_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SPU-类目多挂载关系（虚拟类目）';

-- 2) 规格/属性主数据（类目属性模板的可治理版本；attrsJson 继续做商品快照）
CREATE TABLE IF NOT EXISTS t_product_attr_key (
    id            BIGINT      NOT NULL,
    category_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '所属类目 ID，0=全局通用属性',
    name          VARCHAR(64) NOT NULL COMMENT '属性名（颜色/尺码/材质…）',
    attr_type     TINYINT      NOT NULL COMMENT '属性类型：1 关键属性 2 销售规格(SKU) 3 普通属性(SPU)',
    value_type    TINYINT      NOT NULL DEFAULT 1 COMMENT '值类型：1 枚举 2 数值 3 文本',
    value_options JSON         DEFAULT NULL COMMENT '枚举可选值列表',
    unit          VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '数值单位',
    required_flag TINYINT      NOT NULL DEFAULT 0 COMMENT '是否必填',
    sort          INT          NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 1,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (category_id, name, deleted),
    KEY idx_category_type (category_id, attr_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='规格/属性主数据';

-- 3) B5：预售库存数量列（现状四仓无预售数量，ProductSku.java:70-88）
DROP PROCEDURE IF EXISTS p_product_add_presale_stock;
DELIMITER //
CREATE PROCEDURE p_product_add_presale_stock() BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_product_sku'
        AND COLUMN_NAME = 'presale_stock') THEN
    ALTER TABLE t_product_sku
      ADD COLUMN presale_stock BIGINT NOT NULL DEFAULT 0 COMMENT '预售库存数量（定金支付后扣减池）' AFTER occupied_stock;
  END IF;
END //
DELIMITER ;
CALL p_product_add_presale_stock();
DROP PROCEDURE p_product_add_presale_stock;

-- 4) B13/B5：流水追加关联单号；状态注释扩到 4 已出账 / 5 预售回补
DROP PROCEDURE IF EXISTS p_product_stock_log_add_ref;
DELIMITER //
CREATE PROCEDURE p_product_stock_log_add_ref() BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_product_stock_log'
        AND COLUMN_NAME = 'ref_order_no') THEN
    ALTER TABLE t_product_stock_log
      ADD COLUMN ref_order_no VARCHAR(32) NULL COMMENT '关联单号（尾款流水回指定金单号）' AFTER order_no,
      MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0
        COMMENT '流水状态：0 锁定中 1 已扣减(入占用) 2 已释放 3 已回库 4 已出账(占用仓出账) 5 预售回补(尾款违约)',
      ADD KEY idx_status_time (status, create_time);
  END IF;
END //
DELIMITER ;
CALL p_product_stock_log_add_ref();
DROP PROCEDURE p_product_stock_log_add_ref;
```

> 守卫注意：第 4 段将"加列/改注释/加索引"放在同一存在性判断内；若实施库出现 ref_order_no 已加但索引缺失的中间态，拆成两个独立 procedure（分别守卫 COLUMN 与 STATISTICS）。DELIMITER 语法以本仓库现有迁移执行方式为准（若迁移器不支持 DELIMITER，按 sql/common V3__outbox.sql 既有幂等风格改写）。

## 4. 任务卡

### 4.1 B1-订单侧：GROUPBUY_EVENT 订单流转

**对接点声明（W0 统一对齐，本卡不勘察营销域）**：消费/生产的事件名与字段以 shop-api 常量与 `MqTopics` 现状为准——topic `shop_groupbuy_event`、tag=type、`GroupbuyEvent`（activityId/groupNo/userId/orderNo/type/requiredPeople）；生产端（GroupbuyService.java:184-191）在成团/失败时是"每团员一事件"还是"团一事件"由 W0 确认，消费端两种形态都按 groupNo 回查全团订单，不依赖 event.orderNo 覆盖全团。玩法判定（人数档/24h/限购/成团与否）100% 归营销规划，订单侧不复制规则。

**现状证据**
- 生产：GroupbuyService.java:184-191；契约：GroupbuyEvent.java + GroupbuyOpType.java（1 OPEN/2 JOIN/3 SUCCESS/4 FAIL）。
- 无消费者：shop-order-service mq 包仅 6 个 Listener（见 §0）。
- 超时：PayTimeoutPolicy.java:28-38（GROUPBUY=24h，无 +30min 分支）；PayTimeoutScanJob.java:32-51 为 DB 扫描双保险。
- 计价：OrderCreateServiceImpl.java:521-527 拼团取普通价；订单列 group_no（V2:114）、expire_time（V2:94）、price_snapshot（V2:111）。
- 库存/资源释放：OrderResourceReleaser.java:33-69（取消时同步释放，失败仅 log）。

**精确改动文件清单**
1. 新增 `shop-order-service/.../mq/GroupbuyEventListener.java`（仿 PayTimeoutListener 写法，consumerGroup=`cg_order_groupbuy`，topic/常量取自 MqTopics）。
2. 新增 `shop-order-service/.../order/service/GroupbuyOrderFlowService.java`（+Impl）：`onGroupSuccess(groupNo, event)`、`onGroupFail(groupNo, event)`。
3. 改 `OrderMapper`（+XML/注解以现状为准）：新增 `selectByGroupNo(groupNo)`、`batchUpdateExpireForUnpaid(groupNo, deadline, version)`。
4. 改 `PayTimeoutPolicy`：新增 `groupSuccessExtendSeconds()=DELAY_30_MIN_SECONDS`；不改既有 24h 分支。
5. 改建单链路拼团计价注入点（OrderCreateServiceImpl 组装价快照处 / MarketingTxOps.java:53-60 附近）：以试算响应的 leaderPriceFen/leaderFlag（C37）写 price_snapshot，**实施时以现状价格快照组装代码为准**。
6. shop-api：`OrderCancelType`（或等价常量类，以现状为准）追加 `GROUPBUY_FAIL=4`。
7. deploy/rocketmq/create-topics.sh：GROUPS 追加 `cg_order_groupbuy`（C39）。

**核心步骤**
- SUCCESS（幂等键 groupNo#3）：① selectByGroupNo 捞全团订单；② status=10 待付款订单：expire_time 统一延至事件到达时刻 +30min（条件更新 `status=10 AND expire_time<newDeadline` 防重复续期），并按现有延时消息机制重发 `ORDER_PAY_TIMEOUT`（30min 档，bizNo=orderNo，PayTimeoutListener 以库内 expire_time 为最终准）；③ status=20 待发货订单不动（支付成功链路本已转 20，即 design"成团成功转待发货"）；④ 非 10/20 状态跳过。
- FAIL（幂等键 groupNo#4）：① status=10 订单：按超时取消同路径关单 status→50、cancel_type=4，释放资源走既有 OrderResourceReleaser（券/积分本不允许拼团使用，仅库存），库存释放复用 ORDER_CANCELLED 既有 outbox；② status=20 已支付订单：转自动退款——调用**资金卡退款原语**（refundNo 规则、退款单落库、pay 侧幂等均以 FUNDS 卡 W0 对齐为准；订单侧只发一次退款请求并以 refundNo 幂等，禁止自建退款单表），订单进入既有退款/关闭状态机；③ 30min 续期窗口后仍未支付的，由 PayTimeoutScanJob 既有路径取消（cancel_type=2），消费者不做长驻等待。
- 团长优惠：建单时服务端价（C37）；SUCCESS 不改价（参团/开团价下单瞬间已固定）。
- 所有 Listener 走 t_order_mq_consume（V2:209）eventId 去重 + 业务键 groupNo#type 双幂等。

**单测清单**：① SUCCESS 仅续期待付款单、已支付不动、重复消费第二次无更新（断言 expire_time 与 MQ 发送次数）；② FAIL 未支付→50 且发 ORDER_CANCELLED；③ FAIL 已支付→退款原语恰好一次（mock 断言）、重复事件不二次退款；④ type=1/2 事件 no-op 且落消费记录；⑤ 非本团/无订单事件安全跳过；⑥ 续期 deadline 已大于 now+30min 时不回退。
**E2E 验收点（黑盒 HTTP）**：① `POST /orders`（orderType=3，groupbuyActivityId+groupNo）两笔成团场景 → 调 GET 订单详情断言 expire_time 被 +30min；② 失败场景后 GET 两单：未付单 status=50、已付单进入退款/关闭态且 `GET /pay/refunds`（以资金卡端点现状为准）恰好一笔；③ 30min 后未付由扫描任务取消；④ 全部请求带正常用户 token，断言无 500、MQ 消费组在线。
**环境残留声明**：新增消费组 cg_order_groupbuy 需 create-topics.sh 重跑；测试团/测试订单在 e2e profile 可物理清理；不新增表，无 DDL 回滚负担。
**风险与回归面**：PayTimeoutScanJob/PayTimeoutListener 语义不能变（延迟消息重复投递以库时间为准，已具备）；已支付订单退款强依赖 FUNDS 原语，未就绪时本卡不得上线（FAIL 已支付分支降级为告警挂起，禁止吞单）；MarketingTxOps 价格注入点改动会影响秒杀/预售，按 price_snapshot 回归全部 orderType。

### 4.2 B5-库存侧：预售库存支付后扣减 + 定金/尾款两阶段状态机

**现状证据**：StockServiceImpl.java:85-96（同步 lockStock）、:365-384（handleOrderCreated 一律 doLock）、:388-399（handleOrderPaid 以 LOCKED 流水 confirm）、:466-473（类型映射）；ProductSku.java:85-88（无预售数量）；设计 design.md §3.3（预售支付后扣减）、§4.5（定金不退/尾款 3 天）。

**精确改动文件清单**
1. shop-api：C31 事件字段、C34 状态码（4 已出账、5 预售回补）、C38 refOrderNo；`StockItemCommand` 如有需要补 presaleFinalStage（可空）。
2. `StockServiceImpl`（product）：
   - `lockStock` / `handleOrderCreated`：orderType=4(PRESALE) 分支**直接幂等成功**（消费记录照写；不写流水、不动库存）。
   - `handleOrderPaid`：预售分流——定金阶段（orderType=4 且 presaleFinalStage=false/null 的历史口径由 W0 定）调新 `deductPresaleDeposit`；尾款阶段（=true）调 `linkPresaleFinal`；其余类型维持 confirmExisting 原路径。
   - `handleOrderCancelled`：预售 type=2 流水 status=1 时走新 `returnPresaleDeposit`（而不是 releaseExisting 的"仅 LOCKED 可释放"拒绝路径）。
3. 新增 mapper 方法（ProductSkuMapper / ProductStockLogMapper，SQL 以现状注解/XML 位置为准）：`deductPresaleStock(skuId,qty)`（presale_stock-=qty, occupied_stock+=qty）、`returnPresaleStock(skuId,qty)`（逆运算，带行数守卫）、`shipOutStock(skuId,qty)`（occupied_stock-=qty，B13 共用）。
4. product V4 DDL（§3.3 第 3、4 段）。
5. ProductSku 实体补 `Long presaleStock`。

**核心步骤（两阶段状态机）**
- 下单：预售不锁（同步 Feign 与 MQ 二道均 no-op 且各自幂等）。
- 定金支付成功：presale_stock-=qty、occupied_stock+=qty；插 type=2、status=1 流水（orderNo=定金单号）；UK 幂等，重复支付回调不重复扣。
- 尾款支付成功：插/更新 type=2 尾款流水（orderNo=尾款单号、ref_order_no=定金单号、status=1），**occupied 不再变动**（UK + ref 双重防重）；发货时随 B13 统一出账 status→4。
- 尾款期未付（订单超时/取消事件到达）：对 status=1 的定金流水 occupied-=、presale_stock+=、status→5；定金不退是资金语义（FUNDS/营销卡），库存回补是本卡职责，两者互不阻塞。
- 定金支付前买家取消（design 允许窗口以营销/资金卡为准）：无流水无库存动作，仅消费记录幂等。

**单测清单**：① 预售下单后四仓数量全不变；② 定金支付：presale-=、occupied+=、流水 1；重复回调数量只动一次；③ 尾款支付：occupied 不二次增加、ref_order_no 正确；④ 尾款违约：presale/occupied 回补、status=5、重复取消幂等；⑤ 普通/秒杀/拼团回归原 lock→confirm→release 路径零变化；⑥ presale_stock 不足（行级更新 0 行）抛 STOCK_NOT_ENOUGH 且事件重试可恢复。
**E2E 验收点（黑盒 HTTP）**：预售建单→管理端/内部查库存接口四仓不变；模拟支付定金（pay 回调既有黑盒入口）后查 presale/occupied；模拟尾款支付→发货（商家 POST 发货端点）→断言 occupied 最终出账；模拟尾款超时后 presale_stock 回补。
**环境残留声明**：V4 加列默认 0，历史预售 SKU 需运营在上线窗口维护 presale_stock 初始值（发布清单声明，不写数据迁移脚本）；新增状态码仅追加。
**风险与回归面**：同步预锁 + MQ 二道锁的现状幂等依赖 uk(order_no,sku_id,type)，预售 no-op 必须在两道都生效，漏改同步道会出现"下单锁 0 数量池"错乱；handleOrderPaid 历史无 orderType 的消息（C31 null）必须落普通 confirm 路径；与 P1-1 同波上线（对账要认 status=5 与预售无流水两种新形态）。

### 4.3 B7：运费模板/区域规则服务端定价

**现状证据**：无任何运费模板表（sql/product V2 表清单仅 category/brand/spu/sku/stock_log/stock_warning/comment/mq_consume）；CreateOrderRequest.java:39-40 客户端上送；PriceEngine.java:421-435 只分摊。运费险保费边界：**保费另由资金卡（B11/FUNDS）处理**，本卡不建模、不计价、不在运费模板中夹带保费字段。

**精确改动文件清单**
1. product V3 DDL（§3.2 两张表）。
2. 新增 product 域 `freight` 包：`FreightTemplateService(+Impl)`、`FreightPricingService`（内部定价器：按件/重/体积 + 区域命中 + 满额包邮 + 不可配送拒单）、商家端 `MerchantFreightController`（CRUD，复用商家鉴权现状切面）。
3. 新增 inner 端点与 shop-api C32：ProductInnerController 增加 `POST /inner/freight/calc`；ProductClient 同步 Feign 方法。
4. order：OrderCreateServiceImpl 建单价格组装处删除对 `req.freightFen` 的信任，改为调 ProductClient（或经营销试算 PriceCalc 统一编排，W0 二选一）取价写入 price_snapshot 与 freight_fen 列；客户端字段保留接收但**忽略其值**（日志记录篡改尝试，不报错——兼容老端）。
5. marketing：PriceCalc 试算入口（MobileMarketingController.java:23-25 → PriceEngine）改为先调 product 取原价运费、再应用免邮券（对接点，MARKETING 卡实施；本卡保证 inner API 契约与 SLA）。

**核心步骤（定价规则）**：命中 region 规则优先于默认；按 charge_type 计算 `firstFee + ceil((qty-firstUnit)/addUnit)*addFee`（除零与负值守卫）；满额包邮按商品实付（券后，以试算顺序 W0 决议）；deliverable=0 → 建单返回区域不可配送业务错误（错误码复用 PARAM/区域类现状码，实施时以 ErrorCode 现状为准）；规则快照 JSON 入 price_snapshot，售后退运费按快照口径（对接售后卡，不在本卡）。
**单测清单**：区域命中/未命中、首重续件边界（恰好首件、零续件）、满额包邮临界、不可配送拒单、无模板/停用模板默认值（0 运费或拒配由 W0 定，单测固定决策）、并发改模板版本下建单取价一致性（快照固化）、客户端上送 99999 运费被忽略。
**E2E 验收点**：商家配置模板→`POST /orders` 带不同区域地址断言 freight_fen 来自模板；篡改 freightFen 不影响实付；试算接口与建单运费一致；免邮券生效路径与营销 e2e 联合验收。
**环境残留声明**：新表上线后商家无模板期间的默认策略（默认 0 运费）须在发布说明声明并给运营配置 SOP；无历史数据迁移。
**风险与回归面**：inner Feign 失败的建单容错——明确**失败即建单失败**（不能静默 0 运费，重蹈客户端篡改覆辙），注意降级方向；PriceEngine 分摊算法（:421-435）不改，只改运费来源；分账/退款依赖 freight_fen 的既有口径不变（值变权威了）。

### 4.4 B13：虚拟类目多二级挂载、规格主数据、库存"发货扣减"

**现状证据**：类目为固定三级树 `t_product_category`（V2__product.sql:13-30，pid/level/attr_template_json），SPU 仅 `category3_id` 单挂载（:57、sku 冗余 :97）；属性只活在类目 JSON 模板与 SpuSaveRequest.attrsJson（SpuSaveRequest.java:49），无主数据表；发货事件已发（OrderPersister.java:89-97，shop_order_shipped 在 topic 列表 :11）但 product 无消费者、无 cg_product_shipped 组；流水状态无出账码（V2:138）。

**精确改动文件清单**
1. product V4 DDL（§3.3：t_product_spu_category 多挂载、t_product_attr_key 规格主数据、stock_log 扩状态码）。
2. 新增 product 域：`SpuCategoryMountService(+Impl)`（挂载/卸载，主归属 category3_id 不可卸载、虚拟挂载 mount_type=2）、`AttrKeyService(+Impl)` + 商家/平台端 controller（写权限口径复用 AdminGoodsController 现状鉴权）；实体/Mapper 按 MyBatis-Plus 现状风格新增。
3. 改 SpuSaveRequest/保存服务：attrsJson 中关键/销售属性按 attr_key 主数据校验（可选值/必填/类型），未收录属性仍允许进快照但返回告警（向后兼容，W0 决定是否强校验）；新增可选挂载 categoryIds 字段（包装 List<Long>，@Size(max=20)）。
4. 库存发货出账：新增 `shop-product-service/.../stock/mq/OrderShippedStockListener.java`（group=`cg_product_shipped`）；StockServiceImpl 新增 `handleOrderShipped(OrderShippedEvent)` + `shipOutExisting(log)`（仅 status=1 可出账→status=4，skuMapper.shipOutStock：occupied_stock-=qty，行级守卫）；售后回库选择器需兼容 status=4（selectDeductedByOrderAndSku 现状按 1 查，改为 1/4 均可回库并保持各自逆运算）。
5. C34/C39 契约与消费组。

**核心步骤（发货出账状态机）**：支付 confirm：locked→1（现状语义"已扣减入占用"不变）；发货：1→4（occupied 出账，physical 出仓由 WMS，本系统只记账）；退货回库：4→3（回可售/残次，责任方判定沿用 handleAftersaleChanged 现状 :420-455）；事件 eventId 走 t_product_mq_consume 幂等，重复发货消息安全跳过。

**单测清单**：① SPU 主归属 + 2 个虚拟挂载，按任一挂载类目能查到 SPU；② 卸载主归属被拒；③ attr_key 必填缺失/枚举越界/数值单位；④ 发货：occupied-=、流水 1→4，重复发货消息零变化；⑤ status=2/3 流水收到发货事件抛冲突可重试；⑥ 已出账订单退货退款回可售、商家责任入残次（:441-455 分支回归）。
**E2E 验收点**：平台/商家端配置属性主数据与虚拟类目 → 建品挂载 → 前台按虚拟类目列表出该 SPU；下单→支付→商家发货链路后内部库存查询 occupied 归零、流水 4；发货后退货回库正确。
**环境残留声明**：多挂载/属性表为空时前台行为与现状完全一致（单挂载、attrsJson 直通）；存量 SPU 不回填挂载关系。
**风险与回归面**：confirmDeduct 与 returnStock 被售后/订单多处依赖，出账码加入后所有按状态过滤的 mapper SQL 都要排查（实施时 grep `status` 条件全量过一遍）；商品列表按类目查询路径若用 category3_id 硬过滤，虚拟类目不命中属预期，但需确认导航/搜索卡（PLATFORM/营销）不假设单挂载。

### 4.5 P1-1：普通库存对账 Job（product 服务）

**现状证据**：AUDIT_MQ_CONSISTENCY.md §三 P1-1 原文（:42-52）与附录 E.3（:317-321，CANCELLED 早于 CREATED 悬挂、PAID 早于 CREATED 无人 confirm）；product 域无 @Scheduled；消费跳过点 StockServiceImpl.java:401-418；同步释放吞异常 OrderResourceReleaser.java:33-69；锁范式参考 PayTimeoutScanJob.java:33（@SchedulerLock PT5M/PT1M，依赖 net.javacrumbs.shedlock，product 引入时以 order pom/配置现状为准）。

**精确改动文件清单**
1. 新增 `shop-product-service/.../stock/job/StockReconcileJob.java`：`@Scheduled` 固定延迟（建议每 5 分钟，cron 以现状任务风格为准）+ `@SchedulerLock(name="productStockReconcile", lockAtMostFor="PT4M", lockAtLeastFor="PT30S")`。
2. 新增 `StockReconcileService(+Impl)` 与 mapper 查询：`selectLockedLogsBefore(limit, minAge)`（走 V4 新增 idx_status_time）；C33 批量查订单状态（Feign OrderClient，@Size 100 分页）。
3. 新增对账结果表（product V4 追加，若 PLATFORM 卡已有统一告警/任务台账则改用之，实施时以现状为准）：`t_product_stock_reconcile_log`（id、order_no、sku_id、type、action、detail、create_time；KEY idx_order），每次处理落一行便于审计与抑制重复告警。
4. （可选同波）OrderResourceReleaser 三个 catch（:40/:49/:66）补告警指标埋点——该文件在 order 服务，改动仅加 metric/log 级别提升，不改释放时序；若划入其他波次，对账 Job 的"无有效订单 LOCKED 流水"扫描已能兜底，不阻断本卡。

**核心步骤（权威源 = 订单状态）**：
- 扫描 status=0(LOCKED) 且 create_time 早于 now-N 分钟（建议 N=10，避开正常在途窗口）的流水，分批 limit 200。
- 按 orderNo 聚合调 C33 批量查状态：订单 50 已取消/70 已关闭 → releaseExisting 释放；订单 40 已完成或 30 待收货且存在支付记录 → confirmExisting 补 confirm 后再按 30/40 决定是否出账（40→shipOutExisting）；订单 10 待付款且未超 expire → 跳过；订单 10 已超 expire（以订单 expire_time 为准）→ 释放；订单查不到（Feign 返回空/404）→ 不自动释放，落 reconcile_log + 告警（可能是跨环境脏数据/乱序中间态，二次扫描仍无才升级人工）。
- B5 上线后扩展：预售 type=2 status=1 超尾款期（订单已取消）→ returnPresaleDeposit（status→5）；预售无流水是合法态，扫描器不得为预售 LOCKED 缺失造流水。
- 每次动作带 reconcile_log UK/幂等检查（order_no+action+当天窗口），告警抑制；乱序双重故障兜底：CANCELLED 先消费造成的悬挂在 Job 第 2 轮自动收敛。

**单测清单**：① 终态取消订单残留 LOCKED → 释放且只处理一次；② 已支付订单 LOCKED 残留 → confirm（+已完成的出账）；③ 在途未超时不动；④ 订单查不到只告警不释放、连续两轮升级；⑤ 预售两形态（定金流水违约→5；无流水不造单）；⑥ ShedLock 并发下单实例执行；⑦ 每批 Feign 部分失败不回滚整批（逐单 try/catch，参照 P2-4 教训）。
**E2E 验收点（黑盒/运维）**：人为制造取消后 LOCKED 残留（测试库直接置位）→ 一个调度周期内内部库存查询恢复可售、reconcile_log 有记录；告警通道（现状日志/指标，以 PLATFORM 现状为准）有一条 P2 级事件；正常订单零误报。
**环境残留声明**：t_product_stock_reconcile_log 为运维台账，可长期保留；Job 依赖 ShedLock 提供方（order 已在用，product 接入配置以 order 现状复制）。
**风险与回归面**：最大风险是误释放未支付在途单——minAge 下限 + 订单 expire_time 双条件缺一不可；Feign 调用在调度线程而非消费事务，无长事务问题；Job 与 MQ 消费者并发操作同一流水时依赖现有 updateStatusIf 乐观条件，天然安全。

### 4.6 C-COMMENT：评价/晒单事件生产半卡（product 域）

**对接点声明**：评价功能归属 **shop-product-service**（`com.shop.product.comment` 包；order 侧无 CommentService）。事件类 `CommentCreatedEvent`（字段 commentId/userId/orderNo/spuId/behaviorType 1评价 2晒单/withImage/eventTime）、topic 常量（shop_comment_created）、消费组 cg_user_comment_created、create-topics.sh:17 后追加——**全部由 W0 按 GAP_PLAN_USER C40/C42/C47 统一落 shop-api/shop-common 与脚本，本卡只引用不重复定义**。

**现状证据**：`CommentServiceImpl.java:63-115` create() 同事务 insert 评价（:105 硬置 status=1 正常；状态注释 ProductComment.java:69 "1 正常 2 平台屏蔽"），无 outbox 发布；追评/晒单入口 append()（:116-137）；product 域 outbox 用法现成（StockServiceImpl.java:78,309 OutboxPublisher.publish(topic, tag, event, bizNo)）。

**精确改动文件清单**
1. 改 `CommentServiceImpl.create()`：insert 与好评率更新（:106-114）之后、**同一 @Transactional 内**调 OutboxPublisher 发布 CommentCreatedEvent，bizNo=commentNo；eventTime=now。
2. 审核闸门：仅当评价最终态为正常（status=1）才发；现状无"审核中"前置态（create 直接 status=1），若后续引入审核态（status 常量由 W0 对齐 USER 规划/PLATFORM 审核卡），发布点移至审核通过动作；被平台屏蔽（status=2）不发、不补发。
3. behaviorType 判定：首评为 1；带图/晒单形态为 2（withImage 由 imagesJson 非空判定，与 validateMedia 现状同源）；若 W0 确认"晒单"走 append() 独立入口，则在 append() 同事务补发布点（behaviorType=2）——两处发布均以 commentId/commentNo 幂等，USER 侧消费幂等兜底重放。
4. 不新增 DDL；不改 create-topics.sh（USER 规划所有）。

**单测清单**：① create 成功后 outbox 恰好一条事件且字段正确（withImage true/false 两例）；② 内容违规走屏蔽分支/审核未过不发；③ 重复评价被 REPEAT_SUBMIT 拦截时无事件；④ 事务回滚（如好评率更新失败）事件不落地；⑤ append 入口（若 W0 选该方案）发布且幂等。
**E2E 验收点**：黑盒 POST 评价端点（完成订单）→ product outbox/relay 后 shop_comment_created 投递，user 侧成长值/积分到账（与 USER e2e 联合）；被屏蔽评价无积分；重放消息不重复发积分。
**环境残留声明**：无表/无脚本残留；上线前 USER 半卡（topic、常量、消费组）必须先在 W0 合入，否则本卡发布即投递失败。
**风险与回归面**：发布点必须与评价 insert 同事务（outbox 表与业务库同源，参照 GroupbuyService.java:190 注释 P1-1 模式），禁止事务提交后再发；好评率计数与事件无顺序依赖。

### 4.7 API-T：order/product 侧契约硬化（AUDIT_API_CONTRACT.md §7.2 表）

**现状证据**：CreateOrderRequest.java:22-61（orderType 仅 @NotNull、source 无注解、items 无 @Size、freightFen/usePointsFen 无非负、fromCartIds 无 @Size）；OrderCreateServiceImpl.java:529-543 default 分支静默按普通；CartSelectRequest.java:16（ids 无 @Size）；SpuSaveRequest.java:38-54（mainImage 仅 @Size 无 scheme、images 无元素约束、detailJson/attrsJson 无 @Size、skus 无 @Size）；ProductInnerController.java:43-46（裸 List<Long>）；shop-api StockLockCommand.java:37-39、StockDeductCommand.java:37-39（items 无 @Size）；AdminGoodsController.java:50-56（violation remark @RequestParam 无 @Size）；CommentCreateRequest.java:56-61（媒体 URL 无长度/scheme 注解，服务端 validateMedia 在 CommentServiceImpl.java:199-200 仅做数量校验）。

**精确改动文件清单**（全部注解/白名单级，不改业务语义）
1. shop-common：C35 BatchSizes、C36 @MediaUrl/@ListMediaUrl（http/https + ≤512 + 列表 ≤10）。
2. shop-api product/dto：StockLockCommand/StockDeductCommand/StockReleaseCommand items 加 `@Size(min=1,max=ITEMS_MAX)`；StockItemCommand 补 @NotNull/@Positive；ProductClient.listSkus 参数改为带 @Size 的请求体或在 ProductInnerController.listSkus 入参加 `@Size(max=IN_IDS_MAX)`（Feign 同步，二选一，W0 定）。
3. order DTO：CreateOrderRequest——orderType 加白名单（自定义 @InEnum({1,2,3,4}) 或服务端首行显式 1-4 白名单校验，非法返回参数错误码；5=换货无建单入口须显式拒绝；**先补 §0 勘误所述单测确认现状行为**）；source 加 @Min(1)@Max(4)；items 加 @Size(min=1,max=100)；fromCartIds @Size(max=100)；freightFen/usePointsFen 加 @Min(0)；CartSelectRequest.ids @Size(max=100)、select-all 的 shopId 口径以现状为准。
4. product DTO：SpuSaveRequest.images @ListMediaUrl、mainImage 改 @MediaUrl、detailJson/attrsJson @Size(max=...)（建议 20_000，以 MEDIUMTEXT 与网关 body 上限协调 W0 定）、skus @Size(min=1,max=100)+@Valid（@Valid 现状已有 :53）；CommentCreateRequest 媒体字段同注解；AdminGoodsController：violation remark 改 DTO 参数或类级 `@Validated` + `@RequestParam @Size(max=256)`。
5. GlobalExceptionHandler 对 ConstraintViolationException/MethodArgumentNotValid 的错误码统一为 10001（参数错误）而非 10009——该类属全局组件，本卡仅提出需求并验证 order/product 接口行为，若跨服务公共改动归 PLATFORM 波次则在 W0 挂依赖，不在本卡改公共类。

**核心步骤**：所有批量上限常量统一引用 BatchSizes，禁止散落字面量；新增校验失败回归测试矩阵（每个端点非法值→错误码断言）。
**单测清单**：orderType=0/6/null、source=9、items=101 行、fromCartIds=101、freightFen=-1、ids=101、skus=101、detailJson 超长、URL=`javascript:alert(1)`/ftp://x/513 字符、IN 列表 101、violation remark 257 字符——全部断言被拒且错误码统一；合法报文回归不受影响（尤其换货 type=5 现状若无入口，非法建单被拒后补一条"换货单不可由 POST /orders 创建"的断言）。
**E2E 验收点**：网关黑盒对每个列名端点发非法报文断言业务码（非 500/非 10009）；shop-e2e 既有正向下单/建品/勾选脚本全绿。
**环境残留声明**：纯契约变更无 DDL/无 topic；老客户端若存在批量 >100 的真实报文（排查后认为无），需灰度公告。
**风险与回归面**：白名单可能拦截历史脏调用——上线前 grep 网关访问日志确认无 orderType=5 直建流量；@RequestParam 校验必须配合类级 @Validated，漏标会静默失效（单测兜底）。

## 5. 执行顺序与跨波次建议

- **W0 契约波（跨规划，先行不合业务）**：C30-C38 在 shop-api/shop-common 落常量/DTO/事件可空字段；USER 落 CommentCreatedEvent 与 shop_comment_created 脚本行；与 MARKETING 对齐 GROUPBUY 事件生产形态（单/批量）、PriceCalc 团长优惠字段名与运费取价编排位置；与 FUNDS 对齐失败团退款原语签名/退款单幂等；C39 两个消费组脚本行随本规划首个业务波合入。
- **W1（本域自洽，零外部依赖）**：API-T（4.7）；B7 的 product V3 DDL + 模板 CRUD + /inner/freight/calc（建单侧强制取价可在 W2 与 PriceCalc 编排同时切换，先双跑比对一周：服务端价与客户端上送价差异打点）。
- **W2**：B13 多挂载/规格主数据 + 发货出账（product V4 DDL、cg_product_shipped）；B5 预售两阶段（与 V4 同批，共用流水状态机改造）；C-COMMENT 生产半卡（依赖 W0 USER 常量，可与本波同发）。
- **W3**：P1-1 对账 Job（依赖 W2 全部库存新形态稳定 + C33 订单批量查询）；OrderResourceReleaser 告警埋点随 W3 或更早的 order 维护波。
- **W4**：B1 拼团消费者（cg_order_groupbuy）——依赖 W0 全量对齐、FUNDS 退款原语可用、W2 库存/取消链路稳定；上线顺序：先部署消费组空跑验证消费正常（仅落消费记录+日志灰度开关），再开 SUCCESS/FAIL 动作。
- 跨波次纪律：每个 MQ 消费者上线前 create-topics.sh 必须已重跑（参考 P0-1 漏建 topic 教训）；所有新 @Scheduled 必须带 @SchedulerLock；所有 Feign 内部端点保持 /inner 网关收口现状，不新增绕过面；DDL 编号 order V4 起 / product V3 起，发版前与 FUNDS（order V3 占用者）、USER、PLATFORM 互相核号，禁止重号。
