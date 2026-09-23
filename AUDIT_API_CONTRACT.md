# API 健壮性审计报告（输入校验 / 错误契约 / 越权防护）

- 审计日期：2026-09-17
- 审计范围：7 个业务服务 `shop-{user,product,order,aftersale,pay,marketing,settlement}-service/src/main` 下全部 `@RestController`，以及 `shop-framework`（鉴权/异常/统一返回）、`shop-common`（Result/ErrorCode/PageQuery）、`shop-api`（Feign 命令 DTO）、`shop-gateway`
- 审计方式：只读静态审计，未修改任何源码
- 越权章节与 `SECURITY_REVIEW.md`（2026-09-16）去重：该报告 C-1/C-2/C-3/H-1/H-2/H-4 等项在当前代码中**大部分已修复**（修复证据见 §5.4），本报告只报新增/未覆盖证据

---

## 一、接口总量与清单概览

**共 37 个 Controller、152 个 HTTP 接口**（GET 53 / POST 83 / PUT 12 / DELETE 4）。
外部路径 = 网关 `/api/<服务域>` 前缀（网关 `StripPrefix=2`）+ 下表"基础路径"；`/inner/**` 已在网关 404 且下游有 `X-Internal-Token` 拦截器（`WebMvcConfig.java:22-24`）。

鉴权标记：**登录**=非 `@Anonymous`，`AuthInterceptor` 强制 X-User-Id；**匿名**=方法/类级 `@Anonymous`；**平台**=服务内显式 `userType=2` 校验；**商户**=服务内显式 `merchantId` 归属校验；**内部**=`/inner/**` + 内部令牌。

### 1.1 shop-user-service（28 接口）

| Controller（基础路径） | 数 | 方法 → 鉴权 |
|---|---|---|
| account/AccountController（`/users`） | 3 | GET `/points`、GET `/points/flows`、GET `/balance/flows` → 登录 |
| account/AdminAccountController（`/users/admin/accounts`） | 2 | POST `/merchant`、POST `/admin` → 登录+平台（`AdminAccountController.java:32,39`） |
| account/InnerUserController（`/inner/user`） | 12 | GET `/get`、`/level`、`/address`；POST `/points/{lock,deduct,release,refund,grant}`、`/growth/add`、`/balance/{debit,credit}`、`/gift/debit` → 内部（类级 `@Anonymous`，令牌拦截） |
| address/AddressController（`/users/addresses`） | 5 | POST、PUT、DELETE、GET 分页、GET `/{id}` → 登录+userId 归属 |
| auth/AuthController（`/auth`） | 3 | POST `/register`、`/login`、`/bootstrap-admin` → 匿名（IP 限流 / bootstrap 令牌） |
| profile/UserProfileController（`/users`） | 2 | GET `/me`、GET `/level` → 登录 |
| signin/SignInController（`/users`） | 1 | POST `/sign-in` → 登录 |

### 1.2 shop-product-service（36 接口）

| Controller（基础路径） | 数 | 方法 → 鉴权 |
|---|---|---|
| category/BrandController（`/brands`） | 4 | GET 分页 → 匿名；POST、PUT `/{id}`、POST `/{id}/delete` → 登录+平台（Service 内 `requirePlatform()`） |
| category/CategoryController（`/categories`） | 4 | GET `/tree` → 匿名；POST、PUT、POST `/{id}/delete` → 登录+平台 |
| comment/CommentController（`/comments`） | 4 | POST 发表、POST `/{id}/append` → 登录+本人；POST `/merchant/{id}/reply` → 登录+商户归属（`CommentServiceImpl.java:138-146`）；GET `/products/{spuId}` → 匿名 |
| goods/AdminGoodsController（`/admin/products`） | 4 | GET 分页、GET `/{spuId}`、POST `/{spuId}/audit`、POST `/{spuId}/violation` → 登录+平台（Service 内校验，`SpuServiceImpl.java:326,341-345`） |
| goods/GoodsController（`/products`） | 4 | GET 分页、`/{spuId}`、`/{spuId}/skus`、`/skus/{skuId}/price` → 匿名（类级 `@Anonymous`） |
| goods/MerchantGoodsController（`/merchant/products`） | 9 | GET 分页/详情、POST、PUT、`/{spuId}/{submit,onsale,offsale,delete}`、POST `/skus/{skuId}/replenish` → 登录+商户归属（`AuthUtils.checkOwner`） |
| stock/ProductInnerController（`/inner/product`） | 7 | GET `/sku`、POST `/sku/list`、POST `/stock/{lock,confirm,release,return}`、GET `/stock/saleable` → 内部 |

### 1.3 shop-order-service（23 接口）

| Controller（基础路径） | 数 | 方法 → 鉴权 |
|---|---|---|
| cart/CartController（`/cart`） | 9 | POST 加购、PUT `/{cartId}`、DELETE `/{cartId}`、POST `/{cartId}/favorite`、PUT `/select`、`/select-all`、`/invert`、DELETE `/invalid`、GET 视图 → 登录+userId 归属 |
| order/InnerOrderController（`/inner/order`） | 1 | GET 按 orderNo 查单 → 内部 |
| order/MerchantOrderController（`/merchant/orders`） | 2 | GET 分页、POST `/{orderNo}/ship` → 登录+商户（`currentMerchantId()` 硬校验，:48-54） |
| order/OrderController（`/orders`） | 11 | POST 下单、GET 分页/详情、`/{orderNo}/{cancel,confirm,remind,rebuy}`、PUT `/{orderNo}/address`、DELETE `/{orderNo}`、GET+PUT `/{orderNo}/invoice` → 登录+userId 归属（详情买家或本店商户二选一） |

### 1.4 shop-aftersale-service（16 接口）

