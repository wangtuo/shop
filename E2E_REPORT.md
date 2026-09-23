# shop-e2e 跨域黑盒场景测试报告

- 模块：`shop-e2e/`（仅本模块与本报告为本次交付物，未改动任何其他模块的源码 / pom / 配置 / SQL / 脚本）
- 形态：纯黑盒 HTTP 测试。JUnit 5 + JDK 17 内置 `java.net.http.HttpClient` + Jackson，不启动 Spring 上下文、不引用 main 类、不读 `application.yml`。
- 入口：网关 `http://localhost:8080`（`-Dshop.gateway=` 可覆盖），统一经网关鉴权 / 路由 / 头清洗。
- 门控：每个 `*E2ETest` 类级 `@EnabledIfSystemProperty(named="shop.e2e", matches="true")`，默认 `mvn test` 全部跳过。
- 规模：**11 个测试类，54 个测试方法**（其中 1 个为 6 渠道参数化，实际执行 **59 个用例实例**）。

## 1. 运行方式

前置：7 个业务服务 + 网关 + 其依赖（MySQL / Redis / RocketMQ 等）已在目标环境启动，网关监听 8080。本测试不负责启停任何服务或容器。

```bash
# 标准执行（协调者统一编译运行，本模块作者未执行 mvn）
mvn -pl shop-e2e test -Dshop.e2e=true

# 指定网关
mvn -pl shop-e2e test -Dshop.e2e=true -Dshop.gateway=http://10.0.0.1:8080

# 只跑某一组
mvn -pl shop-e2e test -Dshop.e2e=true -Dtest=PayE2ETest

# 显式允许触发全局手工日结（默认关闭，见缺口 #7）
mvn -pl shop-e2e test -Dshop.e2e=true -Dshop.e2e.settle=true
```

结果约定：业务包裹体 `{code,message,data}`，`code=0` 成功；分页 `{pageNum,pageSize,total,list}`；金额一律 Long 分。

### 1.1 账号引导（不依赖固定种子也能跑）

注册接口强制 `userType=0`，C 端无法自助注册管理员 / 商户（缺口 #1），因此管理端 / 商户端身份按以下优先级解析（见 `support/World.java`）：

1. `-Dshop.admin.token` / `-Dshop.merchant.token` 直接注入现成 JWT（`-Dshop.merchant.id` 显式指定商户号，否则用 JWT 内身份调 `/users/me` 解析）；
2. 否则用账号口令登录，默认 `admin/admin123456`、`merchant/merchant123456`，可用
   `-Dshop.admin.account` `-Dshop.admin.password` `-Dshop.merchant.account` `-Dshop.merchant.password` 覆盖；
3. 仓库自带种子脚本 `deploy/loadtest/seed.sh` 与 `sql/` 可用于准备上述账号；
4. 商户入驻：`@BeforeAll` 以管理员身份尽力调用 `POST /api/settlement/admin/merchants`（应缴保证金 100000 分）做幂等兜底；若管理员 / 商户身份不可用，整类通过 JUnit `Assumptions` 跳过而不是失败。

所有 C 端买家均由测试自行注册（唯一用户名 / 手机号），用例之间、重复执行之间数据互不依赖、顺序无关。

## 2. 测试基建（`com.shop.e2e.support`）

| 类 | 职责 |
|---|---|
| `ApiClient` | JDK HttpClient 封装：baseURL、Bearer token、JSON 序列化、`Raw{httpStatus,text,bizCode(),data()}`、`get/post/put/delete/mustPost/mustPut/rawRequest(额外请求头)`；静态 `obj(...)` JSON 构造器与 `mapOf` 查询参数 |
| `DataFactory` | 全局序号：唯一用户名（≤32 字符）、唯一手机号、`clientToken`、`notifyId`、渠道流水号、物流单号、SKU 编码等，保证可重复执行 |
| `Poller` | 最终一致轮询：默认 10s / 步长 500ms，长超时 20s；`await` 吞掉中间异常 |
| `World` | 单例场景世界：身份引导、买家 / 收货地址、类目品牌 / SPU-SKU / 审核上下架 / 补货、券与三类活动、五类下单、7 渠道下单与 HMAC 回调（含签名算法镜像）、售后全流转、商户结算、一键"买到待发货 / 买到已完成" |

