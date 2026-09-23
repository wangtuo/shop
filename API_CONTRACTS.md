# 跨域 API 契约文档（shop-api）

> 版本 V2.0 · Java 17 · Spring Boot 3.2 · Spring Cloud 2023
> 生成者：子代理 #7（跨域契约一致性审查）· 生成日期 2026-09-16
> 依据：`CONTRACTS.md` §3/§4/§5/§6、`design.md`、`shop-common` 中 `MqTopics`/`BaseEvent`/`Result`
> 用途：**Wave-2 各服务实现代理的工作依据**。各服务必须在下文列出的 `@FeignClient` 同路径
> （`/inner/<domain>`）实现内部 Controller，方法签名逐字一致；MQ 消费者按事件类字段反序列化。

## 0. 全局约定

| 约定 | 内容 |
|------|------|
| 包结构 | `com.shop.api.<domain>.{client,dto,event,enums}`；**api 各域包之间禁止互相 import**，DTO/事件字段自包含；仅可引用 `com.shop.common`（已全量静态扫描确认 0 处跨域 import） |
| Feign 注解 | `@FeignClient(name="shop-<domain>-service", path="/inner/<domain>")`；查询 `@GetMapping` + `@RequestParam("xxx")`，写操作 `@PostMapping` + `@Valid @RequestBody` |
| 返回包装 | 所有 Feign 方法返回 `com.shop.common.result.Result<T>` |
| 金额 | 库表/DTO/事件/计算金额一律 `Long`，单位**分**，禁止 double/float；折扣率、积分倍率、好评率等比率字段允许 `BigDecimal` |
| DTO 规范 | `@Data @NoArgsConstructor @AllArgsConstructor @Builder` 四注解齐全，`implements Serializable`，`serialVersionUID=1L`；集合字段 `@Builder.Default` 初始化为空集合（已全量检查通过） |
| 校验注解 | 统一 `jakarta.validation.constraints.*`（已确认无 javax.validation）；Feign 方法体参数带 `@Valid` 级联校验 |
| 事件基类 | 所有事件 `extends com.shop.common.model.BaseEvent`，自带 `eventId`（幂等键）、`occurredAt`、`bizNo`；消费者以 eventId/业务单号幂等，**禁止回查生产方库** |
| 无 Feign 域 | settlement / aftersale 不提供 Feign（CONTRACTS §3 末），对外完全事件驱动 |
| 单号规则 | 订单号 18 位（YYMMDD+业务类型2位+用户ID后4位+6位序列）；支付单 `P`+17 位；退款单 `R`+17 位；售后单 `AS`+yyyyMMdd+10 位序列；清算单 `CL`、结算单 `ST`、提现单 `WD` 开头（CONTRACTS §6） |

## 0.1 Topic 全量映射（以 `com.shop.common.constant.MqTopics` 常量为准）

| MqTopics 常量 | Topic 字面值 | 事件类 | 生产者 | 消费者 |
|---|---|---|---|---|
| `ORDER_CREATED` | `shop_order_created` | `com.shop.api.order.event.OrderCreatedEvent` | order | product（锁库存）、marketing（锁券/秒杀确认） |
| `ORDER_CANCELLED` | `shop_order_cancelled` | `com.shop.api.order.event.OrderCancelledEvent` | order | product（释放库存）、marketing（释放券/资源）、user（释放冻结积分） |
| `ORDER_PAID` | `shop_order_paid` | `com.shop.api.pay.event.PaymentSucceededEvent` | pay | order→待发货；product 锁定转占用；marketing 核销；user 扣冻结积分+发积分成长值；settlement 登记待清算 |
| `ORDER_SHIPPED` | `shop_order_shipped` | `com.shop.api.order.event.OrderShippedEvent` | order | （无强消费者，供物流/对账订阅） |
| `ORDER_CONFIRMED` | `shop_order_confirmed` | `com.shop.api.order.event.OrderConfirmedEvent` | order | settlement（生成待结算） |
| `ORDER_COMPLETED` | `shop_order_completed` | `com.shop.api.order.event.OrderCompletedEvent` | order | settlement（B 级商户售后期满转可提现） |
| `REFUND_SUCCESS` | `shop_refund_success` | `com.shop.api.pay.event.RefundSucceededEvent` | pay | settlement（清算冲正）、user（退积分/余额到账）、order（回写售后结果） |
| `AFTERSALE_CHANGED` | `shop_aftersale_changed` | `com.shop.api.aftersale.event.AftersaleChangedEvent` | aftersale | order（明细售后状态）、product（买家责任退货入库） |
| `STOCK_WARNING` | `shop_stock_warning` | `com.shop.api.product.event.StockWarningEvent` | product | 落预警表 + 日志告警 |
| `SECKILL_EVENT` | `shop_seckill_event` | `com.shop.api.marketing.event.SeckillEvent` | marketing | 域内秒杀库存状态推进 + 对账 |
| `GROUPBUY_EVENT` | `shop_groupbuy_event` | `com.shop.api.marketing.event.GroupbuyEvent` | marketing | 域内拼团状态推进（成团/失败退款）+ 对账 |
| `PRESALE_EVENT` | `shop_presale_event` | `com.shop.api.marketing.event.PresaleEvent` | marketing | 域内预售状态推进 + 尾款超时 |
| `POINTS_CHANGED` | `shop_points_changed` | `com.shop.api.user.event.PointsChangedEvent` | user | 域内状态推进 + 积分对账单据 |
| `CLEARING_REGISTER` | `shop_clearing_register` | `com.shop.api.settlement.event.ClearingRegisteredEvent` | settlement | 对账单据/账户记账 |
| `CLEARING_SETTLE` | `shop_clearing_settle` | `com.shop.api.settlement.event.SettlementCompletedEvent` | settlement | 商户可提现余额记账/通知 |
| `CLEARING_REVERSE` | `shop_clearing_reverse` | **无事件类**（CONTRACTS §5：settlement 消费 REFUND_SUCCESS 内部冲正，不直接发） | - | - |
| `PAY_RESULT` | `shop_pay_result` | shop-api 无事件类（支付域内部：渠道回调原始结果） | pay | pay 域内 |
| （暂无常量，见 §9 缺口 G1） | 待定 | `com.shop.api.settlement.event.DepositAlertEvent` | settlement | 商户通知/限提管控 |
| （暂无常量，见 §9 缺口 G1） | 待定 | `com.shop.api.settlement.event.WithdrawResultEvent` | settlement | 商户通知/账户回退 |

延时秒级常量（`MqTopics`，RocketMQ deliverAfter）：`DELAY_15_MIN_SECONDS=900`、`DELAY_30_MIN_SECONDS=1800`、`DELAY_24_HOUR_SECONDS=86400`、`DELAY_10_DAY_SECONDS`、`DELAY_15_DAY_SECONDS`。消费者 group 命名 `cg_<域>_<动作>`。

---

# 1. 用户域 user（shop-user-service :8081，/inner/user）