| Controller（基础路径） | 数 | 方法 → 鉴权 |
|---|---|---|
| AftersaleController（`/aftersales`） | 10 | POST 申请、`/{no}/{cancel,resubmit,return-logistics,exchange-confirm,intervene,evidence}`、POST `/price-protect/trial`、GET `/{no}`、GET `/page` → 登录+userId 归属（detail 已补买家/商户/平台三选一，`AftersaleServiceImpl.java:634-652`） |
| MerchantAftersaleController（`/merchant/aftersales`） | 5 | POST `/{no}/{audit,receive,ship,evidence}`、GET `/page` → 登录；audit/receive/ship 服务内 `requireMerchant`（**evidence/page 例外，见 §5.2**） |
| PlatformAftersaleController（`/platform/aftersales`） | 1 | POST `/{no}/arbitration` → 登录+平台（控制器与服务双重校验） |

### 1.5 shop-pay-service（13 接口）

| Controller（基础路径） | 数 | 方法 → 鉴权 |
|---|---|---|
| payment/ChannelNotifyController（`/notify`） | 1 | POST `/pay/{channel}` → 匿名渠道回调（HMAC-SHA256 验签） |
| payment/PayController（`/pays`） | 3 | POST 创建、GET `/{payNo}`、GET `/order/{orderNo}` → 登录；userId 强制取登录身份，金额按订单反查，查询带归属（C-3 已修复） |
| payment/PayInnerController（`/inner/pay`） | 3 | POST `/payment`、GET `/payment`、POST `/refund` → 内部 |
| recon/ReconcileController（`/platform/recon`） | 4 | POST `/run`、GET `/diffs`、POST `/diffs/{id}/handle`、POST `/retry` → 登录+平台（每方法显式校验） |
| refund/RefundController（`/refunds`） | 2 | GET `/{refundNo}` → 登录+平台/归属商户/买家三选一；POST `/{refundNo}/retry` → 平台 |

### 1.6 shop-marketing-service（22 接口）

| Controller（基础路径） | 数 | 方法 → 鉴权 |
|---|---|---|
| activity/ActivityAdminController（`/admin/activities`） | 3 | POST、POST `/{id}/status` → 登录+平台；GET 分页 → **仅登录，无平台校验（见 §5.2-A2）** |
| controller/MobileMarketingController（`/h5/marketing`） | 1 | POST `/calculate` → 登录（只读试算） |
| coupon/CouponAdminController（`/admin/coupons`） | 4 | POST、POST `/{id}/status` → 平台；GET `/{id}/targets`、GET 分页 → **仅登录，无平台校验** |
| coupon/CouponCenterController（`/coupons`） | 3 | GET `/center` → 匿名；POST `/claim`、GET `/my` → 登录 |
| inner/InnerMarketingController（`/inner/marketing`） | 5 | POST `/calculate`、`/lock`、`/confirm`、`/release`、`/coupon/issue` → 内部（原 `/coupons/issue` 已收口，H-1 已修复） |
| promo/PromoAdminController（`/admin/promos`） | 6 | POST、POST `/{id}/status` → 平台；GET 分页、`/{id}`、`/{id}/levels`、`/{id}/targets` → **仅登录，无平台校验** |

### 1.7 shop-settlement-service（14 接口）

| Controller（基础路径） | 数 | 方法 → 鉴权 |
|---|---|---|
| account/MerchantAccountController（`/merchant/account`） | 1 | GET → 登录+商户 |
| clearing/MerchantClearingController（`/merchant/clearing`） | 1 | GET 分页 → 商户 |
| deposit/MerchantDepositController（`/merchant/deposit`） | 2 | GET 分页、POST 缴纳 → 商户 |
| merchant/AdminController（无类级前缀，全方法 `/admin/...`） | 5 | POST `/admin/merchants`、PUT `/admin/merchants/{id}/level`、POST `/admin/merchants/{id}/resign`、GET `/admin/deposit/records`、POST `/admin/reconcile/settle` → 登录+平台（全方法显式校验） |
| statement/MerchantStatementController（`/merchant/statements`） | 1 | GET 分页 → 商户 |
| withdraw/MerchantWithdrawController（全方法路径） | 4 | POST `/merchant/withdrawals`、GET 列表、GET+PUT `/merchant/withdraw/auto-config` → 商户 |

---

## 二、Bean Validation 覆盖

### 2.1 总体数据

| 指标 | 数值 |
|---|---|
| 带 `@RequestBody` 的接口 | 68 |
| 参数前有 `@Valid` 的 | 65（95.6%） |
| **body 完全无 `@Valid` 的写接口** | **3**（见 §2.2） |
| 写接口总数（POST+PUT+DELETE） | 99 |
| 全仓 `@Validated`（类/方法级，用于 `@RequestParam` 约束） | **0 处** —— 即没有任何 `@Min/@Pattern` 形式的参数级约束，参数白名单全部依赖服务层手写 |
| DTO（含 shop-api Command）共 58 个，零约束注解的 | 4 个：`PayCreateRequest`、`PayNotifyRequest`（pay-service）、`PointsRefundCommand`（shop-api）、`SplitRequest`（结算引擎内部对象，不经 HTTP）；另有 `ClaimRequest`（Controller 内部类）零注解 |

### 2.2 完全无校验的写接口（body 裸接）

