# 数据层只读审计报告（DDL_REVIEW）

- 审计对象：仓库 `/Users/bytedance/bits/shop`，7 个业务库共 **71 张表**（shop_user 11 / shop_product 8 / shop_order 6 / shop_pay 8 / shop_settlement 11 / shop_marketing 16 / shop_aftersale 11）
- 审计方式（全程只读）：
  - 通读 7 个 DDL 文件 `sql/<domain>/V2__<domain>.sql`；
  - 对运行容器 `shop-mysql` 逐表执行 `SHOW CREATE TABLE`（71/71），并查询 `information_schema` 做聚合核验，仅 SELECT/SHOW，无任何 DDL/DML；
  - 扫描全部 7 个服务的 entity（71 个 `@TableName` 类）与 mapper（全注解 SQL，无 XML），机械化比对实体字段↔活库列；
  - 结论均附 `文件:行` 或 `库.表.列` 证据，不做臆测。
- 审计基准：`com.shop.common.model.BaseEntity`（id / createTime / updateTime / deleted 四公共列，双向比对时豁免）。

---

## 一、问题清单（按严重度）

### 阻断（1）

**[P0-1] 购物车/收藏唯一键不含 deleted，逻辑删除后重新加购、重新收藏必然撞唯一键**

- DDL：`sql/order/V2__order.sql:35`（`t_order_cart`：`UNIQUE KEY uk_user_sku (user_id, sku_id)`）、`sql/order/V2__order.sql:59`（`t_order_favorite` 同构）。两表实体均 `extends BaseEntity`，`deleted` 上有 `@TableLogic`。
- 删除全部是逻辑删除（UPDATE deleted=1）：`shop-order-service/.../cart/service/impl/CartServiceImpl.java:108`（delete）、`:134`（移收藏后删购物车）、`:178`；下单清空购物车 `shop-order-service/.../order/service/OrderPersister.java:44-47`。
- 加购 `CartServiceImpl.add()`（`CartServiceImpl.java:59-91`）：先 `selectOne(user_id, sku_id)`（MP 自动追加 `deleted=0`，已删行不可见）→ 查不到即 `insert`，命中 `uk_user_sku` 抛 `DuplicateKeyException` 且无捕获兜底。
- 用户路径可稳定复现：**删除购物车中某 SKU（或下单清空）后再次加购同一 SKU → 直接报错**；`moveToFavorite()`（`:119-132`）对 `t_order_favorite` 同理（取消收藏后再次收藏同 SKU 失败）。
- 修复方向（仅建议，未执行）：唯一键纳入逻辑删除值且删除值取行 id（存活为 0），或加购时"复活"已删行，或该两类表改物理删除。

### 高（2）

**[H-1] shop_pay 8 张表活库排序规则漂移为 utf8mb4_0900_ai_ci，与文件声明的库级排序规则及其余 63 张表不一致**