## 1.1 Feign 方法（UserClient）

接口：`com.shop.api.user.client.UserClient`，`@FeignClient(name="shop-user-service", path="/inner/user")`。
全部写操作以命令内 `bizNo` 为幂等键。

| HTTP | 路径 | 方法签名 | 语义 |
|---|---|---|---|
| GET | `/inner/user/get` | `Result<UserDTO> getUser(@RequestParam("userId") Long userId)` | 查询用户基础信息 |
| GET | `/inner/user/level` | `Result<UserLevelDTO> getLevel(@RequestParam("userId") Long userId)` | 查询会员等级与权益 |
| GET | `/inner/user/address` | `Result<AddressDTO> getAddress(@RequestParam("addressId") Long addressId)` | 查询收货地址 |
| POST | `/inner/user/points/lock` | `Result<Void> lockPoints(@Valid @RequestBody PointsLockCommand cmd)` | 下单预扣（冻结）积分 |
| POST | `/inner/user/points/deduct` | `Result<Void> deductPoints(@Valid @RequestBody PointsDeductCommand cmd)` | 支付成功扣冻结 |
| POST | `/inner/user/points/release` | `Result<Void> releasePoints(@Valid @RequestBody PointsReleaseCommand cmd)` | 取消释放冻结 |
| POST | `/inner/user/points/refund` | `Result<Void> refundPoints(@Valid @RequestBody PointsRefundCommand cmd)` | 退款按比例退回积分 |
| POST | `/inner/user/points/grant` | `Result<Void> grantPoints(@Valid @RequestBody GrantPointsCommand cmd)` | 消费/评价/签到等发放积分 |
| POST | `/inner/user/growth/add` | `Result<Void> addGrowth(@Valid @RequestBody GrowthCommand cmd)` | 增加成长值 |
| POST | `/inner/user/balance/debit` | `Result<Void> debitBalance(@Valid @RequestBody AmountCommand cmd)` | 余额支付扣款 |
| POST | `/inner/user/balance/credit` | `Result<Void> creditBalance(@Valid @RequestBody AmountCommand cmd)` | 退款入余额 |
| POST | `/inner/user/gift/debit` | `Result<Void> debitGift(@Valid @RequestBody AmountCommand cmd)` | 赠金扣款 |

## 1.2 DTO

### UserDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| userId | Long | 用户 ID（雪花 ID） |
| username | String | 登录用户名 |
| nickname | String | 昵称 |
| avatar | String | 头像 URL |
| phone | String | 手机号（加密/脱敏存储） |
| userType | Integer | 用户类型：-1 游客 0 普通 1 商户 2 平台运营 |
| level | Integer | 会员等级：L0=0 … L4=4 |
| status | Integer | 账户状态：0 正常 1 冻结 2 注销 |
| growth | Long | 成长值（消费 1 元=1） |

### UserLevelDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| userId | Long | 用户 ID |
| level | Integer | 会员等级 0-4 |
| levelName | String | 等级名称（新会员/银卡/金卡/白金/钻石） |
| growth | Long | 当前成长值 |
| discount | BigDecimal | 等级折扣：1.00/0.98/0.95/0.92/0.90 |
| pointsRate | BigDecimal | 积分倍率：1/1.1/1.5/2/3 |

### AddressDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| addressId | Long | 地址 ID |
| userId | Long | 所属用户 ID |
| receiver | String | 收货人 |
| phone | String | 收货人手机号 |
| province | String | 省 |
| city | String | 市 |
| district | String | 区/县 |
| detailAddress | String | 详细地址 |
| zipCode | String | 邮编 |
| tag | String | 标签（家/公司/学校/其他） |
| isDefault | Integer | 是否默认：0 否 1 是（每用户至多 1 个，地址上限 20 个） |

### PointsLockCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | @NotNull | 用户 ID |
| bizNo | String | @NotBlank | 订单号，幂等键 |
| points | Long | @NotNull | 冻结积分个数 |
| deductFen | Long | @NotNull | 积分抵现金额（分）；100 积分=100 分=1 元，单笔封顶订单金额 50% |
| scene | Integer | - | 积分场景，见 PointsScene |

### PointsDeductCommand / PointsReleaseCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | @NotNull | 用户 ID |
| bizNo | String | @NotBlank | 订单号，幂等键（与冻结记录一一对应） |

### PointsRefundCommand
| 字段 | 类型 | 含义 |
|---|---|---|
| userId | Long | 用户 ID |
| bizNo | String | 退款单号，幂等键 |
| points | Long | 按退款比例计算后实际退回的积分个数 |

### GrantPointsCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | - | 用户 ID |
| bizNo | String | - | 订单号/签到流水等，幂等键 |
| points | Long | - | 发放积分个数（已乘等级倍率的最终值） |
| scene | Integer | @NotNull | 获取场景，见 PointsScene |

### GrowthCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | - | 用户 ID |
| bizNo | String | - | 订单号/评价单/签到流水，幂等键 |
| growth | Integer | @NotNull | 本次增加成长值（正数；消费 1 元=1，评价+10，晒单+20，连续签到 7 天+50） |
| scene | Integer | - | 场景，见 GrowthScene |

### AmountCommand（余额扣款 / 余额入账 / 赠金扣款共用）
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | @NotNull | 用户 ID |
| bizNo | String | @NotBlank | 支付单号/退款单号，幂等键 |
| amountFen | Long | @NotNull | 变动金额（分，恒正数，方向由方法语义决定） |
| remark | String | - | 备注 |

## 1.3 事件

### PointsChangedEvent → Topic `POINTS_CHANGED`（shop_points_changed）
基类字段：eventId、occurredAt、bizNo。

| 字段 | 类型 | 含义 |
|---|---|---|
| userId | Long | 用户 ID |
| changeType | Integer | 1 获取 2 消耗 3 冻结 4 释放 5 退回 6 过期清零（PointsChangeType） |
| points | Long | 本次变动积分个数（正数，方向由 changeType 表达） |
| balanceAfter | Long | 变动后可用积分余额 |
| scene | Integer | 业务场景（PointsScene） |
| bizNo | String | 业务单号（显式固定契约字段，与 BaseEvent.bizNo 同一序列化属性） |

## 1.4 码值