| # | 接口 | 证据 | 实际兜底情况 |
|---|---|---|---|
| 1 | POST `/api/marketing/coupons/claim` | `CouponCenterController.java:41`（`@RequestBody ClaimRequest req`，无 `@Valid`）；内联 DTO `CouponCenterController.java:54-59`，`couponId/requestNo` 无任何注解 | `couponId=null` 一路拼进 Redis key 与查询，错误形态不可预期；`requestNo` 无长度上限，超长值写 DB UK 列时抛 `DataIntegrityViolation` → 10009 |
| 2 | POST `/api/pay/notify/pay/{channel}` | `ChannelNotifyController.java:35`（`@RequestBody PayNotifyRequest`，无 `@Valid`），`PayNotifyRequest.java` 零注解 | 金额/status/单号均可空；靠后续验签与金额比对拒绝（顺序正确），但空字段会先以 NPE/500 形态出现，渠道拿不到规范 ACK |
| 3 | POST `/inner/product/sku/list` | `ProductInnerController.java:44`（`@RequestBody List<Long> skuIds`，无 `@Valid`、无 `@Size`） | 服务层仅判空（`StockServiceImpl.java:251-253`），一次可传超大 ID 列表打 `selectBatchIds` 的 IN 子句；内部令牌后可达，仍建议加上限 |

### 2.3 嵌套校验失效（`@Valid` 级联缺失，约束注解形同虚设）

| # | 证据 | 后果 |
|---|---|---|
| N-1 | `AftersaleApplyRequest.java:31`：`@NotEmpty private List<Item> items;` **无 `@Valid`**；而 `Item.qty` 上的 `@NotNull/@Min(1)/@Max(999)`（:42-45）因此**永不触发**。入口 `AftersaleController.java:38`（apply）、`:52`（resubmit） | `qty=null` 在 `AftersaleServiceImpl.java:160` 拆箱 NPE → 10009；`qty` 负数/0 穿透：非换货类在 :183 被 `lineRefund<=0` 挡住，但换货/补发类（:183 明确放行 lineRefund=0）可把负数量写入售后明细并驱动后续换货出库/库存内部调用（内部命令 `StockItemCommand` 有 `@Min(1)` 兜底，表现为 500 而非资金损失） |
| N-2 | `ActivitySaveRequest.java:31`：`private List<SeckillSkuRequest> seckillSkus` 无 `@Valid`；内嵌 `seckillPriceFen/totalStock` 的 `@NotNull`（:36-39）不触发，且**全 DTO 无任何正数注解**。入口 `ActivityAdminController.java:28` | 见 §3.2-M3：负秒杀价、负库存可入库 |
| N-3 | 对照（已做对）：`CreateOrderRequest.java:32`、`SpuSaveRequest.java:51`、`StockLockCommand`/`StockDeductCommand`/`PriceCalcCommand` 的 items 均有 `@Valid` 级联 | — |

### 2.4 DTO 注解质量抽查（已做对的样板）

- 用户域：`RegisterRequest`、`BootstrapAdminRequest`、`AdminCreateAccountRequest`（用户名 `@Size 3-32`、手机号 `@Pattern ^1\d{10}$`、密码 6-64）；`AddressSaveRequest`（手机号 Pattern、详址 `@Size 256`）。
- 结算域：`ApplyWithdrawRequest`（金额 `@Min(10000)`、账号/姓名 `@Size`）、`OnboardMerchantRequest`（佣金率 0-10000、保证金 100000-5000000）、`UpdateLevelRequest`（0-3）、`AutoWithdrawConfigRequest`（weekday 1-7）。
- 商品域：`SkuSaveRequest` 6 个金额字段全部 `@Min(0)`、重量/体积非负；`CommentCreateRequest` 三维度星级 1-5、内容 10-500 字、图片 9 张、视频 ≤30 秒。
- 订单域：`CreateOrderRequest.Item.qty` `@Min(1)`、`InvoiceRequest.email` `@Email`；购物车 `CartUpdateRequest.qty` 1-99。
- shop-api：`CreatePaymentCommand/CreateRefundCommand.amountFen` `@Min(1)`；`StockItemCommand.qty` `@Min(1)`；`GrantPointsCommand.points/GrowthCommand.growth` `@Positive`。

---

## 三、枚举 / 越界值 / 金额 / 分页

### 3.1 枚举白名单（状态/类型/渠道）

| 字段 | 结论 | 证据 |
|---|---|---|
| 支付方式 payMethod / 终端 terminal | ✅ 服务层白名单 | `PaymentServiceImpl.normalizeParts()`（:211-230，`PayMethods.of` 抛错+`ChannelLimits.check`） |
| 支付/提现渠道 channel | ✅ 白名单 | 回调 `ChannelRouter.route`（:21-29）；对账 `ReconcileServiceImpl.java:65 requireKnownChannel`；提现/自动提现 `WithdrawService.java:84,276 WithdrawChannels.valid` |
| 账户类型 accountType | ✅ 白名单 | `AccountServiceImpl.java:482-484`（1-3 越界抛 10001） |
| 售后类型 type | ✅ 策略表白名单 | `AftersalePolicy.checkApplicable`（:86-103，任何非 1-5 均返回不支持） |
| 仲裁结果 result | ✅ switch default 拒绝 | `AftersaleServiceImpl.java:570-580` |
| 勾选标记 selected（购物车） | ✅ 0/1 归一化 | `CartServiceImpl.java:322-326` |
| 发票类型 invoiceType/抬头 | ✅ 服务层校验 | `InvoiceServiceImpl.validate`（:132-152） |
| 自动提现 enabled/frequency/weekday | ✅ 服务层校验 | `WithdrawService.java:269-282` |
| 商户等级 level | ✅ Bean Validation | `UpdateLevelRequest.java` |
| **营销活动 type** | ❌ 仅 `@NotNull`，无白名单 | `ActivitySaveRequest.java:22`；`ActivityAdminService.save` 原样落库（:46） |
| **券 type/scopeType/validType/issueWay** | ❌ 均仅 `@NotNull`，原样落库 | `CouponSaveRequest.java:20-35`；`CouponAdminService.java:35-44` |
| **促销 type/scopeType** | ❌ 同上 | `PromoSaveRequest.java:21-23`；`PromoAdminService.java:36-38` |
| **三个 changeStatus 的 status 入参** | ❌ `@RequestParam int status` 无 `@Min/@Max`，且无状态机迁移表，条件更新把任意整数写成新状态（如 99） | `ActivityAdminService.java:64-77`、`CouponAdminService.java:63-74`、`PromoAdminService.java:71-82` |
| 订单类型 orderType / 来源 source | ❌ `@NotNull` 但无 1-4 白名单；非法值在 `OrderCreateServiceImpl.stockTypeOf/resolveActivityId`（:527-543）**静默按普通单处理**，但客户端原值仍写入 `t_order.order_type`（:303）与订单号段 | `CreateOrderRequest.java:25,28` |
| 售后举证 evidenceType | ❌ `@NotNull` 无 1-3 校验，原样落库 | `EvidenceRequest.java:15`；`AftersaleServiceImpl.java:522` |
| 售后责任方 responsibilitySide | ❌ 无 1-3 校验且**由买家自报**（见 §5.2-B1） | `AftersaleServiceImpl.java:189,211` |
| 发票内容范围 contentScope | ❌ 无 1/2 校验，原样落库 | `InvoiceServiceImpl.java:126` |
| SKU stockType/presaleFlag、品牌/类目 status、对账差错 status、券"我的"status | ⚠️ 原样透传为 WHERE/落库值；WHERE 类无安全影响（空结果），落库类产生脏枚举值 | `SkuSaveRequest.java:64-67`、`BrandSaveRequest.java:24`、`ReconcileController.java:42` |