回调签名与服务端 `SignVerifier` 完全对齐（以代码为准）：TreeMap 排序键 `amountFen,channelCode,channelTxnNo,notifyId,payNo,status`（`paidTime` 非空时追加），`k=v&` 拼接（去尾 `&`），HmacSHA256 十六进制，渠道密钥 `mock_<channel>_secret_2026`（wechat/alipay/bank/uqr/huabei/baitiao）。

## 3. 场景矩阵（11 组 × 用例 × 真实端点）

网关前缀路由（StripPrefix=2）：`/api/user→用户`、`/api/product→商品`、`/api/marketing→营销`、`/api/order→订单(含购物车)`、`/api/pay→支付`、`/api/settlement→结算`、`/api/aftersale→售后`。

### 01 认证与网关安全 — `AuthE2ETest`（5）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 注册→登录→我的资料 / 等级，身份为普通用户 | `POST /api/user/auth/register`、`POST /api/user/auth/login`、`GET /api/user/users/me`、`GET /api/user/users/level` |
| 2 | 错误口令登录拒绝，不发 token | `POST /api/user/auth/login` |
| 3 | 无 token / 伪造 token 访问受保护资源 → HTTP 401、业务码 10002 | `GET /api/order/orders` |
| 4 | 伪造 `X-User-Id/X-User-Type/X-Merchant-Id` 被网关清洗：匿名带伪造头仍 401；合法 JWT 带伪造头身份不变 | `GET /api/user/users/me`（raw 头注入） |
| 5 | `/inner` 内网段 5 种变体（`/inner/`、`/..%2f`、双重编码 `/%252e%252e`、分号 `/inner;`、大小写 `/%69nner`）对外一律 404 / 10404 | `GET /api/user/inner/users/me` 等 |

### 02 商品域 — `CatalogE2ETest`（5）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 平台建三级类目 + 品牌，匿名类目树可见 | `POST /api/product/categories`、`POST /api/product/brands`、`GET /api/product/categories/tree` |
| 2 | SPU+SKU 草稿(0)→提交审核(1)→平台通过→直接在售(3) | `POST /api/product/merchant/products`、`POST .../merchant/products/{id}/submit`、`POST /api/product/admin/products/{id}/audit`、`GET /api/product/products/{id}` |
| 3 | 在售商品匿名详情 / 关键字分页搜索可查，重复读一致（缓存不脏读） | `GET /api/product/products/{id}`、`GET /api/product/products?keyword=&pageNum=&pageSize=` |
| 4 | SKU 价格快照：售价 / 可售库存 / 可售状态 | `GET /api/product/products/skus/{skuId}/price` |
| 5 | 下架(4)→不可售；补货 +15；重新提交审核后恢复在售(3) | `POST .../merchant/products/{id}/offsale`、`POST .../merchant/products/skus/{id}/replenish`、`.../submit`+`.../audit` |

### 03 营销域 — `MarketingE2ETest`（7）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 平台建券并启用→券中心可见→领取→我的券未使用(0) | `POST /api/marketing/admin/coupons`、`POST .../admin/coupons/{id}/status?status=1`、`GET /coupons/center`、`POST /coupons/claim`、`GET /coupons/my` |
| 2 | 同用户重复领同券被拒（每券每人 1 张） | `POST /api/marketing/coupons/claim` ×2 |
| 3 | H-1 安全契约：系统发券已收口内网 Feign，C 端 `/coupons/issue` 业务拒绝，`/inner/...` 外网 404 | `POST /api/marketing/coupons/issue`、`POST /api/marketing/inner/marketing/coupon/issue` |
| 4 | 秒杀活动（独立 Redis 库存）创建启用 + 秒杀价试算 | `POST /admin/activities`、`POST /admin/activities/{id}/status`、`POST /h5/marketing/calculate`（orderType=2，断言 seckill 价） |
| 5 | 拼团活动创建启用 + 拼团价试算（开团 / 参团无独立 HTTP，缺口 #5） | 同上，orderType=3 |
| 6 | 预售活动：定金阶段 / 尾款阶段两阶段试算（SKU stockType=2、presaleFlag=1） | 同上，orderType=4，`presaleFinalStage=false/true` |
| 7 | 六层试算（商品促销 / 店铺促销 / 平台券 / 店铺券 / 会员券 / 积分 + 运费）：3 件 1000 分商品用 1000 分券，最大余数分摊 333/333/334，仅 1 行 +1，逐行与总金额守恒 | `POST /api/marketing/h5/marketing/calculate` |