- **UserTypes 用户类型**：`-1 VISITOR 游客 / 0 NORMAL 普通 / 1 MERCHANT 商户 / 2 PLATFORM 平台运营`
- **UserStatuses 账户状态**：`0 NORMAL 正常 / 1 FROZEN 冻结 / 2 CANCELLED 注销`
- **MemberLevels 会员等级**：`L0=0 / L1=1 / L2=2 / L3=3 / L4=4`；成长下限 `0/100/1000/5000/20000`；折扣 `1.00/0.98/0.95/0.92/0.90`；积分倍率 `1/1.1/1.5/2/3`；工具方法 `ofGrowth(long)`、`nameOf`、`discountOf`、`pointsRateOf`
- **AccountTypes 账户类型**：`1 BALANCE 余额 / 2 GIFT 赠金 / 3 POINTS 积分 / 4 COUPON 优惠券`
- **PointsScene 积分获取场景**：`1 CONSUME 消费 / 2 SIGN 签到 / 3 COMMENT 评价 / 4 SHARE 分享 / 5 SHOW_ORDER 晒单 / 6 COMPENSATE 系统补偿`
- **PointsChangeType 积分变动类型**：`1 EARN 获取 / 2 CONSUME 消耗 / 3 FREEZE 冻结 / 4 RELEASE 释放 / 5 REFUND 退回 / 6 EXPIRE 过期清零`
- **GrowthScene 成长值场景**：`1 CONSUME 消费 / 2 COMMENT 评价 / 3 SHOW_ORDER 晒单 / 4 SIGN_WEEK 连续签到7天`

---

# 2. 商品域 product（shop-product-service :8082，/inner/product）

## 2.1 Feign 方法（ProductClient）

接口：`com.shop.api.product.client.ProductClient`，`@FeignClient(name="shop-product-service", path="/inner/product")`。
库存写操作以 orderNo + items 幂等。

| HTTP | 路径 | 方法签名 | 语义 |
|---|---|---|---|
| GET | `/inner/product/sku` | `Result<SkuDTO> getSku(@RequestParam("skuId") Long skuId)` | 查单个 SKU |
| POST | `/inner/product/sku/list` | `Result<List<SkuDTO>> listSkus(@RequestBody List<Long> skuIds)` | 批量查 SKU（空入参返回空集合） |
| POST | `/inner/product/stock/lock` | `Result<Void> lockStock(@Valid @RequestBody StockLockCommand cmd)` | TCC-try：可售→锁定 |
| POST | `/inner/product/stock/confirm` | `Result<Void> confirmDeduct(@Valid @RequestBody StockDeductCommand cmd)` | TCC-confirm：锁定→占用（支付成功） |
| POST | `/inner/product/stock/release` | `Result<Void> releaseStock(@Valid @RequestBody StockReleaseCommand cmd)` | TCC-cancel：锁定→可售（取消/超时） |
| POST | `/inner/product/stock/return` | `Result<Void> returnStock(@Valid @RequestBody StockReturnCommand cmd)` | 售后回库（买家责任回可售，质量问题入残次） |
| GET | `/inner/product/stock/saleable` | `Result<Boolean> saleable(@RequestParam("skuId") Long skuId, @RequestParam("qty") Integer qty)` | 可售库存是否充足（商品须已上架） |

## 2.2 DTO

### SkuDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| skuId | Long | SKU ID |
| spuId | Long | 所属 SPU ID |
| skuCode | String | SKU 编码 |
| merchantId | Long | 商家 ID |
| shopId | Long | 店铺 ID |
| spuName | String | SPU 名称（冗余） |
| skuName | String | SKU 名称 |
| specText | String | 规格文本，如 "颜色:红色;尺码:M" |
| image | String | 主图 URL |
| salePriceFen | Long | 销售价（分） |
| marketPriceFen | Long | 市场价/吊牌价（分） |
| memberPriceFen | Long | 会员价（分） |
| promotionPriceFen | Long | 促销价（分） |
| seckillPriceFen | Long | 秒杀价（分） |
| costPriceFen | Long | 成本价（分） |
| availableStock | Long | 可售库存 |
| lockedStock | Long | 锁定库存（下单未支付） |
| occupiedStock | Long | 占用库存（已支付待发货） |
| warnThreshold | Long | 预警阈值，默认 10 |
| status | Integer | 商品状态 0-7（GoodsStatuses） |
| category3Id | Long | 三级类目 ID |
| weightGram | Integer | 重量（克） |
| volumeCc | Integer | 体积（立方厘米） |
| barcode | String | 条码（69 码） |
| presaleFlag | Integer | 是否预售：0 否 1 是 |
| stockType | Integer | 库存类型 1-4（StockTypes） |

### SpuDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| spuId | Long | SPU ID |
| merchantId | Long | 商家 ID |
| shopId | Long | 店铺 ID |
| name | String | 商品名称 |
| brandId | Long | 品牌 ID |
| category3Id | Long | 三级类目 ID |
| mainImage | String | 主图 URL |
| images | List\<String\> | 轮播图 URL（默认空集合） |
| status | Integer | 商品状态 0-7 |
| sales | Long | 累计销量 |
| goodCommentCount | Long | 好评数（4+5 星） |
| totalCommentCount | Long | 总评价数 |
| goodRate | BigDecimal | 好评率=好评数/总评价数（比率字段，非金额） |

### StockLockCommand / StockDeductCommand / StockReleaseCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| orderNo | String | @NotBlank | 订单号（幂等键） |
| orderType | Integer | - | 订单类型 1-5 |
| items | List\<StockItemCommand\> | @NotEmpty @Valid | 库存操作明细（默认空集合） |

### StockReturnCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| orderNo | String | @NotBlank | 订单号（幂等键） |
| items | List\<StockItemCommand\> | @NotEmpty @Valid | 回库明细 |
| reason | Integer | - | 回库原因 1 买家责任 2 质量问题 3 换货（StockReturnReasons） |

### StockItemCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| skuId | Long | @NotNull | SKU ID |
| qty | Integer | @NotNull @Min(1) | 操作数量（正整数） |
| stockType | Integer | - | 库存类型 1-4，缺省按普通库存 |
| activityId | Long | - | 关联活动 ID（秒杀/拼团/预售），普通订单为空 |

## 2.3 事件

### StockWarningEvent → Topic `STOCK_WARNING`（shop_stock_warning）
| 字段 | 类型 | 含义 |
|---|---|---|
| skuId | Long | SKU ID（§5 要求字段） |
| spuId | Long | SPU ID（扩展） |
| merchantId | Long | 商家 ID（扩展） |
| available | Long | 触发时可售库存（§5 要求字段） |
| threshold | Long | 预警阈值（§5 要求字段，默认 10） |

触发：可售库存 ≤ 阈值；库存为 0 自动下架（售罄 5），补货后自动上架。

## 2.4 码值

- **GoodsStatuses 商品状态**：`0 DRAFT 草稿 / 1 PENDING_AUDIT 待审核 / 2 AUDIT_REJECT 审核拒绝 / 3 ON_SALE 已上架 / 4 OFF_SALE 已下架 / 5 SOLD_OUT 售罄 / 6 VIOLATION_OFF 违规下架 / 7 DELETED 已删除`（枚举，`fromCode` 非法抛异常）
- **StockTypes 库存类型**：`1 NORMAL 普通 / 2 PRESALE 预售 / 3 SECKILL 秒杀 / 4 GROUPBUY 拼团`
- **StockLockStatuses 库存锁定状态**：`0 LOCKED 锁定中 / 1 DEDUCTED 已扣减 / 2 RELEASED 已释放 / 3 RETURNED 已回库`
- **StockReturnReasons 回库原因**：`1 BUYER 买家责任（回可售） / 2 QUALITY 质量问题（入残次） / 3 EXCHANGE 换货`