### 3.2 金额（Long 分）负数/0 防护

- ✅ **已防护**：支付/退款命令（`@Min(1)`+服务层双重）、保证金（`@Min(1)`+`DepositService.java:80`）、提现（`@Min(10000)` + 日累计/保证金业务校验）、入驻金额区间、SKU 全部价格字段 `@Min(0)`、补货/购物车/订单行/库存命令数量、积分发放/成长值 `@Positive`、营销引擎对运费/积分/折扣的 `Math.max(0,...)` 钳制（`PriceEngine.java:81,109,110,363,583`）、C 端支付金额强制取订单应付额（`PaymentServiceImpl.java:114-121`）、退款累计不超实付（条件更新）。
- ❌ **M-1 秒杀价/库存可负**：`ActivitySaveRequest.SeckillSkuRequest`（:33-39）`seckillPriceFen` 无 `@Positive`、`totalStock` 无 `@Min`，嵌套 `@Valid` 又缺失；`ActivityAdminService.java:49-58` 直接写库并以负库存初始化 Redis（`SeckillStockClient` Lua `DECRBY` 负值方向反转）。平台账号才能调用，但属于资金类字段缺少最后一道防线。
- ❌ **M-2 券金额/折扣/数量无符号防护**：`CouponAdminService.save` 对 `faceValueFen/thresholdFen/discountBp/maxDiscountFen/totalCount/perUserLimit`（:37-43）只做 null 默认，无正负/区间校验：负面额、`discountBp>1000`（折扣放大）、负库存均可落库，领券/试算链路把这些值当可信数据使用。
- ❌ **M-3 促销档位金额**：`PromoAdminService.java:48-57`，`reduceFen/discountBp/giftQty` 无符号校验。
- ⚠️ 订单 `freightFen/usePointsFen`（`CreateOrderRequest.java:40,43`）无 `@PositiveOrZero`，负值被营销引擎钳为 0，**当前不可套出钱**，但订单实体落库用的是试算结果（`OrderCreateServiceImpl.java:305-312`），建议仍加注解做前置 400。
- ⚠️ 内部命令 `AmountCommand.amountFen` 注释写"恒为正数"但只有 `@NotNull`（`AmountCommand.java:33`）；`PointsLockCommand.points/deductFen` 同；`PointsRefundCommand` **零注解**。服务层 `validateAmount`（`AccountServiceImpl.java:124-135`）有金额校验兜底，且仅 `/inner` 令牌可达，风险低，建议补齐注解契约。

### 3.3 分页参数上限

- ✅ 统一基类 `PageQuery`（`shop-common/.../result/PageQuery.java`）：`safePageSize()` 上限 **200**、`safePageNum()` 下限 1；订单/售后/地址/账户流水/评价/SPU 浏览与管理分页**全部走 safe 方法**（已逐处核对）。
- ✅ 结算域 6 个分页接口在 Controller 显式 `Math.min(pageSize,100)`。
- ✅ 品牌分页 `BrandServiceImpl.java:30-31` 手动钳 200。
- ❌ **营销三个管理分页裸接 `long pageSize` 无上限、`pageNum` 无下限**，直接 `new Page<>(pageNum,pageSize)` 透传给 MyBatis-Plus：
  `ActivityAdminController.java:41-46` → `ActivityAdminService.java:83-90`；
  `CouponAdminController.java:49-54` → `CouponAdminService.java:91-98`；
  `PromoAdminController.java:60-65` → `PromoAdminService.java:108-115`。
  传 `pageSize=10000000` 即整表扫描+大结果集回传（且这三个 GET 接口还缺平台鉴权，见 §5.2-A2，普通用户即可打）。
- ⚠️ 结算域分页只钳了 pageSize，`pageNum=0/负数` 原样入 `new Page<>`（异常时由通用兜底转 10009，非数据泄露，建议统一走基类）。

---

## 四、统一返回 / 错误码契约

### 4.1 一致性（总体良好）

