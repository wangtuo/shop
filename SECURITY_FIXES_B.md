# 安全修复报告（B 组）

修复范围：shop-pay-service、shop-aftersale-service、shop-marketing-service、shop-settlement-service，
以及 shop-api 中对应 4 域的 dto/client 包。未改动 shop-framework / shop-common / shop-gateway /
其他业务服务 / 任何 application.yml / sql / deploy 目录。

测试与构建门禁（`source ~/.sdkman/bin/sdkman-init.sh`，未使用 `mvn -am`）：

| 模块 | 测试数 | 结果 | fat jar |
|---|---|---|---|
| shop-pay-service | 62 | 全绿 | OK（106M） |
| shop-aftersale-service | 59 | 全绿 | OK |
| shop-marketing-service | 83 | 全绿 | OK |
| shop-settlement-service | 113 | 全绿 | OK |

> shop-api 新增/修改了类，已执行 `mvn -pl shop-api install -DskipTests` 发布到本地 ~/.m2，
> 上线流水线需重新构建/部署 shop-api 制品。

---

## C-3 支付域越权与伪造（shop-pay-service，最高优先级）

### 改动文件
- 新增 `pay/support/WebIdentity.java`：requireUser / requirePlatformAdmin。
- 改 `pay/feature/payment/controller/PayController.java`：POST /pays 强制登录；
  userId 只取 X-User-Id 并覆盖 body；GET /pays/{payNo}、GET /pays/order/{orderNo} 传入登录身份。
- 改 `pay/feature/payment/service/impl/PaymentServiceImpl.java`：
  - C 端下单支付：OrderClient 反查 orderNo → 不存在 ORDER_NOT_FOUND；
    order.userId 与登录用户不符 → 403；**金额强制取订单应付 payFen**（body amountFen 被忽略）；
    组合支付 part 金额之和必须等于订单应付，否则 PARAM_INVALID；pay_scene 仍服务端派生。
  - 内部 /inner/pay 走 CreatePaymentCommand 可信链路，保持既有幂等/状态机/验签顺序不变。
  - 查询鉴权 viewByPayNo/viewByOrderNo：平台(userType=2)放行，其余仅本人，越权 403。
- 改 `pay/feature/refund/controller/RefundController.java` 与 service：
  - **删除 C 端发起退款入口**（POST /refunds 与 RefundApplyRequest 一并删除）；
    退款只保留售后域经 PayClient → /inner/pay/refund 的内部链路（已验证链路完整）。
  - GET /refunds/{refundNo}：平台放行；商户经 OrderClient 反查退款单订单，
    订单内任一 item.merchantId 命中才放行，否则 403；消费者 403。
  - POST /refunds/{refundNo}/retry 仅平台运营可用。