---

# 3. 营销域 marketing（shop-marketing-service :8083，/inner/marketing）

## 3.1 Feign 方法（MarketingClient）

接口：`com.shop.api.marketing.client.MarketingClient`，`@FeignClient(name="shop-marketing-service", path="/inner/marketing")`。
lock/confirm/release 均以 orderNo 幂等。

| HTTP | 路径 | 方法签名 | 语义 |
|---|---|---|---|
| POST | `/inner/marketing/calculate` | `Result<PriceCalcResult> calculate(@Valid @RequestBody PriceCalcCommand cmd)` | 确认订单页/下单前试算（不落库、不锁资源） |
| POST | `/inner/marketing/lock` | `Result<Void> lockPromotion(@Valid @RequestBody PromotionLockCommand cmd)` | 预核销券/锁秒杀、拼团、预售资源 |
| POST | `/inner/marketing/confirm` | `Result<Void> confirmPromotion(@Valid @RequestBody PromotionConfirmCommand cmd)` | 支付成功核销券/秒杀扣减 |
| POST | `/inner/marketing/release` | `Result<Void> releasePromotion(@Valid @RequestBody PromotionReleaseCommand cmd)` | 取消/超时释放（未找到锁定记录也返回成功） |

## 3.2 DTO

### PriceCalcCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | - | 用户 ID（游客可空） |
| userLevel | Integer | @NotNull | 会员等级 0-4 |
| orderType | Integer | @NotNull | 订单类型 1-5 |
| items | List\<CalcItem\> | @NotEmpty @Valid | 试算商品行 |
| freightFen | Long | @NotNull | 原始运费（分），免邮券作用于该金额 |
| usePointsFen | Long | - | 拟积分抵现金额（分，封顶商品金额 50%，拼团不可用） |
| categoryCouponId | Long | - | 品类券（用户券记录 ID） |
| shopCouponId | Long | - | 店铺券 ID |
| platformCouponId | Long | - | 平台通用券 ID |
| seckillActivityId | Long | - | 秒杀活动 ID（orderType=2 必填） |
| groupbuyActivityId | Long | - | 拼团活动 ID（orderType=3 必填） |
| presaleActivityId | Long | - | 预售活动 ID（orderType=4 必填） |
| presaleFinalStage | Boolean | - | true=尾款阶段（券可用），false/null=定金阶段（券不可用） |

### CalcItem
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| skuId | Long | @NotNull | SKU ID |
| spuId | Long | - | SPU ID |
| merchantId | Long | - | 商户 ID |
| shopId | Long | - | 店铺 ID |
| category3Id | Long | - | 三级类目 ID（品类券作用域） |
| qty | Integer | @NotNull @Min(1) | 购买数量 |
| salePriceFen | Long | @NotNull | 当前销售单价（分）；活动价由订单域上送 |

### PriceCalcResult
| 字段 | 类型 | 含义 |
|---|---|---|
| originalProductFen | Long | 商品原价总额（分） |
| productPromoFen | Long | 第 1 层商品级优惠（限时折扣/秒杀，分） |
| shopPromoFen | Long | 第 2 层店铺级优惠（满减/满折/第N件，分） |
| categoryCouponFen | Long | 第 3 层品类券优惠（分） |
| shopCouponFen | Long | 第 4 层店铺券优惠（分） |
| platformCouponFen | Long | 第 5 层平台通用券优惠（分） |
| freightCouponFen | Long | 免邮券抵扣运费金额（分） |
| pointsDeductFen | Long | 第 6 层积分抵现（分，封顶 50%） |
| freightFen | Long | 最终应付运费（分）=原始运费-免邮券抵扣 |
| payFen | Long | 整单应付金额（分） |
| itemDetails | List\<ItemPriceDetail\> | SKU 级分摊明细（默认空集合） |
| usedUserCouponIds | List\<Long\> | 实际生效用户券 ID（下单锁定时回传） |
| giftSkuIds | List\<Long\> | 满赠命中赠品 SKU ID |
| snapshotJson | String | 价格快照 JSON（随订单落库，支付/售后以此为准） |

金额恒等式：`payFen = originalProductFen - productPromoFen - shopPromoFen - categoryCouponFen - shopCouponFen - platformCouponFen - pointsDeductFen + freightFen`（freightFen 已扣免邮券）。

### ItemPriceDetail
| 字段 | 类型 | 含义 |
|---|---|---|
| skuId | Long | SKU ID |
| qty | Integer | 数量 |
| originalFen | Long | 原价小计 salePriceFen×qty（分） |
| productPromoFen | Long | 商品级优惠分摊（分） |
| shopPromoFen | Long | 店铺级优惠分摊（分） |
| couponAllocFen | Long | 三类券优惠分摊合计（分） |
| pointsAllocFen | Long | 积分抵现分摊（分） |
| freightAllocFen | Long | 运费分摊（分） |
| paidFen | Long | 明细实付（分），售后退款基数 |
| giftFlag | Integer | 是否满赠赠品：1 是 0 否 |

### PromotionLockCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | @NotNull | 用户 ID |
| orderNo | String | @NotBlank | 订单号（幂等键） |
| orderType | Integer | @NotNull | 订单类型 1-5 |
| items | List\<CalcItem\> | @NotEmpty @Valid | 订单商品行（券门槛复核/库存锁定） |
| userCouponIds | List\<Long\> | - | 预核销用户券 ID（试算结果原样回传；秒杀/拼团为空） |
| activityId | Long | - | 秒杀/拼团/预售活动 ID（普通订单可空） |
| snapshotJson | String | - | 试算价格快照（锁定时复核一致性） |

### PromotionConfirmCommand / PromotionReleaseCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| userId | Long | @NotNull | 用户 ID |
| orderNo | String | @NotBlank | 订单号（幂等键） |

## 3.3 事件

### SeckillEvent → Topic `SECKILL_EVENT`（shop_seckill_event）
| 字段 | 类型 | 含义 |
|---|---|---|
| activityId | Long | 秒杀活动 ID |
| skuId | Long | SKU ID |
| userId | Long | 用户 ID |
| orderNo | String | 订单号（幂等键） |
| type | Integer | 1 锁定 2 扣减 3 释放（SeckillOpType） |
| qty | Long | 数量 |

### GroupbuyEvent → Topic `GROUPBUY_EVENT`（shop_groupbuy_event）
| 字段 | 类型 | 含义 |
|---|---|---|
| activityId | Long | 拼团活动 ID |
| groupNo | String | 团号（同团各事件共用，消息 key） |
| userId | Long | 用户 ID（团长/团员） |
| orderNo | String | 关联订单号（幂等键） |
| type | Integer | 1 开团 2 参团 3 成团 4 失败（GroupbuyOpType） |
| requiredPeople | Integer | 成团人数：2/3/5/10 |