- 全部 152 个接口统一返回 `Result<T>`（code/message/data/timestamp）；`ErrorCode` 按域分段（1xxxxx 通用、3 商品、4 营销、5 订单、6 支付、7 结算、8 售后），全仓抛错统一走 `BizException(ErrorCode[, msg])`，Feign 回包由 `FeignResults.resolve` 映回枚举，未发现裸数字码。
- `GlobalExceptionHandler`（`shop-framework/.../web/GlobalExceptionHandler.java`）捕获面：
  - ✅ `BizException`（401/403 正确设置 HTTP 状态，:31-35）
  - ✅ `MethodArgumentNotValidException`/`BindException`（聚合字段 defaultMessage，:40-46）
  - ✅ `ConstraintViolationException`、`MissingServletRequestParameterException`、`HttpRequestMethodNotSupportedException`
  - ✅ 兜底 `Exception`：服务端 `log.error` 记录完整堆栈，**客户端只收固定文案"系统繁忙，请稍后再试"，不泄露堆栈/SQL/内部类名**
- 抽查 SQL 异常路径（超长字符串写库、重复键）：均落入兜底分支，无异常 message 回显。

### 4.2 错误契约缺口

| # | 问题 | 证据 / 后果 |
|---|---|---|
| E-1 | **未捕获 `HttpMessageNotReadableException`**（JSON 格式错误、枚举传字符串、body 类型不符）与 **`MethodArgumentTypeMismatchException`**（路径/查询参数类型不符，如 `/orders/abc` 中 Long 传字母） | `GlobalExceptionHandler.java:63` 兜底 Exception 一律返回 **10009"系统繁忙"** 并打 error 日志：客户端 4xx 类错误被误判为 5xx 系统故障，且污染告警。建议增补两个 handler 返回 10001/400 |
| E-2 | `ChannelLimits.check` 对不支持终端抛的是 **`IllegalArgumentException`** 而非 BizException | `ChannelLimits.java:75-77`；C 端 `/pays` 传非法 terminal → 10009 而非 10001。同类还有 `AdminController.java:80 LocalDate.parse(date)`（日期格式错→10009，应 400）、`PayMethods.of` 等枚举转换 |
| E-3 | 除 401/403 外，**所有错误 HTTP 状态码都是 200**（含参数错误、404 业务语义、409 冲突） | 全 handler 未 `response.setStatus`；网关/监控/前端只能靠 body.code 判断，与 REST 语义和 RETRY 中间件交互易出错。属契约风格问题，需全链路统一 |
| E-4 | `ConstraintViolationException` 直接回 `e.getMessage()` | `GlobalExceptionHandler.java:50`；消息形如 `lockStock.arg0.points 积分数量不能为空`，泄露 Java 方法名/参数索引，建议与字段错误一样只取 message 模板 |
| E-5 | 个别错误消息回显内部单号 | `PaymentServiceImpl` "支付单不存在: "+payNo 等（SECURITY_REVIEW L-2 已记录，维持低风险） |
| E-6 | 校验注解全部依赖 `@Valid`，但全仓无 `@Validated`；若将来有人给 `@RequestParam` 加约束注解会静默失效 | grep `@Validated` 0 命中（框架现状说明，非现网缺陷） |

---

## 五、越权防护（只报 SECURITY_REVIEW.md 之外的新增/未覆盖证据）

### 5.1 SECURITY_REVIEW 项的修复确认（本轮复核已通过，不再计为新风险）

- C-1 `/inner/**`：网关归一化后命中 `inner` 段直接 404（`JwtAuthGlobalFilter.java:71-73`）+ 下游 `InternalTokenInterceptor` 强制 `X-Internal-Token`（`WebMvcConfig.java:22-24`），Feign 侧注入、网关侧剥离该头（`JwtAuthGlobalFilter.java:88`）。
- C-2：注册拒绝 `userType=1`、忽略 merchantId（`AuthServiceImpl.java:58,78`；`RegisterRequest.java:36-42`），商户/运营账号只走 `AdminAccountController`（平台开通）。
- C-3：支付创建强制登录身份+按订单反查金额/归属/状态（`PaymentServiceImpl.java:97-125`）；退款创建收口到 `/inner/pay/refund`；退款查询三方归属校验（`RefundServiceImpl.java:110-135`）；支付查询带 viewer。
- H-1：营销 3 个 admin Controller 的**写**操作、对账、仲裁、发券均已加 `requirePlatformAdmin()`；H-2 售后详情已补买家/商户/平台三选一（`AftersaleServiceImpl.java:634-652`）。
- H-4：网关已先双重解码+归一化再匹配，白名单收敛为文件级路径。
- 遗留跟踪：JWT 密钥在 `JwtAuthGlobalFilter.java:67`、`JwtService` 仍保留代码内默认值（H-3，需确认 prod fail-fast 由 `ShopSecretEnvironmentValidator` 兜底），不在本报告重复展开。

### 5.2 新增/未覆盖证据

#### A 类：垂直越权（管理端）

- **A1（重要）营销 `/admin/**` 的 GET 接口普遍缺平台校验**。SECURITY_REVIEW H-1 只点了"新建/上下架"，当前写操作已补，但以下 7 个 GET 端点任何登录消费者（userType=0）都可调用：
  - `ActivityAdminController.java:40-47` GET `/admin/activities`（分页，含未开始/已下架全量活动、ruleJson、shopId）
  - `CouponAdminController.java:43-46` GET `/admin/coupons/{id}/targets`、`:48-55` GET 分页（券面额/总量/限领/投放目标全量）
  - `PromoAdminController.java:44-57` GET `/{id}`、`/{id}/levels`、`/{id}/targets`、`:59-66` GET 分页（满减档位、作用 SKU/类目）
  - 网关只校验 JWT 不校验 userType；这些 Controller/Service 方法内无任何 `WebIdentity` 调用。影响：竞品/黑产可拉取全站未公开营销配置与成本结构（折扣率、满减门槛、活动库存结构）。叠加 §3.3 的无上限 pageSize，可一次拖全表。
- **A2（建议）商户售后列表不硬卡商户身份**：`MerchantAftersaleController.java:67-69` 直接把 `getMerchantIdOrNull()`（可能为 null）传入，Service 用无条件 `.eq(merchantId, null)`（`AftersaleServiceImpl.java:674-683`），消费者调用返回**空页 200 而非 403**。无数据泄露，但与 `MerchantOrderController.currentMerchantId()` 的 403 口径不一致。