### 04 购物车 — `CartE2ETest`（5）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 加入→改数量→删除 | `POST /api/order/cart`、`PUT /api/order/cart/{cartId}`、`DELETE /api/order/cart/{cartId}`、`GET /api/order/cart` |
| 2 | 单选 / 全不选 / 反选，selected 标记正确 | `PUT /api/order/cart/select`、`PUT /api/order/cart/select-all?selected=&shopId=`、`POST /api/order/cart/invert` |
| 3 | 单行数量上限 99：改为 100 拒绝 | `PUT /api/order/cart/{cartId}` |
| 4 | 下架商品 `invalid=1,invalidReason=1`；直接下单被拒；一键清理失效 | `GET /api/order/cart`、`POST /api/order/orders`、`DELETE /api/order/cart/invalid` |
| 5 | 移入收藏成功且购物车行保留 | `POST /api/order/cart/{cartId}/favorite` |

### 05 下单 — `OrderCreateE2ETest`（7）

订单号编码：18 位 = YYMMDD(6) + 业务码 01/02/03/04/05(2) + userId 末 4 位 + 6 位日序列。

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 普通单 orderType=1：01 段、待付款(10)、金额正确、列表可见 | `POST /api/order/orders`、`GET /api/order/orders/{no}`、`GET /api/order/orders` |
| 2 | 秒杀单 orderType=2：02 段、单价取秒杀价 | 同上 + `POST /api/marketing/admin/activities` |
| 3 | 拼团单 orderType=3：03 段，下单内部自动开团 | `POST /api/order/orders`（团号内部维护，缺口 #5） |
| 4 | 预售定金单 orderType=4：04 段（尾款挂接无外部入口，Assumption 守卫，缺口 #5） | `POST /api/order/orders` |
| 5 | 换货单 orderType=5：仅售后域内部发起，C 端直下 Assumption 守卫 | `POST /api/order/orders` |
| 6 | `clientToken` 幂等：同 token 重提返回同一订单号 | `POST /api/order/orders` ×2 |
| 7 | 非法单拒绝：SKU 不存在 / 数量 0 | `POST /api/order/orders` |

### 06 支付 — `PayE2ETest`（5 个方法 / 10 个用例实例）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | **参数化 ×6**：微信 / 支付宝 / 网银 / 银联二维码 / 花呗 / 白条 mock 渠道，HMAC 正确的 SUCCESS 回调驱动支付成功（20→30）与订单待发货 | `POST /api/pay/pays`、`POST /api/pay/notify/pay/{channel}`、`GET /api/pay/pays/{payNo}` |
| 2 | 余额支付（payMethod=3）：建单即成功无回调（环境无余额时 Assumption 跳过） | `POST /api/pay/pays` |
| 3 | 坏签名（翻转签名末位）→ 60002；支付单仍 10、订单仍 10，状态不变 | `POST /api/pay/notify/pay/{channel}` |
| 4 | 同 `notifyId` 重复回调幂等：只成功一次（渠道+notifyId 幂等键） | `POST /api/pay/notify/pay/{channel}` ×2 |
| 5 | 组合支付（微信 7000 + 支付宝 3000）→ 全额 SUCCESS 回调（10000）→ 售后仅退款 → 退款 10000、退款单成功，资金按渠道最大余数比例拆分且总额守恒（拆分行无 HTTP 投影，缺口 #6） | `POST /api/pay/pays`（parts[]）、回调、`POST /api/aftersale/aftersales`、`POST /api/aftersale/merchant/aftersales/{no}/audit`、`GET /api/pay/refunds/{refundNo}` |

### 07 支付成功扇出 — `PaySuccessFanoutE2ETest`（1）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 支付后：TCC 锁定（可售 19 / 锁定 ≥1 / 占用 0）→ 回调后锁定转占用且可售不回弹；积分到账且有流水；成长值增加；清算按订单登记（stage=10，payAmountFen=10000）；同 notifyId 重放 + 新 notifyId 迟到 ACK 均不二次执行（库存 / 积分 / 清算行数稳定 =1） | `GET /api/product/products/skus/{id}/price`、`GET /api/user/users/points`、`/users/points/flows`、`/users/level`、`GET /api/settlement/merchant/clearing`、`POST /api/pay/notify/pay/{channel}` |