规则：24h 有效期；同一用户同一活动仅参团 1 次；失败自动退款；拼团订单不可用券与积分。

### PresaleEvent → Topic `PRESALE_EVENT`（shop_presale_event）
| 字段 | 类型 | 含义 |
|---|---|---|
| activityId | Long | 预售活动 ID |
| userId | Long | 用户 ID |
| orderNo | String | 订单号（幂等键） |
| type | Integer | 1 定金支付 2 尾款提醒 3 取消（PresaleOpType） |
| finalPayDeadline | Long | 尾款支付截止时间（毫秒时间戳）；超时取消、定金不退 |

## 3.4 码值

- **ActivityTypes 营销活动类型**：`1 FULL_REDUCE 满减 / 2 FULL_DISCOUNT 满折 / 3 FULL_GIFT 满赠 / 4 NTH_PIECE 第N件优惠 / 5 LIMITED_DISCOUNT 限时折扣 / 10 SECKILL 秒杀 / 11 GROUPBUY 拼团 / 12 PRESALE 预售 / 13 BARGAIN 砍价 / 14 LOTTERY 抽奖`
- **CouponTypes 券类型**：`1 FULL_REDUCE 满减券 / 2 DISCOUNT 折扣券 / 3 NO_THRESHOLD 无门槛券 / 4 FREE_FREIGHT 免邮券 / 5 CATEGORY 品类券 / 6 SHOP 店铺券`
- **CouponStatuses 用户券状态**：`0 UNUSED 未使用 / 1 USED 已使用 / 2 EXPIRED 已过期 / 3 INVALID 已作废`
- **CouponIssueWays 发放方式**：`1 ACTIVE_CLAIM 主动领取 / 2 ACTIVITY 活动发放 / 3 NEW_USER 新人礼包 / 4 COMPENSATE 系统补偿 / 5 POINTS_EXCHANGE 积分兑换`
- **PromotionLayer 叠加层级**：`1 PRODUCT 商品级 / 2 SHOP 店铺级 / 3 CATEGORY_COUPON 品类券 / 4 SHOP_COUPON 店铺券 / 5 PLATFORM_COUPON 平台券 / 6 POINTS 积分抵现`
- **SeckillOpType**：`1 LOCK / 2 DEDUCT / 3 RELEASE`；**GroupbuyOpType**：`1 OPEN / 2 JOIN / 3 SUCCESS / 4 FAIL`；**PresaleOpType**：`1 DEPOSIT_PAID / 2 FINAL_REMIND / 3 CANCEL`（枚举均提供 `of(Integer)`，未知返回 null）

---

# 4. 订单域 order（shop-order-service :8084，/inner/order）

## 4.1 Feign 方法（OrderClient）

接口：`com.shop.api.order.client.OrderClient`，`@FeignClient(name="shop-order-service", path="/inner/order")`。

| HTTP | 路径 | 方法签名 | 语义 |
|---|---|---|---|
| GET | `/inner/order` | `Result<OrderDTO> getByOrderNo(@NotBlank @RequestParam("orderNo") String orderNo)` | 按订单号查订单聚合（含收货人、发票、明细）；不存在返回业务错误码 |

## 4.2 DTO

### OrderDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| orderNo | String | 订单号（18 位） |
| userId | Long | 下单用户 ID |
| userNickname | String | 用户昵称快照 |
| userPhone | String | 用户手机号快照 |
| orderType | Integer | 订单类型 1-5 |
| status | Integer | 订单状态 10/20/30/40/50/60/61/62/70 |
| source | Integer | 订单来源 1 APP 2 H5 3 小程序 4 PC |
| productTotalFen | Long | 商品总额（分） |
| freightFen | Long | 运费（分） |
| productDiscountFen | Long | 商品优惠金额（分） |
| shopDiscountFen | Long | 店铺优惠金额（分） |
| platformDiscountFen | Long | 平台优惠金额（分，含平台券） |
| pointsDeductFen | Long | 积分抵扣金额（分） |
| discountTotalFen | Long | 优惠总额（分） |
| payFen | Long | 实付金额（分） |
| payMethod | Integer | 支付方式（PayMethods） |
| payTransactionNo | String | 支付流水号 |
| payTime | LocalDateTime | 支付时间 |
| remark | String | 买家备注 |
| receiver | ReceiverDTO | 收货信息快照 |
| invoice | InvoiceDTO | 发票信息 |
| items | List\<OrderItemDTO\> | 订单明细（默认空集合） |
| createTime / updateTime | LocalDateTime | 创建/更新时间 |

### OrderItemDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| orderItemId | Long | 明细 ID（雪花） |
| orderNo | String | 所属订单号 |
| skuId | Long | SKU ID |
| spuId | Long | SPU ID |
| merchantId | Long | 商户 ID |
| shopId | Long | 店铺 ID |
| skuName | String | SKU 名称快照 |
| specText | String | 规格文本快照 |
| image | String | 商品主图快照 |
| priceFen | Long | 成交单价（分） |
| qty | Integer | 购买数量 |
| itemTotalFen | Long | 单价×数量小计（分） |
| discountAllocFen | Long | 商品/店铺/平台优惠分摊（分） |
| pointsAllocFen | Long | 积分抵扣分摊（分） |
| freightAllocFen | Long | 运费分摊（分） |
| paidFen | Long | 明细实付金额（分） |
| aftersaleStatus | Integer | 明细售后状态 0-6（ItemAftersaleStatuses） |

### ReceiverDTO
`receiver, phone, province, city, district, detailAddress`（均 String，下单地址快照）。

### InvoiceDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| invoiceType | Integer | 0 不开发票 1 电子普通发票 2 增值税专用发票 |
| contentScope | Integer | 1 商品明细 2 商品类别 |
| titleType | String | PERSONAL 个人 / COMPANY 企业 |
| companyName | String | 公司名称（企业抬头） |
| taxNo | String | 纳税人识别号 |
| email | String | 电子发票接收邮箱（退款自动冲红） |

## 4.3 事件（均含 BaseEvent 字段）

### OrderCreatedEvent → `ORDER_CREATED`（shop_order_created）
| 字段 | 类型 | 含义 |
|---|---|---|
| orderNo | String | 订单号 |
| userId | Long | 用户 ID |
| orderType | Integer | 订单类型 1-5 |
| status | Integer | 固定 10 待付款 |
| usedPointsFen | Long | 积分抵扣金额（分，无则 0） |
| userCouponId | Long | 用户券 ID（未用券 null） |
| freightFen | Long | 运费（分） |
| expirePaySeconds | Long | 支付超时秒数（普通 1800/秒杀 900/拼团 86400+1800/预售尾款 3 天） |
| items | List\<OrderItemMessage\> | 订单明细（默认空集合） |