#### B 类：水平越权 / 信任边界（C 端）

- **B1（重要）售后"责任方"由买家自报并直接驱动运费赔付**：`AftersaleApplyRequest.responsibilitySide`（:25）无 1/2/3 白名单，`AftersaleServiceImpl.java:189-190` 用客户端上送值计算 `freightCompensation`，`:211` 原样落库，`:220` 再次计入 `freightRefundFen`；`RefundCalculator.freightCompensation`（:69-74）对 side=1（商家责任）**全额返还买家填报的运费，无上限**（保险理赔上限 `insuranceClaimFen` 是另一条路径，未被此路径使用）。责任方本应由商家审核/平台仲裁认定，商家审核是唯一闸门（可拒绝），但买家可对每单默认提交"商家责任+任意金额运费"，商家一旦误点同意即按买家自报金额退款。建议：申请时责任方一律默认买家且不接收客户端字段，商家审核通过时才允许改判。
- **B2（重要）售后举证接口无任何归属校验**：`AftersaleServiceImpl.submitEvidence`（:506-527）只校验介入单存在、状态在举证期、未过 3 天截止，**不校验操作人与售后单关系**：
  - 买家端 `POST /aftersales/{aftersaleNo}/evidence`（`AftersaleController.java:80-86`，side 硬编码买家）：任意登录用户对任意处于举证期的他人售后单可写入举证内容；
  - 商户端 `POST /merchant/aftersales/{aftersaleNo}/evidence`（`MerchantAftersaleController.java:58-64`，side 硬编码商家，且传的是 `getUserId()` 不是 merchantId）：普通消费者也能以"商家方"身份向任意介入单注入举证。
  - 对照同文件其余 6 个操作均有 `requireOwner/requireMerchant`，举证是唯一漏掉的写操作。后果：污染平台仲裁证据链（可冒充对方提交伪造图片/文字，影响裁决走向），`evidenceType/content/mediaUrls` 还均无长度/枚举校验。建议：买家端补 `requireOwner`，商户端补 `requireMerchant` 并按商户身份取 side。
- **B3（建议）商户审核/收货的 rejectReason、仲裁 remark 等字符串无长度上限**：`AuditRequest.java:15`、`ArbitrateRequest.java:15`、`LogisticsRequest.java`（公司/单号均仅 @NotBlank）、`ShipRequest.java`，超长值写库异常 → 10009；属输入契约缺口而非越权，一并在此跟踪。
- 已核对通过、维持 SECURITY_REVIEW 结论的 C 端归属面：订单（11 个操作全部 userId 归属，详情买家/商户二选一）、地址（requireOwned）、购物车/收藏（全查询带 userId）、发票、账户流水（强制 userId 入参）、评价追评/商家回复（本人/本店）、支付/退款查询（viewer 三选一）。**收藏夹无独立查询/删除接口**（仅有购物车"移入收藏"一个动作，`CartController.java:54-58`，带 userId 归属），无攻击面。

#### C 类：商户端商户归属

- 已通过：商品 8 个写操作 `AuthUtils.checkOwner`；订单发货强制 merchantId 且逐单比对；售后审核/收货/换货发货 `requireMerchant`（:905-909）；结算 5 个 Controller 全部 `WebIdentity.requireMerchantId()` 且 SQL 按 merchantId 过滤，提现单号服务端覆盖、收款账号加密+脱敏返回。
- 未发现新的商户水平越权证据。

---

## 六、文件上传 / 富文本 / 批量接口

### 6.1 文件上传

- **全仓不存在任何文件上传端点**：grep `MultipartFile`/`multipart` 在 java 与 yml 中 **0 命中**，无 `spring.servlet.multipart.*` 大小/类型配置。评价图片/视频、品牌 LOGO、类目图标、SPU 主图/轮播、售后举证媒体**全部以 URL 字符串上送**。
- 因此"文件大小/类型校验"无直接落点；但 URL 本身缺校验（当前仅评价在服务端重复校验了张数与视频时长，`CommentServiceImpl.java:199-206`）：
  - ⚠️ 每个 URL 元素**无长度上限**（`List<String> images` 只有 list 级 `@Size(max=9)`，无元素级 `@Size`/URL 格式）：`CommentCreateRequest.java:57`、`CommentAppendRequest.java:16`、`SpuSaveRequest.java:43`；`videoUrl` 有 512 长度但无 scheme 校验（`CommentCreateRequest.java:60-61`）；售后 `EvidenceRequest.mediaUrls`（逗号分隔）完全无限制（:17）。
  - ⚠️ 无 http(s) 协议/存储桶域名白名单：评价列表与 SPU 详情对匿名用户开放（网关白名单 `/api/product/comments/**`、`/api/product/products/**`），`javascript:`/私网 URL（`http://169.254.169.254/...`）等会随 JSON 下发，前端若直接渲染 `<a href>`/富文本即形成存储型 XSS / SSRF 探测面。建议统一改为"先上传对象存储拿可信域名 URL 再引用"的两步模式，并对 URL 做 `https + 媒体桶域名` 白名单。

### 6.2 富文本

- `SpuSaveRequest.detailJson/attrsJson`（:46,48，商品详情富文本/属性 JSON）**无 `@Size` 上限**，商户可提交 MB 级 JSON；匿名商品详情接口原样回传（`SpuServiceImpl.buildDetail`），既是大字段打库/带宽问题，也依赖前端对富文本做 XSS 过滤。
- 未配置全局请求体大小（各服务 application.yml 无 `server.tomcat.max-swallow-size/max-http-form-post-size`），实际吃 Tomcat 默认值，建议显式声明（如 JSON 2MB）。

### 6.3 批量接口上限