### 08 履约 — `FulfillmentE2ETest`（3）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 发货(30)→确认收货(40)→五星评价→匿名评价列表可见（白名单） | `POST /api/order/merchant/orders/{no}/ship`、`POST /api/order/orders/{no}/confirm`、`POST /api/product/comments`、`GET /api/product/comments/products/{spuId}` |
| 2 | 发货后待收货(30)（物流单号实体已持久化但 `OrderDTO` 不投影，仅验状态，见小缺口） | `POST .../merchant/orders/{no}/ship`、`GET /api/order/orders/{no}` |
| 3 | 发票申请保存与查询：类型 / 抬头 / 邮箱回显、状态字段存在；开具由 04:10 定时任务完成，无手动触发（缺口 #3） | `PUT /api/order/orders/{no}/invoice`、`GET /api/order/orders/{no}/invoice` |

### 09 售后 — `AftersaleE2ETest`（6）

状态机：10 审核 →（1/5 直接退款；2/3 退 20 待退货→30 商家待收货；4 退 41 待补发发货）→42 已发 / 待签收→43→50 完成；55 驳回。

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 仅退款(1) 待发货：同意即 50，refundFen=8000、refundNo 回填；商户（待结算+保证金）合计不增加（瀑布第一档：先冲待结算） | `POST /api/aftersale/aftersales`、`POST /merchant/aftersales/{no}/audit`、`GET /aftersales/{no}`、`GET /api/settlement/merchant/account` |
| 2 | 退货退款(2) 已完成单：审核→买家退货物流→商家收货→50，refundFen=7000 | `.../return-logistics`、`POST /merchant/aftersales/{no}/receive` |
| 3 | 换货(3)：退回→收货→指定换发 SKU 发货→买家签收→50，exchangeSkuId 落单、refundFen=0 | `POST /merchant/aftersales/{no}/ship`（exchangeSkuId）、`POST /aftersales/{no}/exchange-confirm` |
| 4 | 补发(4)：同意后直接补发→签收→50，不退不换 | `.../ship`、`.../exchange-confirm` |
| 5 | 价保(5) 试算：6 字段结构完整（原价 / 现价 / 单位差价 / 总差价 / eligible / reason），无在售改价入口时差价 0（缺口 #2） | `POST /api/aftersale/aftersales/price-protect/trial` |
| 6 | 运费险责任侧 responsibilitySide=3 可落单、freightRefundFen 字段存在；理赔明细无查询、投保无外部入口（Assumption 守卫，缺口 #4） | `POST /api/aftersale/aftersales`（responsibilitySide=3）、`POST /aftersales/{no}/cancel` |

### 10 结算 — `SettlementE2ETest`（7）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 商户账户 7 字段：可用 / 冻结 / 待结算 / 保证金余额 / 应缴(100000) / 预警阈值 / 提现阻断标记 | `GET /api/settlement/merchant/account` |
| 2 | 缴保证金 +100000 入账且钱包流水可查（注意：会累积共享商户保证金余额） | `POST /api/settlement/merchant/deposit`、`GET /merchant/deposit` |
| 3 | 提现门槛：9999（<10000 下限）与 50000001（>单日 5000 万分上限）均拒绝 | `POST /api/settlement/merchant/withdrawals` |
| 4 | 余额不足 / 保证金 <50% 预警时提现阻断（取可用+100 万分必触发其一） | `POST /api/settlement/merchant/withdrawals` |
| 5 | 手续费：当月前 3 笔免费、第 4 笔 0.1% 且最低 200 分（Assumption：已有 ≥40000 分**已结算**可用余额且当月无提现，避免污染共享环境） | `POST /merchant/withdrawals`、`GET /merchant/withdrawals` |
| 6 | S 级周期：确认收货后轮询清算 stage=20、dueDate=今天+1；对账单只读可查（A/B/C 需时钟推进，缺口 #7） | `GET /merchant/clearing`、`GET /merchant/statements` |
| 7 | 手工日结仅 `-Dshop.e2e.settle=true` 时触发，默认跳过（全局资金推进） | `POST /api/settlement/admin/reconcile/settle?date=yyyy-MM-dd` |