- 改 `pay/feature/recon/controller/ReconcileController.java`：/platform/recon/** 4 个端点全部强制 userType=2。
- 测试：PaymentServiceImplTest(15)、RefundServiceImplTest(8)、RefundControllerEndpointTest（反射断言外部 POST 入口不存在）、WebIdentityTest(4) 等；覆盖伪造 userId、金额篡改、组合支付不等额、越权查询 403、平台/商户/消费者矩阵。

### 残留风险
- 支付/退款的商户侧查询鉴权依赖 OrderClient 返回的订单 item.merchantId；订单域数据异常（无 item）时商户查询按 403 处理（fail-closed）。
- 内部 Feign 链路安全仍依赖网关阻断 /inner/** 外网访问与 X-Internal-Token 体系（本次未改，需运维侧确认网关规则）。

---

## H-1 平台/商户/内部接口越权（4 模块）

### 改动文件
- pay：ReconcileController 全部写操作 userType=2（见 C-3）。
- aftersale：`controller/PlatformAftersaleController.java#arbitrate` 与
  `service/impl/AftersaleServiceImpl.java#arbitrate` 双层 requirePlatformAdmin。
- marketing：
  - `activity/controller/ActivityAdminController.java`、
    `coupon/controller/CouponAdminController.java`、
    `promo/controller/PromoAdminController.java`：save 与 /{id}/status 共 6 个写操作首行 requirePlatformAdmin；GET 读不拦。
  - **对外 POST /coupons/issue 下线**（CouponCenterController 删除该方法及 IssueRequest）。
  - shop-api 新增 `api/marketing/dto/CouponIssueCommand.java`，
    `api/marketing/client/MarketingClient.java` 新增 `issueCoupon`（POST /inner/marketing/coupon/issue）；
    `inner/InnerMarketingController.java` 实现该契约（issueWay 缺省=系统补偿，requestNo 幂等透传）。
    全仓 grep 确认无其他 /coupons/issue 调用方。
- 测试：marketing AdminControllerAuthTest(9，消费者/商户 403 且 service 零交互、平台通过)、
  WebIdentityTest(4)、CouponIssueRemovedTest(2，反射断言入口不存在且 /claim 保留)、InnerCouponIssueTest(2)。

### 残留风险
- 三个 admin 控制器的 GET（列表/详情/ targets）未强制平台身份，保持"商户可读"的既有业务口径；
  若后续确认这些列表含跨商户敏感字段，需要再收口。

---

## H-2 售后单枚举（shop-aftersale-service）

### 改动文件
- `controller/AftersaleController.java#detail` 传入 WebIdentity.requireUser()。
- `service/AftersaleService.java`：detail 签名增加 LoginUser viewer。
- `service/impl/AftersaleServiceImpl.java#detail`：
  平台(userType=2)放行；买家本人(userId 相等)或该单商户(merchantId 相等)放行；
  其余 403「无权查看该售后单」（不区分有无，避免单号枚举）。
- 测试 AftersaleServiceImplTest 新增 7 个用例：买家/商户/平台通过，
  其他买家、其他商户 403，仲裁接口消费者/商户 403（UserContext 每个用例置位、@AfterEach 清理）。

### 残留风险
- 售后单列表接口（非 detail）未在本次范围；若列表存在跨商户/跨用户查询参数，需要另行核查。

---

## M-1 收款账号落库加密 + 对外脱敏（shop-settlement-service）

### 改动文件
- 新增 `support/DataCipher.java`：AES-256/GCM/NoPadding，密文格式 `enc:v1:`+Base64(IV12‖密文‖TAG16)，
  每次随机 IV；密钥取 `-Dshop.data.enc-key` → 环境变量 `SHOP_DATA_ENC_KEY`
  → 开发默认值（仅本地）；非 32 字节以 SHA-256 派生；**prod profile 使用内置默认密钥启动 fail-fast**。
  无 `enc:v1:` 前缀的历史明文行 decrypt 原样返回（渐进迁移，不炸读）。
- 新增 `support/AccountMask.java`：账号 `**** **** **** 1234`（仅留后 4），姓名留首字。
- 新增对外 VO：`withdraw/vo/WithdrawVO.java`、`withdraw/vo/AutoWithdrawConfigVO.java`
  （实体密文 → 解密 → 脱敏；实体不再直接出现在任何 HTTP 响应）。
- 改 `withdraw/service/WithdrawService.java`：
  - doApply：channelAccount / accountName 加密落库；
  - saveConfig：配置账号加密落库，返回脱敏 VO；
  - getConfigMasked/pageMerchant：返回脱敏 VO（分页记录逐条转换）；
  - runAutoWithdraw：自动打款链路解密配置中的完整账号后再用（doApply 会重新加密入新单）。
- 改 `withdraw/controller/MerchantWithdrawController.java`：响应类型全部换成脱敏 VO。
- 测试：DataCipherTest(10：往返、随机 IV、篡改失败、错钥失败、自定义密钥、历史明文兼容、空值、SHA-256 派生、prod 默认密钥 fail-fast、空密钥拒绝)、
  AccountMaskTest(5)、WithdrawMaskingTest(3：分页脱敏、配置写密文回显脱敏、查询脱敏)；
  WithdrawServiceTest 增加密文断言、自动链路解密往返断言。

### 需要负责人执行的 DDL（本次禁止改 sql/，仅给建议；密文=前缀7 + Base64(IV12+明文+TAG16)）
现有 4 列宽度对 128/64 字符明文不足（128 字节账号密文约 215；64 字符 utf8mb4 姓名最坏约 387）：

```sql
ALTER TABLE t_sett_withdraw
  MODIFY COLUMN channel_account VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款账号（AES-GCM密文 enc:v1:）',
  MODIFY COLUMN account_name    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款人姓名（AES-GCM密文 enc:v1:）';
ALTER TABLE t_sett_withdraw_auto_config
  MODIFY COLUMN channel_account VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款账号（AES-GCM密文 enc:v1:）',
  MODIFY COLUMN account_name    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '收款人姓名（AES-GCM密文 enc:v1:）';
```

### 残留风险
- 历史明文行可读但不会自动改写；需要一次性数据迁移任务（读出明文→encrypt 回写），建议上线 DDL 后补。
- 密钥不支持在线轮换（密文带 v1 版本位，可扩展多版本密钥，当前未做）；密钥泄露后的轮换流程需另立预案。
- bank_name（开户行）视为非敏感信息未加密；如合规口径要求，可按同一工具加密（列宽同样要评估）。
- 平台运营端目前不展示提现账号（无 HTTP 出口）；若后续开放运营打款台，必须走独立内部 VO + 操作审计，不要直接回传实体。

---

## M-3 提现/保证金幂等键跨商户隔离（shop-settlement-service）

### 改动文件
- `withdraw/dto/ApplyWithdrawRequest.java`：新增可选 clientToken（≤64）与服务端内部字段 withdrawNo。
- `deposit/dto/DepositPayRequest.java`：新增可选 clientToken。
- `withdraw/controller/MerchantWithdrawController.java`：进入业务前由服务端
  SettleNoGenerator 预生成 withdrawNo 并**覆盖 body 任何伪造值**；@Idempotent 下沉到 service。
- `withdraw/service/WithdrawService.java#apply`：
  `key = #merchantId + ':' + (#request.clientToken ?: #request.withdrawNo)`。
- `deposit/controller/MerchantDepositController.java`：预生成保证金流水号 DP…；
  `deposit/service/DepositService.java` 新增 payDepositWeb（@Idempotent，
  `key = #merchantId + ':' + (#clientToken ?: #logNo)`），原 payDeposit 保留给内部/任务链路；
  writeLog 支持服务端预分配流水号。
- 未改动 IdempotentAspect（框架层）。
- shop-settlement-service/pom.xml 为 maven-compiler-plugin 开启 `<parameters>true</parameters>`
  （SpEL 按参数名取值的前提，仅对本模块生效）。
- 测试：IdempotencyKeyIsolationTest(4，直接驱动框架切面 + Redisson 捕获 redis key：
  两商户同账号同金额键不同、clientToken 优先、提现与保证金两条链路)、
  MerchantWithdrawControllerTest(2，服务端单号覆盖 body 伪造值；消费者 403)。

### 残留风险 / 知会
- 根 pom 未开 `-parameters`，**其他模块存量 @Idempotent SpEL 键（#request.xxx）在 Spring 6.1
  下实际取不到参数名**（本地变量表发现器已移除）。本次只在 settlement 模块 pom 内修复；
  建议负责人统一在根 pom 的 maven-compiler-plugin 配置 parameters=true 后回归全部模块。
- clientToken 由客户端保证唯一；缺省时每笔请求都会得到独立兜底键（不再按"账号+金额"做防重），
  防重复提交改由客户端传 token 承担，这是修复"两个商户同额互斥/同商户连续两笔正常提现被误杀"的预期行为。

---

## M-5 渠道密钥外置与生产 fail-fast（shop-pay-service）

### 改动文件
- 新增 `pay/channel/ChannelSecretProvider.java`：6 个 mock 渠道密钥改为
  `shop.pay.channel.MOCK_WECHAT.secret` … `MOCK_BAITIAO.secret`（环境变量大写形式可覆盖），
  dev 默认值仅本地；`shop.pay.mock-channels-enabled`（默认 true）；
  prod profile 下启用 mock 渠道、或任一密钥仍等于内置默认值 → 启动 IllegalStateException fail-fast。
  另保留 devDefaults() 与测试构造器。
- `pay/channel/ChannelLimits.java`：删除静态 SECRETS 与 secret()，限额/终端等逻辑不变。
- `pay/feature/payment/support/SignVerifier.java`：改注入 ChannelSecretProvider；
  未知渠道回调返回 PAY_SIGN_ERROR「回调渠道不支持」；HMAC-SHA256 + MessageDigest.isEqual 常量时比较不变。
- `feature/recon/service/impl/ReconcileServiceImpl.java` 同步改走 provider.requireKnownChannel。
- 测试：SignVerifierTest(7，自定义密钥签名验签通过、错钥 60002)、
  ChannelSecretProviderTest(5，dev 默认、prod 启用 mock fail-fast、prod 默认密钥 fail-fast、
  prod 轮换密钥通过、未知渠道 PARAM_INVALID)。

### 需要负责人配置（禁止写 application.yml，走环境变量/启动参数）
- `SHOP_PAY_CHANNEL_MOCK_WECHAT_SECRET` / `MOCK_ALIPAY` / `MOCK_BANK` / `MOCK_UQR` /
  `MOCK_HUABEI` / `MOCK_BAITIAO`（或 -Dshop.pay.channel.<CODE>.secret=...）；
- 生产 `SHOP_PAY_MOCK_CHANNELS_ENABLED=false`（或 -Dshop.pay.mock-channels-enabled=false）；
- `SHOP_DATA_ENC_KEY`（或 -Dshop.data.enc-key=32字节密钥，settlement 服务必需，勿用默认值）。

---

## L-2 错误信息不回显内部单号（shop-pay-service，顺手项）

- PaymentServiceImpl / RefundServiceImpl：去掉「支付单不存在: {payNo}」「订单无支付单: {orderNo}」
  「提现单/退款单」等后缀内部单号，冲突/状态类提示同样去号；notify/activeQuery 路径一并处理。
- 日志（服务端）仍保留完整单号用于排障，仅对外 message 不回显。

---

## 交付物清单（新增类汇总）

- pay：support/WebIdentity、channel/ChannelSecretProvider + 5 个测试类（见上）。
- aftersale：support/WebIdentity。
- marketing：support/WebIdentity；shop-api：CouponIssueCommand、MarketingClient#issueCoupon。
- settlement：support/DataCipher、support/AccountMask、withdraw/vo/WithdrawVO、
  withdraw/vo/AutoWithdrawConfigVO，及对应 5 个测试类；模块 pom 开启 -parameters。