| 接口/字段 | 上限 | 证据 |
|---|---|---|
| 购物车条目数 / 单 SKU 件数 | ✅ 服务端硬限（MAX_CART_ITEMS / 99） | `CartServiceImpl.java:63-75,99-100` |
| 评价图片 / 追评图片 | ✅ 9 张 | DTO `@Size(max=9)` + 服务端双校验 |
| 下单商品行 `CreateOrderRequest.items` | ❌ 无 `@Size`（qty 有 @Min） | `CreateOrderRequest.java:31-33`；一次可提交数百上千行，连带放大库存/营销/积分三个 Feign 批量调用 |
| 下单 `fromCartIds` | ❌ 无上限 | `CreateOrderRequest.java:64` |
| 购物车批量勾选 `CartSelectRequest.ids` | ❌ 无 `@Size` | `CartSelectRequest.java:21`（IN 条件） |
| 商品 SPU 保存 `skus` 列表 | ❌ 无 `@Size`（内嵌字段校验完整） | `SpuSaveRequest.java:49-52` |
| 库存锁/扣/放/退 items | ❌ 无 `@Size`（内部命令，元素 @Valid 完整） | `StockLockCommand.java:28`、`StockDeductCommand.java:28` |
| 售后明细 items | ❌ 无 `@Size` 且 @Valid 缺失（N-1） | `AftersaleApplyRequest.java:31` |
| 秒杀 SKU / 券 targets / 促销 levels+targets | ❌ 无 `@Size` | `ActivitySaveRequest.java:31`、`CouponSaveRequest.java:37`、`PromoSaveRequest.java:30-31` |
| 内部批量查 SKU | ❌ 无 `@Size` | `ProductInnerController.java:44` |
| 对账重试批量 | ❌ `limit` 默认 100 但无上浮 | `ReconcileController.java:56`（仅平台可调） |

---

## 七、统计与风险接口清单

### 7.1 数字汇总

- **总接口数 X = 152**（37 个 Controller；GET 53 / POST 83 / PUT 12 / DELETE 4）
- **有输入校验接口数 Y = 140（92.1%）**。口径：至少具备以下之一即计入——(a) body 参数有 `@Valid` 且 DTO 约束有效；(b) 服务层对全部客户端入参有显式白名单/区间/归属校验；(c) 无 body 写操作具备归属校验且路径参数类型安全。未计入的 12 个：3 个无 `@Valid` body（§2.2）+ 3 个 changeStatus 无状态白名单 + 3 个管理分页无 pageSize 上限 + `/platform/recon/retry`（limit 无上浮）+ `/admin/products/{id}/violation`（remark 无长度且平台写）+ `/admin/reconcile/settle`（date 无格式约束，解析异常误报 500）。
- 写接口 body Bean Validation 覆盖：**65/68（95.6%）**；嵌套级联 `@Valid` 缺口 2 处（N-1、N-2）。
- 分页安全上限覆盖：PageQuery 子类全部覆盖；结算/品牌已钳；**营销 3 个管理分页未覆盖**。
- 金额负数防护：支付/退款/结算/商品链路完整；**营销活动/券/促销 3 个后台保存缺口**。
- 越权：SECURITY_REVIEW 所列 C/H 级阻断项在当前代码已基本修复；本轮新增越权证据 **3 项**（A1 营销 admin GET、B1 买家自报责任方、B2 举证无归属）。
- 堆栈/SQL 泄露：**未发现**（兜底 handler 不回传内部信息）。

### 7.2 风险接口清单（文件:行）