商户等级 / 入驻（用例内部准备）：`POST /api/settlement/admin/merchants`、`PUT /api/settlement/admin/merchants/{id}/level`。

### 11 并发 — `ConcurrencyE2ETest`（3）

| # | 用例 | 主要端点 |
|---|---|---|
| 1 | 秒杀库存 10：`CyclicBarrier` + 20 线程 2N 请求 → 恰好 10 单成功、订单号互不重复；每个成功者在自己列表可见 02 段单；第 21 人被拒 | `POST /api/order/orders`（orderType=2）、`GET /api/order/orders` |
| 2 | 库存 1 的券 2 线程并发领取 → 恰好 1 人成功；第三人再领失败 | `POST /api/marketing/coupons/claim` |
| 3 | 同一 `clientToken` 并发双提 → 两次幂等成功、同一订单号、只生成一单 | `POST /api/order/orders` ×2 并发 |

MQ 重复消息幂等（订单支付事件）在 07 组用同 notifyId 重放 + 迟到 ACK 覆盖。

## 4. 契约缺口 / 不一致台账（以代码为准）

| 编号 | 级别 | 模块 / 端点 | 缺口 | 测试应对 |
|---|---|---|---|---|
| #1 | **阻断** | 用户 / 结算域：注册与 `POST /settlement/admin/merchants` | 注册强制 `userType=0`，无管理员 / 商户自助开通 API；黑盒无法纯 API 引导管理端身份 | 支持种子账号或 `-Dshop.admin.* / -Dshop.merchant.*` 注入；`@BeforeAll` 尽力入驻，身份缺失则整类 Assumption 跳过 |
| #2 | 阻断正向用例 | 商品域 SPU/SKU 编辑 | 仅草稿(0) / 审核驳回(2) 可编辑，在售(3) 后无改价 / 改库存类型 HTTP | 价保只验试算结构与零差价，正向降价赔付不可黑盒构造 |
| #3 | 功能不可即时验 | 订单域发票 | 开具仅 `InvoiceIssueJob`（cron `0 10 4 * * ?`），无手动触发端点；invoiceNo/pdfUrl/issueTime 定时回填 | 只验申请保存 / 查询回显，不等待 cron |
| #4 | 功能不可即时验 | 售后域运费险 | 有 `AftersaleInsurance` 实体但无查询端点；理赔为 72h 延迟任务；无 C 端投保入口 | 仅在环境允许时验证 responsibilitySide=3 落单与字段存在，随后取消；Assumption 守卫 |
| #5 | 链路不可分段验 | 营销 / 订单域 | 拼团开团 / 参团、预售定金登记 / 尾款挂接均在下单 / 支付应用服务内部驱动，无独立 C 端 HTTP；换货单(5) 仅售后内部发起 | 用 orderType=3/4 直下订单 + 试算覆盖；尾款支付与 05 段直下用 Assumption 守卫 |
| #6 | 分支不可强制 / 无投影 | 支付 / 售后 / 结算 | 退款瀑布"待结算不足→扣保证金"分支无法从外部稳定构造；`RefundSplitter` 分渠道拆分行无 HTTP 投影 | 验证瀑布第一档（待结算扣减）与退款总额 / 状态守恒，不逐渠道断言 |
| #7 | 需时钟 / 运维能力 | 结算域 | A/B/C 到账日 +7/+15/+30 需时钟推进；提现审核 / 打款为定时任务；无 C 端余额充值入口，已结算资金不可即时制造 | 只黑盒观测 S(T+1)；手续费正向用例 Assumption 守卫；手工日结默认关闭需显式 `-Dshop.e2e.settle=true` |
| 小缺口 | 投影缺失 | 订单域 `OrderDTO` | 物流单号 / 公司在订单实体持久化但 DTO 不投影 | 发货用例只断言状态推进到待收货(30)，不断言单号回显 |
| 安全契约（符合预期） | — | 营销域 `POST /coupons/issue` | H-1 已将系统发券收口为内网 Feign，C 端不可达、`/inner` 外网 404 | 用例 03-3 固化该安全行为，防止回退 |