### OrderCancelledEvent → `ORDER_CANCELLED`（shop_order_cancelled）
| 字段 | 类型 | 含义 |
|---|---|---|
| orderNo | String | 订单号 |
| userId | Long | 用户 ID |
| cancelType | Integer | 1 用户 2 超时 3 商家（CancelTypes；§5 必含 1/2） |
| usedPointsFen | Long | 已用积分抵扣金额（分） |
| userCouponId | Long | 已预核销用户券 ID（可空） |
| seckillActivityId | Long | 秒杀活动 ID（可空） |
| items | List\<OrderItemMessage\> | 订单明细 |

### OrderShippedEvent → `ORDER_SHIPPED`（shop_order_shipped）
`orderNo String`、`userId Long`、`logisticsNo String`、`autoConfirmDeadline Long`（毫秒时间戳，发货后 10 天）、`items List<OrderItemMessage>`。

### OrderConfirmedEvent → `ORDER_CONFIRMED`（shop_order_confirmed）
| 字段 | 类型 | 含义 |
|---|---|---|
| orderNo | String | 订单号 |
| userId | Long | 用户 ID |
| merchantId | Long | 商户 ID |
| productPayFen | Long | 商品实付金额（分） |
| freightFen | Long | 运费（分） |
| shopDiscountFen | Long | 店铺优惠金额（分，扩展） |
| platformCouponFen | Long | 平台券金额（分，扩展） |
| pointsDeductFen | Long | 积分抵扣金额（分，扩展） |
| totalPayFen | Long | 订单总实付金额（分，扩展） |
| items | List\<OrderItemMessage\> | 订单明细 |

### OrderCompletedEvent → `ORDER_COMPLETED`（shop_order_completed）
`orderNo String`、`userId Long`、`merchantId Long`。

### OrderItemMessage（事件 items 元素，Serializable，非事件）
| 字段 | 类型 | 含义 |
|---|---|---|
| skuId | Long | SKU ID |
| spuId | Long | SPU ID |
| merchantId | Long | 商户 ID |
| shopId | Long | 店铺 ID |
| category3Id | Long | 三级类目 ID（扩展） |
| qty | Integer | 购买数量 |
| salePriceFen | Long | 成交单价（分） |
| productTotalFen | Long | 单价×数量（分） |
| paidFen | Long | 明细实付金额（分，扩展） |
| seckillActivityId | Long | 秒杀活动 ID（秒杀订单） |
| groupNo | String | 拼团团号（拼团订单） |
| presaleActivityId | Long | 预售活动 ID（预售订单） |

## 4.4 码值

- **OrderTypes 订单类型**：`1 NORMAL 普通 / 2 SECKILL 秒杀 / 3 GROUPBUY 拼团 / 4 PRESALE 预售 / 5 EXCHANGE 换货`；`bizTypeCodeOf(Integer)` 返回订单号两位码 `01/02/03/04/05`
- **OrderStatuses 订单状态**：`10 WAIT_PAY 待付款 / 20 WAIT_SHIP 待发货 / 30 WAIT_RECEIVE 待收货 / 40 COMPLETED 已完成 / 50 CANCELLED 已取消 / 60 REFUNDING 退款中 / 61 RETURN_REFUNDING 退货退款中 / 62 EXCHANGING 换货中 / 70 CLOSED 已关闭`
- **CancelTypes 取消类型**：`1 USER 用户 / 2 TIMEOUT 超时 / 3 MERCHANT 商家`
- **OrderSources 订单来源**：`1 APP / 2 H5 / 3 MINI_APP 小程序 / 4 PC`
- **ItemAftersaleStatuses 明细售后状态**：`0 NONE 无 / 1 APPLYING 申请中 / 2 REFUNDING 退款中 / 3 REFUNDED 已退款 / 4 RETURNING 退货中 / 5 EXCHANGING 换货中 / 6 FINISHED 已完成`

---

# 5. 支付域 pay（shop-pay-service :8085，/inner/pay）

## 5.1 Feign 方法（PayClient）

接口：`com.shop.api.pay.client.PayClient`，`@FeignClient(name="shop-pay-service", path="/inner/pay")`。

| HTTP | 路径 | 方法签名 | 语义 |
|---|---|---|---|
| POST | `/inner/pay/payment` | `Result<PaymentDTO> createPayment(@Valid @RequestBody CreatePaymentCommand cmd)` | 创建支付单并渠道下单；按 orderNo 幂等返回原单 |
| GET | `/inner/pay/payment` | `Result<PaymentDTO> getByPayNo(@RequestParam("payNo") String payNo)` | 按支付单号查询 |
| POST | `/inner/pay/refund` | `Result<RefundDTO> refund(@Valid @RequestBody CreateRefundCommand cmd)` | 发起退款；以 refundNo 幂等，累计不超过实付金额 |

## 5.2 DTO

### CreatePaymentCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| orderNo | String | @NotBlank | 业务订单号（18 位），幂等键 |
| userId | Long | @NotNull | 付款用户 ID |
| payMethod | Integer | @NotNull | 支付方式 1-7（PayMethods） |
| amountFen | Long | @NotNull @Min(1) | 支付金额（分），须等于订单实付 |
| subject | String | - | 订单主题，透传渠道展示 |
| terminal | Integer | - | 发起终端 1-4（Terminals） |

### CreateRefundCommand
| 字段 | 类型 | 校验 | 含义 |
|---|---|---|---|
| refundNo | String | - | 退款单号（R+17 位）；空则支付域生成，非空作幂等键 |
| orderNo | String | @NotBlank | 原订单号 |
| aftersaleNo | String | - | 售后单号（售后触发必填；价保/冲正可空） |
| userId | Long | @NotNull | 退款归属用户 ID |
| amountFen | Long | @NotNull @Min(1) | 本次退款金额（分），累计不超实付 |
| payMethod | Integer | - | 退款渠道/原支付方式（PayMethods） |
| refundType | Integer | @NotNull | 1 全额 2 部分（RefundTypes） |
| source | Integer | @NotNull | 1 售后 2 价保 3 清算冲正（RefundSources） |
| operatorType | Integer | - | 操作人类型（同统一用户类型码），审计用 |
| reason | String | - | 退款原因 |

### PaymentDTO
`payNo String`、`orderNo String`、`userId Long`、`payMethod Integer`、`amountFen Long`、`status Integer`（PayStatuses 10-70）、`payUrl String`（收银台 URL）、`channelTransactionNo String`、`createTime LocalDateTime`、`payTime LocalDateTime`。

### RefundDTO
`refundNo String`、`payNo String`、`orderNo String`、`aftersaleNo String`、`userId Long`、`amountFen Long`、`payMethod Integer`、`refundType Integer`、`status Integer`（RefundStatuses 10-50）、`channelRefundNo String`、`createTime LocalDateTime`、`finishTime LocalDateTime`。

## 5.3 事件