| 级别 | 接口 | 位置 | 问题摘要 |
|---|---|---|---|
| 重要 | POST `/aftersales`、POST `/aftersales/{no}/resubmit` | `shop-aftersale-service/src/main/java/com/shop/aftersale/aftersale/dto/AftersaleApplyRequest.java:31`；`AftersaleController.java:38,52` | items 缺 `@Valid`，qty null/负值穿透；换货/补发分支负数量落库 |
| 重要 | POST `/aftersales/{no}/evidence`、POST `/merchant/aftersales/{no}/evidence` | `shop-aftersale-service/src/main/java/com/shop/aftersale/aftersale/service/impl/AftersaleServiceImpl.java:506-527`；`AftersaleController.java:80-86`；`MerchantAftersaleController.java:58-64` | **举证无归属校验（IDOR）**：任意用户可向他人介入单写买家/商家方证据；evidenceType/mediaUrls/content 无约束 |
| 重要 | POST `/aftersales` | `AftersaleServiceImpl.java:189-190,211,220`；`RefundCalculator.java:69-74` | 买家自报 responsibilitySide=1 即按自报金额全额退运费，无上限 |
| 重要 | GET `/admin/activities`、GET `/admin/coupons`、GET `/admin/coupons/{id}/targets`、GET `/admin/promos`、`/{id}`、`/{id}/levels`、`/{id}/targets` | `ActivityAdminController.java:40-47`；`CouponAdminController.java:43-55`；`PromoAdminController.java:44-66` | 7 个管理端 GET 缺平台校验，普通用户可拉全量未公开营销配置 |
| 重要 | POST `/admin/activities` | `ActivitySaveRequest.java:31-39`；`ActivityAdminService.java:29-58` | 嵌套校验失效；负秒杀价/负库存可入库并初始化 Redis |
| 重要 | POST `/admin/coupons` | `CouponSaveRequest.java:20-44`；`CouponAdminService.java:29-58` | 面额/折扣率/总量/限领/枚举无符号与白名单校验 |
| 重要 | POST `/admin/promos` | `PromoSaveRequest.java:30-39`；`PromoAdminService.java:31-60` | levels 无 `@Valid`，reduceFen/discountBp/giftQty 可负 |
| 重要 | POST `/admin/activities/{id}/status`、`/admin/coupons/{id}/status`、`/admin/promos/{id}/status` | `ActivityAdminController.java:33-38`；`CouponAdminController.java:36-41`；`PromoAdminController.java:37-42`（服务层 `ActivityAdminService.java:64-77` 等） | status 任意整数，无状态机迁移白名单 |
| 重要 | GET 三个 `/admin/...` 分页 | `ActivityAdminController.java:41-46`；`CouponAdminController.java:49-54`；`PromoAdminController.java:60-65` | pageSize 无上限 + pageNum 无下限，可大结果集打 DB（普通用户可调） |
| 重要 | POST `/coupons/claim` | `CouponCenterController.java:41,54-59` | 裸内联 DTO 无 `@Valid`/注解，couponId 空值、requestNo 超长 |
| 重要 | POST `/notify/pay/{channel}` | `ChannelNotifyController.java:33-48`；`PayNotifyRequest.java` | 回调 body 无 `@Valid`，空字段以 NPE/500 代替规范错误 ACK |
| 建议 | POST `/orders` | `CreateOrderRequest.java:25,28,33,40,43,64`；`OrderCreateServiceImpl.java:527-543` | orderType/source 无白名单（非法值静默按普通单落库）；items/fromCartIds 无批量上限；运费/积分缺非负注解（引擎已钳） |
| 建议 | POST `/inner/product/sku/list` | `ProductInnerController.java:43-46` | List 入参无 @Size，IN 子句无上浮 |
| 建议 | POST `/platform/recon/retry`、GET `/platform/recon/diffs`、POST `/admin/reconcile/settle` | `ReconcileController.java:42,56`；`AdminController.java:77-82` | retry limit 无上浮；status 无白名单（空结果）；date 解析异常误报 10009 |
| 建议 | POST `/admin/products/{spuId}/violation` | `AdminGoodsController.java:51-56` | remark 无 @Size |
| 建议 | 全局错误处理 | `GlobalExceptionHandler.java:40-67` | 缺 HttpMessageNotReadable/TypeMismatch handler（客户端错误误判系统故障）；非 401/403 全返回 HTTP 200；约束消息泄露方法签名（:50） |
| 建议 | 非法终端 | `ChannelLimits.java:72-77` | 抛 IllegalArgumentException → 10009，应为 10001 |
| 建议 | PUT `/cart/select`、PUT `/cart/select-all` | `CartSelectRequest.java:21,27`；`CartController.java:61-73` | ids 无 @Size（selected 已服务端归一化） |
| 建议 | 售后/发货/审核相关字符串 | `EvidenceRequest.java:15-17`；`AuditRequest.java:15`；`ArbitrateRequest.java:13-15`；`LogisticsRequest.java:13-17`；`ShipRequest.java:17-20` | 枚举无白名单、文本无长度上限，超长写库异常→10009 |
| 建议 | 商品/评价媒体与富文本 | `CommentCreateRequest.java:56-61`；`CommentAppendRequest.java:16`；`SpuSaveRequest.java:43,46,48`；`EvidenceRequest.java:17` | URL 无元素长度/协议/域名白名单；detailJson 无大小约束；无 multipart 上限配置 |
| 建议 | GET `/merchant/aftersales/page` | `MerchantAftersaleController.java:66-69`；`AftersaleServiceImpl.java:674-683` | 非商户身份返回空页而非 403，口径不一致 |
| 建议 | 内部资金/积分命令 | `AmountCommand.java:33`；`PointsLockCommand.java:30,34`；`PointsRefundCommand.java`（零注解） | 缺 @Positive/@NotNull 契约注解（服务层已兜底，仅 /inner 令牌可达） |
| 建议 | 发票 contentScope | `InvoiceRequest.java:17`；`InvoiceServiceImpl.java:126` | 1/2 无白名单，原样落库 |

### 7.3 分级结论

**阻断（上线前必须清零）：无新增阻断项。** SECURITY_REVIEW.md 的 12 项阻断在当前代码中已基本完成整改（网关 /inner 收口+归一化、商户注册收口、支付金额服务端化、平台鉴权补齐、售后详情归属、限流/加密等配套代码均已落地）；本轮未发现同等级别的新增可直接导致未授权资金操作的缺陷。建议另行确认 H-3（JWT 密钥生产强制注入）的部署侧配置后复测。

**重要（应在本迭代修复，9 组）：**
1. 售后举证两个端点补归属校验（B2）；
2. 售后责任方不接受客户端自报，改由审核/仲裁认定，运费补偿走保险上限路径（B1）；
3. `AftersaleApplyRequest.items` 补 `@Valid`，qty 非法值前置 400（N-1）；
4. 营销 7 个 `/admin/**` GET 补 `requirePlatformAdmin`（A1）；
5. 营销 3 个保存接口补金额/折扣/库存正数校验、枚举白名单、嵌套 `@Valid` 与列表上限（M-1/M-2/M-3/N-2）；
6. 3 个 changeStatus 改为状态机迁移白名单，拒绝任意目标状态；
7. 营销 3 个管理分页改用 `PageQuery.safePageSize()` 钳制；
8. 领券、渠道回调两个对外写接口补 `@Valid` 与 DTO 约束；
9. `GlobalExceptionHandler` 增补消息不可读/类型不匹配两类 400 处理，避免客户端错误触发系统告警。

**建议（健壮性/契约硬化）：** 批量列表统一 `@Size` 上限（下单行、勾选、SKU、targets/levels、内部 IN 查询）；全量字符串字段补 `@Size`；媒体 URL 改对象存储托管+协议/域名白名单，富文本与请求体显式声明大小；参数级校验统一引入 `@Validated`；错误响应按语义设置 HTTP 状态码并收敛异常消息；结算分页 pageNum 下限；`merchant/aftersales/page` 对非商户返回 403；内部命令补全 Bean Validation 契约注解。