阻断级缺口合计：**1 个**（#1，环境预置账号）；#2 阻断的是价保正向断言而非测试执行（环境给账号即可全量跑通）。

## 5. 不可自动化（黑盒内）步骤与原因

1. 管理员 / 商户账号开通：无自助 API，需种子数据 / 运维创建后用 `-D` 注入（缺口 #1）。
2. 发票开具回执（invoiceNo / PDF）：等待每日 04:10 cron，单次测试窗口内不可触发（缺口 #3）。
3. 运费险理赔到账：72 小时延迟任务 + 无查询端点（缺口 #4）。
4. A/B/C 商户到账日（+7/+15/+30）与提现审核 / 打款：需推进系统时钟或等待定时任务（缺口 #7）。
5. 价保降价赔付正向场景：上架商品无改价入口（缺口 #2）。
6. 预售尾款支付、拼团成团 / 失败结算：无独立 HTTP 挂接点（缺口 #5）。
7. 用户余额充值后做余额支付正向：无 C 端充值入口，依赖环境预置余额（否则 06-2 跳过）。

## 6. 数据隔离与可重复性

- 每个买家、类目 / 品牌 / SPU / SKU、券、活动、clientToken、notifyId、渠道流水、物流单号均带全局唯一序号，用例之间无共享业务数据、无执行顺序依赖，可反复执行。
- 唯一的共享面是被注入的商户钱包：保证金缴纳（10-2）会累积余额（幂等入驻不应重复收费），手续费正向用例（10-5）以"当月零提现 + 足额已结算余额"为前提，不满足即跳过，不制造脏数据。
- 并发用例全部使用独立活动 / 独立券 / 独立买家，避免与其他用例争抢库存。

## 7. 交付物清单

```
shop-e2e/pom.xml
shop-e2e/src/test/java/com/shop/e2e/
├── support/{ApiClient,DataFactory,Poller,World}.java
├── AuthE2ETest.java            # 01 认证 / 网关安全 (5)
├── CatalogE2ETest.java         # 02 商品 (5)
├── MarketingE2ETest.java       # 03 营销 (7)
├── CartE2ETest.java            # 04 购物车 (5)
├── OrderCreateE2ETest.java     # 05 下单 (7)
├── PayE2ETest.java             # 06 支付 (5 方法 / 10 实例)
├── PaySuccessFanoutE2ETest.java# 07 支付扇出 (1)
├── FulfillmentE2ETest.java     # 08 履约 (3)
├── AftersaleE2ETest.java       # 09 售后 (6)
├── SettlementE2ETest.java      # 10 结算 (7)
└── ConcurrencyE2ETest.java     # 11 并发 (3)
E2E_REPORT.md                   # 本报告
```

未执行 `mvn`、未启停任何本地服务或容器；已用 JDK17 `javac` 对全部 16 个源文件做编译期语法校验通过（classpath 仅 JUnit5 / Jackson，不涉及业务模块类）。

---

## 附录 A：2026-09-17 生产级验收轮次结果

- 构建：全 reactor `mvn -o clean package`，**885 个单元测试 0 失败 / 0 错误**（shop-e2e 54 用例默认跳过）。
- E2E：`mvn -o -pl shop-e2e test -Dshop.e2e=true -Dshop.gateway=http://[::1]:8080 -Dshop.e2e.settle=true`
  （admin/load_merchant 种子账号），结果 **Tests run: 59, Failures: 0, Errors: 0, Skipped: 2**，BUILD SUCCESS。
  跳过 2 例均为共享环境有意门控：手工日结的月度提现手续费「本月已免一次」前置（`Assumptions`）与支付模块的环境前置门控；无失败、无条件性通过率下降。
- 同批 jar 的混沌验收：`bash deploy/loadtest/chaos.sh` → `PASS=25 FAIL=0`（Redis 20s 宕机窗口读链路 0 失败、
  RocketMQ 45s 宕机 7 库 outbox 共享 90s 并行清空、恢复后 30s 稳态零失败、7/7 慢车道挂起探针 120s 内重放成功）。
- 该轮修复的中间件 HA 与 AOP 绑定崩溃根因见 CODE_REVIEW.md「2026-09-17 第三轮」。