### PaymentSucceededEvent → `ORDER_PAID`（shop_order_paid）
| 字段 | 类型 | 含义 |
|---|---|---|
| payNo | String | 支付单号（P+17 位） |
| orderNo | String | 订单号 |
| userId | Long | 付款用户 ID |
| payMethod | Integer | 支付方式（PayMethods） |
| amountFen | Long | 实付金额（分） |
| channelTransactionNo | String | 渠道交易流水号（扩展） |
| paidTime | LocalDateTime | 支付成功时间 |

### RefundSucceededEvent → `REFUND_SUCCESS`（shop_refund_success）
| 字段 | 类型 | 含义 |
|---|---|---|
| refundNo | String | 退款单号（R+17 位） |
| payNo | String | 原支付单号（扩展） |
| orderNo | String | 原订单号 |
| aftersaleNo | String | 售后单号（价保/冲正可空） |
| userId | Long | 退款归属用户 ID |
| amountFen | Long | 退款金额（分） |
| payMethod | Integer | 退款渠道/原支付方式 |
| refundType | Integer | 1 全额 2 部分 |
| refundTime | LocalDateTime | 退款成功时间（扩展） |

## 5.4 码值

- **PayMethods 支付方式**：`1 WECHAT 微信 / 2 ALIPAY 支付宝 / 3 BALANCE 余额 / 4 BANK_CARD 银行卡 / 5 UNIONPAY 云闪付 / 6 HUABEI 花呗分期 / 7 BAITIAO 白条`
- **Terminals 终端**：`1 APP / 2 H5 / 3 MINI_APP 小程序 / 4 PC`
- **PayStatuses 支付单**：`10 WAIT 待支付 / 20 PAYING 支付中 / 30 SUCCESS 成功 / 40 FAIL 失败 / 50 CLOSED 已关闭 / 60 REFUNDING 退款中 / 70 REFUNDED 已退款`
- **RefundStatuses 退款单**：`10 WAIT 待退款 / 20 PROCESSING 退款中 / 30 SUCCESS 成功 / 40 FAIL 失败 / 50 REVERSED 已冲正`
- **RefundTypes 退款类型**：`1 FULL 全额 / 2 PART 部分`
- **RefundSources 退款来源**：`1 AFTERSALE 售后 / 2 PRICE_PROTECT 价保 / 3 CLEARING 清算冲正`
- **ReconcileDiffTypes 对账差异**：`1 LONG 长款（渠道有系统无，补单补发货） / 2 SHORT 短款（系统有渠道无，未支付则关单） / 3 AMOUNT_MISMATCH 金额不符（以渠道为准调账挂账）`

---

# 6. 清算域 settlement（shop-settlement-service :8086，无 Feign）

## 6.1 DTO

### ClearingBreakdown（清算分账快照，可随事件序列化）
| 字段 | 类型 | 含义 |
|---|---|---|
| clearingNo | String | 清算单号（CL 前缀） |
| orderNo | String | 订单号 |
| merchantId | Long | 商户 ID |
| productAmountFen | Long | 商品金额（分，优惠前） |
| merchantBearDiscountFen | Long | 商户承担优惠（店铺券、商户满减等，分） |
| platformBearDiscountFen | Long | 平台承担优惠（平台券、积分抵现，分） |
| merchantReceivableFen | Long | 商户应收货款（分，已扣佣金/通道费、含运费） |
| platformCommissionFen | Long | 平台佣金（分） |
| techFeeFen | Long | 技术服务费（分，0.5 元/笔或 0.1%） |
| channelFeeFen | Long | 支付通道费（分，约 0.6%，商户承担，退款不退） |
| marketingSubsidyFen | Long | 营销补贴（平台承担优惠，分） |
| freightFen | Long | 运费（分，计入商户应收） |
| commissionRateBps | Integer | 佣金费率（万分比，10%=1000） |
| stage | Integer | 清算阶段 10/20/30/40（ClearingStages） |

分账公式（design 7.2.2）：商户应收 =（商品金额-商户承担优惠）×（1-佣金率）- 支付通道费 + 运费；平台佣金 =（商品金额-商户承担优惠）× 佣金率。

### MerchantStatementDTO
| 字段 | 类型 | 含义 |
|---|---|---|
| statementNo | String | 结算单号（ST 前缀） |
| merchantId | Long | 商户 ID |
| merchantLevel | Integer | 商户等级 0=S 1=A 2=B 3=C |
| stage | Integer | 结算阶段 20/30（ClearingStages） |
| totalFen | Long | 结算单总金额（分） |
| settledFen | Long | 已结算（可提现）金额（分） |
| freezingFen | Long | 冻结中金额（分） |
| settleTime | LocalDateTime | 结算完成时间 |

## 6.2 事件

### ClearingRegisteredEvent → `CLEARING_REGISTER`（shop_clearing_register）
消费 ORDER_PAID 后按 7.2.2 完成分账快照发出，阶段 10。
`breakdown ClearingBreakdown`（完整快照）、`orderNo String`（冗余）、`merchantId Long`（冗余）。

### SettlementCompletedEvent → `CLEARING_SETTLE`（shop_clearing_settle）
按商户等级周期到期（或 B 级 ORDER_COMPLETED）转 SETTLED(30) 可提现。
`statementNo String`、`merchantId Long`、`amountFen Long`（本次转可提现金额，分）、`settleTime LocalDateTime`。

### WithdrawResultEvent → **MqTopics 暂无对应常量（缺口 G1）**
`withdrawNo String`（WD 前缀）、`merchantId Long`、`amountFen Long`、`status Integer`（30 成功/40 失败/50 拒绝，WithdrawStatuses）、`failReason String`（失败/拒绝原因）。

### DepositAlertEvent → **MqTopics 暂无对应常量（缺口 G1）**
`merchantId Long`、`balanceFen Long`（保证金余额，分）、`thresholdFen Long`（预警阈值，通常为应缴额 50%，分）。

## 6.3 码值

- **ClearingStages 清算阶段**：`10 WAIT_CLEAR 待清算（支付成功） / 20 WAIT_SETTLE 待结算（已收货分账，冻结中） / 30 SETTLED 已结算（可提现） / 40 REVERSED 已冲正（退款）`
- **MerchantLevels 商户等级**：`0 S（T+1 结算/提现 T+0） / 1 A（T+7/T+1） / 2 B（T+15 售后期结束/T+1） / 3 C（T+30/T+3）`
- **AccountRole 账户角色**：`1 PLATFORM 平台收入 / 2 MERCHANT 商户结算 / 3 USER_BALANCE 用户余额 / 4 MARKETING 营销补贴`
- **FeeItems 费用项**：`1 COMMISSION 佣金 / 2 TECH_FEE 技术服务费 / 3 CHANNEL_FEE 支付通道费（退款不退）`
- **WithdrawStatuses 提现状态**：`10 APPLY 已申请 / 20 AUDITING 审核中 / 30 SUCCESS 成功 / 40 FAIL 失败（金额退回可提现） / 50 REFUSED 已拒绝`
- 提现规则（design 7.4）：最低 100 元、单日上限 50 万、每月前 3 次免费、其后 0.1%（单笔最低 2 元）；保证金低于 50% 限提，清退 90 天无纠纷退还（design 7.6）。