- `sql/pay/V2__pay.sql:52/83/118/142/166/193/227/248` 8 张表均只写 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`，**未带 COLLATE**；库级声明是 utf8mb4_unicode_ci（`V2__pay.sql:9`）。
- 活库实测（SHOW CREATE TABLE + information_schema）：该 8 表 `COLLATE=utf8mb4_0900_ai_ci`（套用服务器默认 collation），其余 6 库 63 表均为 `utf8mb4_unicode_ci`。
- 这是文件↔活库 71 张表全量结构比对中**唯一**结构性差异。后果：跨表/跨库字符串比较与排序口径不统一；在 server 默认排序规则不同的环境重建库会得到不同结构，迁移结果不可重复。

**[H-2] 品牌/SKU 的唯一键带 deleted(0/1) 防不住第二次同名删除**

- `sql/product/V2__product.sql:44`（`t_product_brand`：`UNIQUE KEY uk_name (name, deleted)`）、`sql/product/V2__product.sql:117`（`t_product_sku`：`UNIQUE KEY uk_sku_code (sku_code, deleted)`）。
- `deleted` 只有 0/1：同名品牌（或同 sku_code）新建后再次删除时，逻辑删除 UPDATE 要把第二行也置为 deleted=1，与第一行已删数据撞唯一键。属于 P0-1 的同类反模式，触发于后台商品/品牌管理，频率较低故列高。

### 中（7）

**[M-1] 营销域 8 个实体的 version 列无 `@Version`，自定义 UPDATE 的 WHERE 也不带 version 条件，乐观锁实际不生效**

- 全仓 `@Version` 共 20 个类，营销服务为 **0**；带 version 字段却无注解：`Coupon`、`UserCoupon`、`Activity`、`Promo`、`SeckillSku`、`Groupbuy`、`PresaleOrder`、`BargainRecord`（`shop-marketing-service/.../**/entity/*.java`，DDL 列见 `sql/marketing/V2__marketing.sql` 各表）。
- 自定义 SQL 只做 `version = version + 1` 单向自增，WHERE 无 `AND version = #{version}`：如 `shop-marketing-service/.../coupon/mapper/CouponMapper.java:14-24`。
- 现状并发性由状态机条件 UPDATE（`status = fromStatus`）和库存条件（`stock >= qty`）兜底，资金安全暂不依赖 version，但 version 列形同虚设，后续若改用 MP `updateById` 将误以为有乐观锁保护。

**[M-2] 一批写路径表无 version，并发安全完全依赖条件 UPDATE/UK 插入幂等这一种手段**

- 代表：`t_pay_refund_split`（`sql/pay/V2__pay.sql:125`）、`t_aftersale_refund`（`V2__aftersale.sql:157`）、`t_aftersale_window`（`:103`）、`t_aftersale_order_item_ref`（`:133`）、`t_seckill_order`（`V2__marketing.sql:186`）、`t_groupbuy_member`（`:224`）、`t_marketing_lock`（`:306`）、`t_promo_level/t_promo_target`、`t_coupon_target`、`t_product_stock_log`（`V2__product.sql:128`）。
- 经核对 mapper，这些表当前的写 SQL 均带状态条件或唯一键幂等，手法正确；但没有第二道防线，后续新增写入口时必须保持同一写法。

**[M-3] 两处真实索引缺口（代码实际查询路径）**

- `t_user_coupon`：无 coupon_id 参与的索引。限领校验 `CouponService.countHeld()` 以 `(user_id, coupon_id)` 过滤（`shop-marketing-service/.../coupon/service/CouponService.java:96-98`），只能用 `idx_user_status(user_id,status)` 的 user_id 前缀再回表过滤 coupon_id；建议加 `(user_id, coupon_id)`。
- `t_pay_channel_flow.order_no`：无索引（键只有主键、`uk_channel_order_no(channel_code,channel_order_no)`、`idx_pay_no`、`idx_channel_txn`，`sql/pay/V2__pay.sql:79-82`）；按业务订单号查渠道流水（对账/客服链路）为全表扫描，建议补 `idx_order_no`。

**[M-4] 两条自定义 UPDATE 缺少 deleted 过滤条件**

- `shop-marketing-service/.../coupon/mapper/CouponMapper.java:18-19` `decreaseReceived`：`WHERE id=#{id} AND received_count > 0`，无 `status=1 AND deleted=0`（同文件其余 UPDATE 均带）；该方法仅在领券撞键回滚补偿时调用（`CouponService.java:89`），影响有限但口径不统一。
- `shop-pay-service/.../feature/payment/mapper/NotifyLogMapper.java:20-22` `updateResult`：`WHERE id=#{id}`，无 deleted（回调日志表本不做逻辑删除，实际无害，建议风格统一）。
- 全仓未发现无 WHERE 的 UPDATE/DELETE，上述是仅有的两处"有 WHERE 但漏 deleted"。

**[M-5] 31 处 `@Select("SELECT * ...")`，列变更直接冲击实体映射**

- 集中在 aftersale 域全部查询 mapper（`AftersaleOrderMapper/AftersaleItemMapper/AftersaleWindowMapper/AftersaleRefundMapper/AftersaleDisputeMapper/AftersaleInsuranceMapper/AftersaleOrderItemRefMapper/AftersalePriceProtectMapper`）、pay 域 `PaymentMapper/ChannelFlowMapper/RefundSplitMapper/ReconDiffMapper`、settlement 域 `ClearingMapper/WithdrawMapper/WithdrawAutoConfigMapper/DepositLogMapper`、user 域 `UserAccountMapper/UserAccountFlowMapper/UserPointsGrantMapper`、product 域 `ProductStockLogMapper` 等。建议显式列名。

**[M-6] MQ 幂等键口径 7 库不统一，且消费状态码语义相反**

- `shop_pay` 为 `uk_group_event(consumer_group, event_id)`（`sql/pay/V2__pay.sql:246`），正确支持同一事件被多个消费组各自处理；
- 其余 6 库均为单列 `uk_event_id(event_id)`，其中 shop_user（`sql/user/V2__user.sql:228-239`）与 shop_settlement（`sql/settlement/V2__settlement.sql:270-282`）表内**存了 consumer_group 列却不进唯一键**——同一 event_id 若被两个消费组消费会互相判重（当前事件均为单组订阅，属理论风险）。
- 状态码：`t_marketing_mq_consume.status` 注释 `0成功 1失败待重试`（`sql/marketing/V2__marketing.sql:331`），其余各库为 `1成功`（pay 为 `consume_status 1成功 2失败`，settlement `1成功 0失败`），跨域排障易误读。

**[M-7] t_user 的 uk_username/uk_phone 不含 deleted，逻辑注销后同名/同手机号无法重新注册**

- `sql/user/V2__user.sql:33-34`。若业务允许注销后重新注册（同名/同号），将在注册插入时撞键，性质同 P0-1；当前代码中未见注销复活流程，列为中风险待业务确认。

### 低（10）

- **[L-1] 实体↔列唯一不匹配**：`t_marketing_mq_consume` 有 `deleted` 列（`sql/marketing/V2__marketing.sql:334`），但实体 `shop-marketing-service/.../mq/entity/MqConsumeLog.java` 未声明该字段、未继承 BaseEntity（无 `@TableLogic`）。该表 append-only，实际无害；这是 71 个实体机械化双向比对中**唯一**的列/字段不一致。
- **[L-2] 主键策略三套并存**：用户/商品/订单/营销/`t_sett_merchant` 用雪花 `BIGINT`；pay 8 表用 `BIGINT UNSIGNED AUTO_INCREMENT`；aftersale 11 表、settlement 其余表用 `BIGINT AUTO_INCREMENT`。跨域 ID 生成方式不统一；另 `BIGINT UNSIGNED` 映射 Java `Long` 理论上有上限溢出问题（自增量级远达不到，提示性风险）。
- **[L-3] `t_pay_channel_flow.paid_fen` 列名误导**：列名为"已付"，注释实为"本行累计已退款金额（分）"（`sql/pay/V2__pay.sql:71`），后续开发极易误用，建议改名 refunded_fen。
- **[L-4] V2 迁移脚本风格混用且不可演进**：user/order/pay/aftersale 用 `DROP TABLE IF EXISTS` + CREATE（破坏性、重跑丢表），product/settlement 用 `CREATE TABLE IF NOT EXISTS`（表存在则改列不生效）。两种写法都不能支撑上线后的增量结构演进，建议后续以增量版本脚本管理。
- **[L-5] 复合唯一键包含可空列，NULL 不去重**：`t_pay_channel_flow.uk_channel_order_no(channel_code,channel_order_no)`（`sql/pay/V2__pay.sql:80`）、`t_pay_refund_split.uk_channel_refund_no`（`:139`）。渠道单号回写前多行 NULL 互不冲突，当前依赖"先落本地单、成功后回写"的流程保证正确，属需显式知晓的 MySQL 语义。
- **[L-6] `t_aftersale_insurance.refund_time` 为 `DATETIME NOT NULL`**（`sql/aftersale/V2__aftersale.sql:235`）：理赔必须等退款成功事件才能插入，事件迟到/补投期间无法预落单，约束偏紧。
- **[L-7] `t_user_growth_flow.growth` 为 INT、`t_user.growth`/`growth_after` 为 BIGINT**（`sql/user/V2__user.sql:170` vs `:24`）：单笔增量与余额口径不同，异常大增量时有截断面，建议统一 BIGINT。
- **[L-8] `t_lottery_record` 除主键外无业务唯一键**（`sql/marketing/V2__marketing.sql:287-304`，仅有普通索引 idx_activity_user/idx_order）：append-only 流水可以接受，但消息重复投递时若上层未走 `t_marketing_lock` 防重，会产生重复中奖记录，建议补 `(activity_id,user_id,request_no)` 类幂等键。
- **[L-9] `t_aftersale_order.refund_no` 无索引**（列定义 `sql/aftersale/V2__aftersale.sql:55`）：当前退款查询走 `t_aftersale_refund.uk_refund_no`（`AftersaleRefundMapper.java:18`），主表该列只回写不查询，暂无实际扫描，预留观察。
- **[L-10] 后台扫描列索引非全覆盖**：部分小表（如 `t_sett_merchant` 无 create_time 索引、`t_lottery_record` 无 user_id 独立索引、若干状态/时间列只在复合索引中非前导）依赖管理端低频扫描，当前数据量下可接受，随量增长再评估。

---

## 二、八项审计要求逐条结论

### 1. 实体 ↔ 列 1:1 映射

对全部 71 个 `@TableName` 实体与 71 张活库表做了机械化双向字段比对（驼峰↔下划线，BaseEntity 四公共列豁免）：

- **70/71 完全一致**；唯一例外是 [L-1] `t_marketing_mq_consume.deleted` 列在实体中缺失（无功能影响）。
- 未继承 BaseEntity 的实体均与其表结构精确对应：product `StockWarning`/`MqConsumeRecord`（无 update_time/deleted）、order/marketing/aftersale 的 MqConsumeLog、pay/settlement 各实体。
- 反向（实体字段无对应列）：**0 处**。

### 2. 类型一致性

- 金额：information_schema 全量核验，**所有 `*_fen` 列均为 BIGINT 且注释完整（100%）**，实体均为 `Long`；全库唯一 DECIMAL 列是 `t_product_spu.good_rate DECIMAL(5,4)`（费率非金额），实体 `BigDecimal`，正确。
- 枚举/状态：无任何 status/type 列为 varchar，实体均为 `Integer`（TINYINT/INT）；`t_aftersale_order.status` 为 INT（状态码到 90），与实体 Integer 匹配。
- 时间：所有 `*_time` 为 DATETIME↔`LocalDateTime`、`*_date` 为 DATE↔`LocalDate`，**无 String↔时间错配**；JSON 列映射 String。
- 未发现 Integer↔BIGINT 的直接错配；提示项：积分列 INT/BIGINT 混用（计数 INT，余额/冻结 BIGINT）、[L-7] growth INT vs BIGINT、[L-2] BIGINT UNSIGNED↔Long。

### 3. MQ 消费幂等表（7/7 库全部具备，event_id 唯一去重）

| 库 | 表 | 唯一键 |
|---|---|---|
| shop_user | t_user_mq_consume | `uk_event_id(event_id)`（V2__user.sql:239） |
| shop_product | t_product_mq_consume | `uk_event_id(event_id)`（V2__product.sql:208） |
| shop_order | t_order_mq_consume | `uk_event_id(event_id)`（V2__order.sql:217） |
| shop_pay | t_pay_mq_consume | **`uk_group_event(consumer_group, event_id)`**（V2__pay.sql:246） |
| shop_settlement | t_sett_mq_consume | `uk_event_id(event_id)`（V2__settlement.sql:281） |
| shop_marketing | t_marketing_mq_consume | `uk_event_id(event_id)`（V2__marketing.sql:336） |
| shop_aftersale | t_aftersale_mq_consume | `uk_event_id(event_id)`（V2__aftersale.sql:300） |

口径差异与状态码语义问题见 [M-6]。业务侧另有插入幂等模式：流水/冲正表普遍以 `uk_biz_change(biz_no,change_type)`、`uk_refund_no`、`uk_order_no` 等做业务幂等，与 MQ 幂等构成双保险。

### 4. 关键表约束（逻辑删除 / 乐观锁 / 业务唯一键）

- **deleted**：除 4 张 append-only/投影表（`t_product_stock_warning`、`t_product_mq_consume`、`t_order_mq_consume`、`t_aftersale_mq_consume`）外全部具备，且实体侧 `@TableLogic` 一致（[L-1] 为唯一例外）。
- **version（28 张表有列，20 个实体启用 @Version）**：订单主表/明细/购物车、SKU/SPU、支付单/渠道流水/退款单、清算账户/清算单/商户/结算单/提现单/自动提现配置、用户/用户账户/地址/积分冻结/积分发放、售后单均带 version 且实体启用 @Version；营销 8 张表带 version 列但 **0 个 @Version**（[M-1]）；无 version 的写表见 [M-2]。
- **业务唯一键**：order_no/pay_no/refund_no/clearing_no/statement_no/withdraw_no/aftersale_no 等单据号均有独立 UK；账户 `uk_owner_role(owner_id,role_type)`、流水 `uk_biz_change(biz_no,change_type)`、购物车/收藏 `uk_user_sku`（该键设计缺陷见 P0-1）、领券 `uk_issue_request(user_id,coupon_id,issue_way,request_no)` 等关键约束在位。
- **无物理外键、无级联**（71 张表 0 个 FOREIGN KEY），与微服务分库架构一致；未发现跨库 JOIN。

### 5. 高频字段 / 同库关联列索引

- user_id、order_no、pay_no、status+时间（过期/超时扫描）、merchant_id 复合索引在主链路表上覆盖良好（如 `t_order_order` 有 idx_user_status/idx_user_time/idx_merchant_status/idx_status_expire/idx_status_confirm/idx_status_aftersale/idx_pay_no 共 7 个业务索引）。
- 条件扣减/状态机 UPDATE 的 WHERE 列（status、merchant_id+stage+due_date、account 角色等）均有索引或主键驱动。
- 真实缺口 2 处见 [M-3]；低优观察项 [L-9]/[L-10]；`t_product_comment` 的 (order_no, sku_id) 查询被 `uk_order_sku(order_no,sku_id)` 完整覆盖，不是缺口。

### 6. 字符集 / 引擎 / 注释

- ENGINE：**71/71 InnoDB**（information_schema 实测，0 个例外）。
- 字符集：**71/71 utf8mb4**；COLLATE：63 张 `utf8mb4_unicode_ci`，shop_pay 8 张活库为 `utf8mb4_0900_ai_ci`（[H-1]）。
- 表注释：**71/71 非空**；金额列注释：全部 `*_fen` 列均有"（分）"语义注释，100% 覆盖；唯一列名/注释矛盾为 [L-3]。

### 7. 活库核对（SHOW CREATE TABLE）

对 71 张表逐张执行 `docker exec -i shop-mysql mysql -uroot -proot -e "SHOW CREATE TABLE ..."`（只读），用解析器与 DDL 文件逐列比对：列名/类型/可空/默认值/AUTO_INCREMENT/ON UPDATE/全部键（名称、列序、唯一性）/引擎/字符集/表注释。

- **70 张表与文件完全一致；唯一漂移：shop_pay 8 表 COLLATE**（[H-1]；文件未写 COLLATE，活库套服务器默认值，故文件与活库在该属性上表现一致地"缺省"，但与库级声明及其余 63 表不一致）。
- information_schema 聚合复核：非 InnoDB 表 0；缺表注释 0；非 BIGINT 的金额列 0；varchar 型状态列 0；时间语义列类型异常 0。

### 8. 危险模式

- **物理删除**：全仓 mapper 无任何 `@Delete` SQL；删除统一走 MyBatis-Plus `deleteById/delete(wrapper)`，因 `@TableLogic` 全部落为 UPDATE deleted=1；未发现无界 DELETE。
- **UPDATE**：所有自定义 `@Update` 均带 WHERE（主键/单据号/状态机前置条件），库存与余额扣减均带守恒条件（`available_stock >= qty`、`balance >= amount`、`refunded_fen + ? <= amount_fen` 等），写法稳健；仅 2 处漏 deleted（[M-4]）。
- **SELECT ***：31 处（[M-5]），均为按主键/唯一键/索引列取整行映射实体，无越权全量导出类查询。
- **级联**：无 FK 级联；跨服务联动均经 MQ/Feign，未见可被数据库层放大的删除链。

---

## 三、71 张表逐表一句结论

### shop_user（11）

1. `t_user` — 雪花主键、version 与等级索引齐全，唯 uk_username/uk_phone 未含 deleted 存注销重注册隐患（M-7）。
2. `t_user_account` — uk(user_id,account_type)+version+余额行锁条件更新，资金表范式正确。
3. `t_user_account_flow` — append-only 流水，uk(biz_no,change_type) 业务幂等到位，无 version 合理。
4. `t_user_points_freeze` — uk_order_no+version，冻结/解冻幂等与并发控制齐备。
5. `t_user_points_grant` — uk_biz_no+version，发放幂等正确。
6. `t_user_points_daily` — uk(user_id,stat_date,scene) 防日刷，投影表无 version 合理。
7. `t_user_sign_in` — uk(user_id,sign_date) 天然保证每日一签。
8. `t_user_growth_flow` — uk_biz_no 幂等，growth INT 与余额 BIGINT 口径不一（L-7）。
9. `t_user_growth_discount` — uk(user_id,year) 年费折扣唯一，结构合规。
10. `t_user_address` — version+逻辑删除齐备，一用户多地址故无业务 UK，合理。
11. `t_user_mq_consume` — uk_event_id 幂等合规，consumer_group 未入键为理论口径问题（M-6）。

### shop_product（8）

12. `t_product_category` — 低频后台表，无 version/UK，靠层级关系管理，可接受。
13. `t_product_brand` — uk(name,deleted) 挡不住第二次同名逻辑删除（H-2）。
14. `t_product_spu` — version、JSON 快照、good_rate DECIMAL(5,4)↔BigDecimal 均正确，允许重名无 UK 合理。
15. `t_product_sku` — version+6 个 fen 列规范，uk(sku_code,deleted) 存二次删除撞键缺陷（H-2）。
16. `t_product_stock_log` — uk(order_no,sku_id,type) 保证 TCC 记录幂等，条件状态更新替代 version，可接受。
17. `t_product_stock_warning` — 仅 id/create_time 的事件投影表，实体不继承 BaseEntity 与表结构一致。
18. `t_product_comment` — uk_comment_no+uk_order_sku(order_no,sku_id) 防重复评价，spu/merchant/user 索引齐全。
19. `t_product_mq_consume` — uk_event_id 幂等，精简列（无 update_time/deleted）与实体一致。

### shop_order（6）

20. `t_order_cart` — uk_user_sku 不含 deleted，删除后重加购必撞键（P0-1）。
21. `t_order_favorite` — 同 P0-1，取消收藏后无法重新收藏同 SKU。
22. `t_order_order` — version+uk_order_no/uk_pay_no+7 个业务索引，状态机/超时扫描覆盖最完善的表之一。
23. `t_order_item` — version，idx_order_no/(user_id,sku_id)/aftersale_no 关联索引齐备。
24. `t_order_invoice` — uk_order_no 保证一单一票，结构合规。
25. `t_order_mq_consume` — uk_event_id 幂等，无 deleted 的 append 表与实体一致。

### shop_pay（8；均存在 H-1 排序规则漂移）

26. `t_pay_order` — uk_pay_no+uk_order_no+version+状态超时索引，核心约束完整，仅缺显式 COLLATE。
27. `t_pay_channel_flow` — version+uk(channel_code,channel_order_no) 幂等；order_no 无索引（M-3）、paid_fen 命名误导（L-3）、UK 含可空列（L-5）。
28. `t_pay_refund` — version+uk_refund_no+pay_no/order_no/aftersale_no/status 索引，退款主表合规。
29. `t_pay_refund_split` — 拆分流水 UK 幂等正确，无 version 靠状态条件更新（M-2/L-5）。
30. `t_pay_notify_log` — uk(channel_code,notify_id) 回调幂等到位；updateResult 漏 deleted（M-4，实际无害）。
31. `t_pay_recon_batch` — uk_batch_no+uk(date,channel) 防重复对账，结构合规。
32. `t_pay_recon_diff` — uk(batch_no,channel_code,channel_txn_no) 差错去重，索引覆盖对账处理路径。
33. `t_pay_mq_consume` — 全仓唯一 uk(consumer_group,event_id) 设计最严谨；payload/error_msg 便于排障。

### shop_settlement（11）

34. `t_sett_merchant` — 雪花主键+version，保证金/佣金率字段规范，无业务 UK（id 即商户号）合理。
35. `t_sett_account` — uk(owner_id,role_type)+version+三余额行锁更新，账户表范式正确。
36. `t_sett_account_flow` — uk(biz_no,change_type)+uk_flow_no 双重幂等，append 流水无 version 合理。
37. `t_sett_clearing` — version+uk_order_no+uk_clearing_no+(merchant,stage,due_date) 索引，清算核心表完整。
38. `t_sett_clearing_reverse` — uk_refund_no 冲正幂等，瀑布扣回字段齐全，无 version 靠 UK 幂等（M-2）。
39. `t_sett_statement` — version+uk_statement_no+uk(merchant_id,period_date) 防周期重复出账。
40. `t_sett_withdraw` — version+uk_withdraw_no+(merchant,apply_date)/status 索引，提现主表合规。
41. `t_sett_withdraw_daily_count` — uk(merchant_id,stat_month,stat_date) 月免费笔数/日限额口径唯一。
42. `t_sett_withdraw_auto_config` — version+uk_merchant_id 一商户一配置，合规。
43. `t_sett_deposit_log` — uk_log_no+merchant/biz 索引，保证金流水 append 合规。
44. `t_sett_mq_consume` — uk_event_id 幂等；consumer_group 未入 UK（M-6），状态码 1成功/0失败 与营销域相反。

### shop_marketing（16；带 version 的 8 个业务实体均无 @Version，见 M-1）

45. `t_promo` — 满减活动主表，version 有列无 @Version，状态条件更新兜底。
46. `t_promo_level` — 活动阶梯，随主活动级联管理，无 version/UK（配置行）可接受。
47. `t_promo_target` — 活动定向行，无 UK，配置覆盖写，低频可接受。
48. `t_coupon` — 券模板条件扣减库存设计正确（increaseReceived 带 status/deleted/总量条件），decreaseReceived 漏 deleted（M-4）。
49. `t_coupon_target` — 券定向行，无 version，配置类低频写。
50. `t_user_coupon` — uk_issue_request 四重防重设计好；缺 (user_id,coupon_id) 索引致限领校验回表（M-3）。
51. `t_activity` — 活动主表，version 有列无 @Version（M-1）。
52. `t_seckill_sku` — 秒杀库存靠 stock>=qty 条件扣减兜底，INT 库存列与 version 均在但 @Version 缺失。
53. `t_seckill_order` — uk_order_no 占位幂等，无 version 靠状态机（M-2）。
54. `t_groupbuy` — version+uk_group_no，拼团主表合规（@Version 缺失）。
55. `t_groupbuy_member` — uk(activity_id,user_id)+uk_order_no 防一人多参/重复下单，无 version（M-2）。
56. `t_presale_order` — version+uk_order_no，预售占位表合规（@Version 缺失）。
57. `t_bargain_record` — version+uk(user_id,activity_id) 一用户一砍价记录（@Version 缺失）。
58. `t_lottery_record` — 仅有普通索引无业务唯一键，重复投递有重复中奖面（L-8）。
59. `t_marketing_lock` — uk_order_no 作为营销四接口的幂等锚点，表结构即设计意图，无 version（M-2）。
60. `t_marketing_mq_consume` — uk_event_id 幂等；status 0成功/1失败 与其他库相反（M-6），实体漏映射 deleted（L-1）。

### shop_aftersale（11）

61. `t_aftersale_order` — uk_aftersale_no+version+7 个业务索引，售后主表完整；status INT 与实体匹配，refund_no 无索引暂无害（L-9）。
62. `t_aftersale_item` — uk(aftersale_no,order_item_id) 防同明细重复行，明细累计靠 ref 表守恒，无 version 可接受。
63. `t_aftersale_window` — uk_order_no 事件投影，无 version（重放靠 UK 覆盖语义，M-2）。
64. `t_aftersale_order_item_ref` — uk_order_item 保证一明细一投影，可退余额守恒的锚点表，无 version（M-2）。
65. `t_aftersale_refund` — uk_refund_no+uk_aftersale_no 双唯一，退款状态机条件更新正确，无 version（M-2）。
66. `t_aftersale_dispute` — uk_aftersale_no 一单仅一介入单，举证/裁决截止索引服务超时任务。
67. `t_aftersale_evidence` — 凭证 append 表，(aftersale_no,side) 索引即可，无 UK 合理。
68. `t_aftersale_insurance` — uk_order_no 单单一次理赔；refund_time NOT NULL 偏紧（L-6）。
69. `t_aftersale_price_protect` — uk_order_no 单单一次价保，差价快照字段规范。
70. `t_aftersale_status_log` — append 状态日志，仅 idx_aftersale_no，无 UK 合理。
71. `t_aftersale_mq_consume` — uk_event_id+idx_biz_no，精简列与实体（@TableId AUTO、仅 createTime）精确一致。

---

## 四、统计

| 指标 | 结果 |
|---|---|
| 审计表数 | **71**（user 11 / product 8 / order 6 / pay 8 / settlement 11 / marketing 16 / aftersale 11） |
| 实体映射覆盖 | 71/71 实体完成双向机械比对；一致 70，不一致 1（L-1） |
| 活库核对覆盖 | **71/71 = 100%** SHOW CREATE TABLE 逐张比对 + information_schema 聚合核验；结构一致 70，漂移 1 类（H-1，涉 8 表） |
| 问题总数 | **20**：阻断 1 / 高 2 / 中 7 / 低 10 |
| InnoDB | 71/71 |
| utf8mb4 | 71/71（unicode_ci 63；0900_ai_ci 8，H-1） |
| 表注释完整 | 71/71；金额（*_fen）列 BIGINT 与注释覆盖 100% |
| MQ 幂等表 | 7/7 库具备且均有 event_id 唯一键（口径不统一见 M-6） |
| 物理外键/级联/物理删除 SQL/无 WHERE 更新删除 | 0 / 0 / 0 / 0 |
| SELECT * | 31 处（M-5） |

**总体评价**：数据层规范度高——金额分位 BIGINT、单据号 UK、状态机条件更新、MQ event_id 幂等、全 InnoDB/utf8mb4、注释完整、活库与文件 70/71 完全一致；上线前必须处理 P0-1（购物车/收藏重加购必现故障）与 H-1（支付库 collation 漂移），H-2 及中低问题可按迭代收敛。

---

## 修复记录（2026-09-16）

修复人：数据库修复 lane（仅改 sql/ 与本报告问题直接对应的实体/Mapper/删除语句，未触碰 application.yml、deploy、framework/common/gateway 及其他模块 service/controller）。

**核心设计**：`deleted` 列语义由「0/1」改为「0=未删除；逻辑删除时写入该行雪花 id」。存活行 deleted=0 唯一；每条历史删除行 deleted=各自不同（即主键 id），因此 `(业务键, deleted)` 唯一键下删除→新建永不撞键，且删除值与行同生命周期、同毫秒批量删除也不冲突（优于毫秒时间戳）。BaseEntity 的 `@TableLogic` 是全局 Integer/0/1 语义且子类无法安全覆盖字段级注解，故删除点统一改为显式 `LambdaUpdateWrapper.setSql("deleted = id")`（MP 仍自动追加 `AND deleted=0`），共 6 处。

| 问题编号 | 修法 | 活库 ALTER 语句 | 验证结果 | 遗留 |
|---|---|---|---|---|
| P0-1 | t_order_cart/t_order_favorite：deleted TINYINT→BIGINT，uk_user_sku 重建为 (user_id,sku_id,deleted)，注释更新（sql/order/V2__order.sql）；CartServiceImpl.java 3 处删除（delete/moveToFavorite/clearInvalid）与 OrderPersister.java 下单清空购物车共 4 处改为 `update(...).setSql("deleted = id")`；CartItem/Favorite 实体补语义 javadoc；新增回归单测 add→delete→reAdd→delete | `ALTER TABLE shop_order.t_order_cart MODIFY deleted BIGINT NOT NULL DEFAULT 0 COMMENT '...', DROP INDEX uk_user_sku, ADD UNIQUE KEY uk_user_sku (user_id, sku_id, deleted);`（favorite 同构） | lint_order 全脚本重放 OK；lint 与活库均实测「加购→删→重加购→再删」两次插入/更新成功、终态 0 存活行（存活期恰 1 行 deleted=0），活库测试数据已物理清理；CartServiceImplTest/OrderPersisterTest 同步更新且全绿 | 收藏表当前无「取消收藏」代码路径（仅 moveToFavorite 插入），DDL/实体已先行改造；实体 deleted 字段仍为 Integer（存活行恒 0，删除值走 SQL 不回填实体），无需改 BaseEntity |
| H-2 | t_product_brand/t_product_sku：deleted TINYINT→BIGINT，uk_name/uk_sku_code 本就含 deleted 无需重建（sql/product/V2__product.sql）；BrandServiceImpl.delete 与 SpuServiceImpl 两处 SKU 删除点（编辑移除、删商品）改 setSql("deleted = id")；SPU 无含 deleted 唯一键（允许重名），spuMapper.deleteById 维持原样 | `ALTER TABLE shop_product.t_product_brand MODIFY deleted BIGINT NOT NULL DEFAULT 0 COMMENT '...';`（t_product_sku 同构） | lint_product 同名品牌「建→删→建→删→再建」实测仅 1 行 deleted=0；BrandServiceImplTest/SpuServiceImplTest 更新并全绿（148 tests） | 无 |
| H-1 | sql/pay/V2__pay.sql 8 张 CREATE TABLE 全部补 `COLLATE=utf8mb4_unicode_ci` | 8 条 `ALTER TABLE shop_pay.<t> CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;` | information_schema 复核 7 库 71 表全部 utf8mb4_unicode_ci（原 8 张 0900_ai_ci 归零） | 无 |
| M-3 | t_user_coupon 加 KEY idx_user_coupon(user_id,coupon_id)（覆盖 countHeld 的 (user_id,coupon_id) 过滤）；t_pay_channel_flow 加 KEY idx_order_no(order_no)（sql/marketing、sql/pay V2） | `ALTER TABLE shop_marketing.t_user_coupon ADD INDEX idx_user_coupon (user_id, coupon_id);` `ALTER TABLE shop_pay.t_pay_channel_flow ADD INDEX idx_order_no (order_no);` | information_schema 确认两索引在位 | 无 |
| M-4 | CouponMapper.decreaseReceived 补 `AND deleted = 0`；NotifyLogMapper.updateResult 补 `AND deleted = 0` | 无需 DDL | 营销模块 68 tests 全绿；pay 改动仅注解字符串 | 无 |
| L-1 | MqConsumeLog（marketing）补 `private Integer deleted;`，与 t_marketing_mq_consume.deleted 完成 71/71 实体列 1:1 | 无（列本存在） | 模块编译通过、68 tests 全绿；自定义 insertIgnore 不写该列走 DEFAULT 0，BaseMapper.insert 省略 null 字段 | 未加 @TableLogic：append-only 表恒 0，维持与另外 3 个 MqConsumeLog 实体一致的最小映射 |
| L-3 | paid_fen 列名保留（改名波及实体/查询），注释改为「本行累计已退款金额（分）；列名为历史遗留，语义为退款非支付」（零风险注释修复） | `ALTER TABLE shop_pay.t_pay_channel_flow MODIFY paid_fen BIGINT NOT NULL DEFAULT 0 COMMENT '...';` | SHOW CREATE 与脚本一致 | 后续大版本可考虑改名 refunded_fen |
| M-7 | **评估后跳过**：全仓 grep 无 t_user 行级删除流程——「注销」仅 status=2（AuthServiceImpl 登录拦截），userMapper 无 delete/deleteById 调用（唯一 deleteById 在 AddressServiceImpl，操作 t_user_address）。当前不存在注销后同名重注册的插入路径 | 无 | 结论已 grep 实证 | 业务确认要支持账号注销后同名/同号重注册时，按 P0-1 同法改造（deleted BIGINT + uk 纳入 deleted + 注销删除点 setSql） |
| M-1（@Version） | **接受现状，不改造**：营销 8 实体 version 列保留；写路径并发性已由状态机条件 UPDATE（status=fromStatus）、库存守恒条件（stock>=qty/received_count<total_count）、UK 插入幂等三重兜底；秒杀库存另有 Redis Lua 原子扣减 + DB 条件更新。补 @Version 需 8 实体与全部自定义 UPDATE 的 WHERE 联动改造，与既有 `version=version+1` 自增写法语义重叠，回归面大且无资金安全增量；列为后续若改用 MP updateById 时的强制准入项 | — | — | — |
| M-5（SELECT *） | **不改**：31 处均为按主键/唯一键/索引取整行并直接映射实体，已完成 71 实体↔列 1:1 双向审计；显式列名在当前手写 SQL 风格下列演进时更易漏映射，收益/风险不匹配 | — | — | 新增查询规范上要求显式列；实体加列时保持列默认值兼容 |
| L-2（主键三套） | **不改**：雪花/BIGINT AUTO_INCREMENT/BIGINT UNSIGNED AUTO_INCREMENT 三套并存但跨库无 FK、各域以单据号 UK 对外交互；BIGINT UNSIGNED→Long 的理论溢出在自增量级不可达；统一需动 pay/aftersale/settlement 多实体与全部 DDL | — | — | 提示性风险，随分库分表规划一并处理 |

**全量验证**：

1. 7 个 V2 脚本（user/product/order/pay/settlement/marketing/aftersale）全部以 `--default-character-set=utf8mb4` 重放到一次性 lint_<domain> 库，0 报错。
2. lint 与活库 71 张表逐张 SHOW CREATE 比对：剥离 COMMENT 文本后**结构零漂移**（列/类型/可空/默认/键/引擎/字符集/排序规则全一致）。
3. 测试（`mvn -pl <module> test`，未 -am、未 install 任何其他模块）：shop-order-service **154/0**（19 个测试类，含新增 P0-1 回归用例 `add_delete_reAddSameSku_deleteAgain_bothSucceedAndDeletedWritesRowId`，CartServiceImplTest 19/0、OrderPersisterTest 3/0）；shop-product-service **148/0**（SpuServiceImplTest 31/0、BrandServiceImplTest 8/0）；shop-marketing-service **68/0**（CouponMapper/MqConsumeLog 改动在内）；shop-pay-service **62/0**（NotifyLogMapper 改动在主代码中编译通过，PaymentServiceImplTest 以 Mockito mock 该 Mapper 不受注解 SQL 影响）；shop-user-service 本 lane 零代码改动（M-7 跳过），无需复跑。修复窗口内其他 lane 正在编辑 shop-common（新增 JsonViews 尚未 install）及 pay 签名轮换特性（期间出现的短暂编译中断均已随其提交消失，最终 pay 全绿），与本次改动无关。
4. 遗留环境观察（非本次引入）：活库 6 个业务库（shop_pay 因脚本含 SET NAMES 除外）的历史中文注释为双重编码存储（本次未触碰的 t_user 等表同样如此，系活库初始导入会话字符集所致）；本次新增/修改的 5 条注释均以 utf8mb4 连接写入、编码正确。建议后续择期用带 `SET NAMES utf8mb4` 的连接统一重灌注释。