---

# 7. 售后域 aftersale（shop-aftersale-service :8087，无 Feign）

## 7.1 DTO

### AftersaleItemMessage（售后事件明细行，Serializable）
| 字段 | 类型 | 含义 |
|---|---|---|
| orderItemId | Long | 订单明细 ID |
| skuId | Long | SKU ID |
| qty | Integer | 本次售后数量 |
| refundFen | Long | 本行退款金额（分，含分摊优惠/积分/运费的实付口径） |

约束：同一明细累计退款不超过实付金额，同一明细同时只能有一笔进行中售后（design 8.3.2）。

## 7.2 事件

### AftersaleChangedEvent → `AFTERSALE_CHANGED`（shop_aftersale_changed）
每次状态流转（审核/退货物流/收货确认/退款/换货发货/仲裁）发出；消费者据 oldStatus→newStatus 做状态机校验，禁止逆向覆盖。

| 字段 | 类型 | 含义 |
|---|---|---|
| aftersaleNo | String | 售后单号（AS+yyyyMMdd+10 位序列） |
| orderNo | String | 业务订单号 |
| userId | Long | 买家用户 ID |
| merchantId | Long | 商户 ID |
| type | Integer | 售后类型 1-5（AftersaleTypes） |
| oldStatus | Integer | 变更前状态（首次申请可空） |
| newStatus | Integer | 变更后状态（AftersaleStatuses） |
| refundFen | Long | 本次退款总金额（分；换货/补发为 0） |
| responsibilitySide | Integer | 责任方 1 商家 2 买家 3 运费险（ResponsibilitySide，决定运费与库存去向） |
| logisticsNo | String | 退货/换货物流单号 |
| items | List\<AftersaleItemMessage\> | 售后明细行（默认空集合） |

## 7.3 码值

- **AftersaleTypes 售后类型**：`1 REFUND_ONLY 仅退款 / 2 RETURN_REFUND 退货退款 / 3 EXCHANGE 换货 / 4 RESHIP 补发货 / 5 PRICE_PROTECT 价保`
- **AftersaleStatuses 售后状态**：`10 WAIT_MERCHANT_AUDIT 待商家审核 / 20 WAIT_BUYER_RETURN 待买家退货 / 30 MERCHANT_RECEIVING 商家收货中 / 40 REFUNDING 退款中 / 41 WAIT_EXCHANGE_SHIP 待换货发货 / 42 EXCHANGE_SHIPPED 换货已发货 / 43 EXCHANGE_WAIT_RECEIVE 换货待收货 / 50 FINISHED 已完成 / 55 REJECTED 已拒绝(待用户处理) / 80 PLATFORM_INTERVENING 平台介入中 / 90 CANCELED 已撤销`
- **ResponsibilitySide 责任方**：`1 MERCHANT 商家（承担运费） / 2 BUYER 买家（承担运费，退货回可售） / 3 INSURANCE 运费险（保险赔付，最高 25 元，72h 理赔，每单一次）`
- **ArbitrationResults 仲裁结果**：`1 MERCHANT_WIN 商家胜诉 / 2 BUYER_WIN 买家胜诉 / 3 PARTIAL 部分支持`
- 超时（CONTRACTS §4 / design 8.5）：仅退款/退货退款/换货商家审核 **2 天**自动同意；商家确认收货 **3 天**自动确认并退款；换货发货 **5 天**超时转退款。价保：下单后 7 天（大促 30 天），不含秒杀/拼团，每单一次（design 8.8）。

---

# 8. 关键异步链路（实现要点）

1. **下单同步链路**：订单校验 → `ProductClient.lockStock`(TCC-try) → `MarketingClient.lockPromotion` → `UserClient.lockPoints`（有积分时）→ 落订单(待付款 10) → 发 `ORDER_CREATED` + 支付超时延时消息。任一步失败按**逆序补偿**（release 营销 → release 库存 → release 积分）。
2. **支付成功扇出**：pay 回调先验签、以 payNo 幂等落单，再发 `ORDER_PAID`；order/product/marketing/user/settlement 各自以 eventId/payNo 幂等消费。
3. **超时关单双保险**：RocketMQ 延时消息（普通 30 分钟/秒杀 15 分钟/拼团 24h+30 分钟/预售尾款 3 天）+ DB 扫描补偿任务（ShedLock 选主），取消发 `ORDER_CANCELLED`（cancelType=2）。
4. **确认收货**：用户确认或发货后 10 天超时自动确认 → 发 `ORDER_CONFIRMED` → settlement 生成待结算；售后期满发 `ORDER_COMPLETED`（B 级转可提现）。
5. **退款/清算冲正**：aftersale 经 `PayClient.refund` 发起退款；pay 退款成功发 `REFUND_SUCCESS`；settlement 内部冲正（**不发 CLEARING_REVERSE**）：佣金按比例退回、通道费不退、先扣待结算款不足扣保证金；user 按比例退积分、退款入余额；order 回写售后结果。
6. **售后回库**：aftersale 发 `AFTERSALE_CHANGED`，product 仅在**买家责任**（responsibilitySide=2）/换货事件调用 `returnStock`（reason=1/3 回可售；商家责任质量问题 reason=2 入残次）。

# 9. 审查结论与已知缺口

- CONTRACTS §3 全部 **21 个 Feign 方法签名**（方法名/参数名/参数类型/Result 包装/@RequestBody/@RequestParam）与实现**逐字一致**；5 个 `@FeignClient` 的 name/path 全部正确。
- §5 全部 9 类必选事件（含 items 内嵌 10 个字段）字段覆盖完整；全部事件继承 `BaseEvent`；金额字段全部 Long；校验注解全部 jakarta；DTO 四注解 + Serializable 齐全；无循环引用；**0 处跨域 import**。
- §4 全部码值与常量类/枚举逐一核对一致。
- **G1（唯一待补缺口）**：`com.shop.common.constant.MqTopics` 中缺少 `DepositAlertEvent`、`WithdrawResultEvent` 两个 settlement 事件的 Topic 常量（shop-api 不可改 shop-common）。建议在 shop-common 增补（如 `DEPOSIT_ALERT="shop_deposit_alert"`、`WITHDRAW_RESULT="shop_withdraw_result"`），Wave-2 settlement 代理在常量补齐前不应硬编码 topic 字符串。
- `SpuDTO.goodRate`、`UserLevelDTO.discount/pointsRate` 使用 BigDecimal，均为**比率/倍率非金额字段**，符合"金额一律 Long"禁令。
- `OrderClient.getByOrderNo` 与各 @RequestBody 命令参数在契约签名之外附加了 `@NotBlank`/`@Valid` 校验注解，不改变方法签名，且符合 CONTRACTS §2.4，保留。
