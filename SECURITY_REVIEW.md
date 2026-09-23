# shop 电商系统生产级安全审计报告

- 审计日期：2026-09-16
- 审计范围：shop-gateway、shop-user/product/marketing/order/pay/settlement/aftersale-service、shop-framework、shop-common
- 审计方式：只读静态审计 + 路径匹配实测（基于 spring-core/spring-web 6.1.6 的 `AntPathMatcher` 与 `PathPattern` 实测验证白名单/路由绕过）
- 契约依据：`CONTRACTS.md`（§Feign `/inner/**` 契约）、`design.md` 第 9/10 章（SSL、敏感数据脱敏存储、防重复提交/防超卖/防刷单）

---

## 一、漏洞总表（按风险等级排序）

### 严重（Critical）

#### C-1 内部 `/inner/**` 接口全部可经网关直达，无任何网络隔离或调用方认证

- 位置：
  - `shop-gateway/src/main/resources/application.yml:17-58`（7 条 `Path=/api/<domain>/**` + `StripPrefix=2` 路由，无任何 `/inner` 排除规则）
  - `shop-user-service/.../account/controller/InnerUserController.java:35-126`（类级 `@Anonymous`）
  - `shop-product-service/.../stock/controller/ProductInnerController.java:30-72`（类级 `@Anonymous`）
  - `shop-order-service/.../order/controller/InnerOrderController.java:19-31`（类级 `@Anonymous`）
  - `shop-pay-service/.../payment/controller/PayInnerController.java:23-48`（**无 `@Anonymous`**，仅要求"已登录任意用户"）
  - `shop-marketing-service/.../inner/InnerMarketingController.java:21-48`（**无 `@Anonymous`**）
- 证据（PathPattern 实测，spring-web 6.1.6）：
  - `/api/user/inner/user/balance/credit` 命中路由 `/api/user/**`
  - `/api/pay/inner/pay/refund` 命中路由 `/api/pay/**`
  - `/api/product/inner/product/stock/lock` 命中路由 `/api/product/**`
  - `/api/order/inner/order`、`/api/marketing/inner/marketing/lock` 同理命中
  - `StripPrefix=2` 后下游收到的路径正是 `/inner/user/balance/credit` 等内部端点；`CONTRACTS.md:53` 声称"仅服务间 Feign 调用可达"，但代码与部署（`deploy/kubernetes/00-namespace-config.yaml:84-93` 网关 ClusterIP 对外）均无网络策略/内网隔离
- 风险：
  1. **未授权**访问 `POST /api/user/inner/user/balance/credit`、`/balance/debit`、`/points/grant`、`/gift/debit`（`@Anonymous` 连 JWT 都不要）→ 任意给自己/他人账户充值余额、赠金、积分；
  2. 未授权锁/放/确认库存（`/inner/product/stock/lock|confirm|release|return`）→ 可搞崩全站库存；
  3. 未授权读取任意订单完整 DTO（`GET /api/order/inner/order?orderNo=`，`OrderQueryServiceImpl.getByOrderNo()` 无归属校验，含收货人姓名/手机/地址快照）；
  4. 任意已登录普通用户调用 `POST /api/pay/inner/pay/refund`（无 `@Anonymous` 不等于有授权，`AuthInterceptor` 只校验"有 X-User-Id"）→ 以 Feign 内部命令 `CreateRefundCommand` 发起退款；
  5. 任意已登录用户调用 `/api/marketing/inner/marketing/calculate|lock|confirm|release` 篡改优惠锁定状态。
- 修复建议：
  1. 网关层硬阻断：在鉴权过滤器中对规范化后的路径 `startsWith("/inner/")` 直接 404/403（最省事且纵深防御）；
  2. 下游再兜底：服务注册只监听内网网卡/独立端口，K8s NetworkPolicy 限制 `/inner/**` 仅集群内服务账号可访问；
  3. 内部接口增加独立的服务间认证（mTLS 或 `X-Internal-Token` 共享密钥 + Feign 拦截器注入，外部入口在网关剥离该头），不要仅靠 `@Anonymous` + "约定不暴露"。

#### C-2 匿名注册可绑定任意 merchantId，自助获取任意商户身份（商户接管）

- 位置：
  - `shop-user-service/.../auth/controller/AuthController.java:27-31`（注册 `@Anonymous`，网关白名单 `/api/user/auth/**`，`JwtAuthGlobalFilter.java:38`）
  - `shop-user-service/.../auth/service/impl/AuthServiceImpl.java:41-63`（`userType=1` 时仅校验 merchantId 非空，不校验该 merchantId 是否真实存在/是否已被绑定/是否经入驻审核）
  - `shop-user-service/.../auth/dto/RegisterRequest.java:28-31`（`userType`、`merchantId` 均为客户端可控字段）
- 风险：任何人调用 `POST /api/user/auth/register` 提交 `{"userType":1,"merchantId":<受害商户ID>,...}` 即可拿到 `utype=1, mid=<受害商户>` 的 JWT，随后可操作该商户全部资源：发货（`MerchantOrderController`）、售后仲裁前审核（`MerchantAftersaleController`）、商品上下架/改价（`MerchantGoodsController`，`AuthUtils.checkOwner` 比对的正是这个 mid）、查看商户资金账户/流水、发起提现（`MerchantWithdrawController`，提现收款账号由请求体提交 → 直接盗走商户货款）。
- 修复建议：注册接口禁止接收 `userType=1`（商户账号只能由平台入驻流程在审核通过后创建，见 settlement `AdminController` 的商户创建链路）；或注册时忽略请求体 merchantId，由服务端基于已审核通过的商户-账号绑定关系回填。

#### C-3 对外支付/退款接口信任客户端金额与 userId，任意用户可发起/查询任意订单支付与退款

- 位置：
  - `shop-pay-service/.../payment/controller/PayController.java:27-40`
  - `shop-pay-service/.../payment/service/impl/PaymentServiceImpl.java:87-138`（`validateRequest` 仅校验 orderNo 非空、userId 非空、amountFen>0、组合支付合计相等；**不校验该 orderNo 是否属于请求 userId、金额是否等于订单应付金额**）
  - `shop-pay-service/.../refund/controller/RefundController.java:25-39`（`POST /refunds`、`GET /refunds/{refundNo}`、`POST /refunds/{refundNo}/retry` 无任何身份/角色校验）
  - `shop-pay-service/.../refund/service/impl/RefundServiceImpl.java:81-97`（`apply` 把 `RefundApplyRequest` 的 userId/orderNo/amountFen 原样转为内部 `CreateRefundCommand`）
- 风险：
  1. `POST /api/pay/pays` 请求体可指定任意 `userId`、`amountFen` 对任意订单创建支付单（余额支付路径在同事务内直接扣款成功并发 `PaymentSucceededEvent`，订单侧 `PayEventConsumer.onPaid()` 只按 orderNo 推进状态，不回算金额 → 可对他人订单"支付"或干扰正常支付）；
  2. `POST /api/pay/refunds` 任意已登录用户对任意 orderNo 提交退款，userId/金额客户端自填；虽有 `addRefundedFen` 累计不超过实付额的条件更新（`RefundServiceImpl.java:166-169`）防透支，但首笔退款即可把任意订单的全款退到自己指定的余额账户（余额退回路径 `doRefundOne` 用 `refund.getUserId()`，`RefundServiceImpl.java:184-193`）；
  3. `GET /api/pay/pays/{payNo}`、`/api/pay/pays/order/{orderNo}`、`GET /api/pay/refunds/{refundNo}` 为水平越权信息泄露（payNo/orderNo 可枚举/来自其他渠道）。
- 修复建议：支付创建必须由订单域/支付域按 orderNo 反查订单，以登录用户比对 `order.userId`，金额强制取订单应付金额（忽略请求体 amountFen）；退款接口不应对 C 端开放（只接受售后域 Feign 内部调用，随 C-1 一并收口到 `/inner`），所有支付查询加 userId 归属过滤。

### 高（High）

#### H-1 平台运营/管理端接口普遍缺失 userType=2 垂直越权校验

- 位置：
  - `shop-pay-service/.../recon/controller/ReconcileController.java:23-53`（`/platform/recon/run|diffs|diffs/{id}/handle|retry` 全类无任何身份校验，任意登录用户可触发 T+1 对账、执行差错补偿打款）
  - `shop-aftersale-service/.../controller/PlatformAftersaleController.java:18-29` + `AftersaleServiceImpl.arbitrate()`（约 531 行起，无 `userType` 判断 → 任意登录用户可对平台介入单作出终局仲裁并决定赔付金额）
  - `shop-marketing-service/.../activity/controller/ActivityAdminController.java:20-39`（`/admin/activities` 新建/上下架秒杀、拼团、预售活动，无 UserContext 调用；全 marketing 服务 grep 不到任何 userType 校验）
  - `shop-marketing-service/.../coupon/controller/CouponAdminController.java:29-45`（券模板创建/上下架）
  - `shop-marketing-service/.../promo/controller/PromoAdminController.java:30-56`（促销规则管理）
  - `shop-marketing-service/.../coupon/controller/CouponCenterController.java:50-54`（`POST /coupons/issue` 任意登录用户可向任意 userId 发券，issueWay 自选）
- 对照：商品域做对了（`shop-product-service/.../support/AuthUtils.java:23-39`，`CategoryServiceImpl`/`BrandServiceImpl`/`SpuServiceImpl` 写操作均调用 `requirePlatform()`），结算域做对了（`WebIdentity.requirePlatformAdmin()`），可直接复用同款模式。
- 风险：普通用户伪造营销活动（0 元秒杀/无限券）、自行仲裁售后赔付、触发对账补偿，直接造成资金损失。
- 修复建议：框架层提供 `@RequirePlatform`/`@RequireMerchant` 注解或在上述 Controller/Service 统一调用 `AuthUtils.requirePlatform()`（userType=2）；`/coupons/issue` 收口为内部 Feign 接口。

#### H-2 售后单详情水平越权（IDOR）

- 位置：
  - `shop-aftersale-service/.../controller/AftersaleController.java:93-96`：`detail()` 只传 aftersaleNo，不传登录身份
  - `shop-aftersale-service/.../service/impl/AftersaleServiceImpl.java:635-642`：`detail(String no)` 直接返回售后单+明细，无 `requireOwner`/`requireMerchant`
- 证据：同文件其他所有操作（apply/cancel/resubmit/returnLogistics/exchangeConfirm/intervene 第 108/262/279/362/453/471 行）均做了归属校验，`pageUser` 第 644-653 行按 userId 过滤，唯独 detail 漏掉。
- 风险：任意登录用户遍历 aftersaleNo 即可读取他人售后单（含订单号、商品、退款金额、买家信息、举证内容）。
- 修复建议：`detail` 增加 `userId/merchantId` 参数，按 `AftersaleOrder.userId` 或 `merchantId` 归属校验（参照订单域 `OrderQueryServiceImpl.detail()` 第 33-41 行的买家或商户二选一模型）。

#### H-3 JWT 密钥为仓库内置默认值，9 个配置点全部是同一个公开字符串

- 位置：
  - `shop-gateway/src/main/resources/application.yml:62`
  - `shop-framework/.../security/JwtService.java:24`（默认值）、`shop-gateway/.../filter/JwtAuthGlobalFilter.java:52`（默认值）
  - 7 个业务服务 `src/main/resources/application.yml:43` 全部硬编码 `shop-trade-system-jwt-secret-key-please-change-in-prod-0123456789`
- 风险：密钥随代码泄露即可离线伪造任意 uid/utype/mid 的合法 JWT，使 C-2/H-1 之外再增加一条完全绕过登录的身份伪造路径；网关与服务密钥相同，一旦泄露全链路失守。
- 修复建议：生产强制从环境变量/Nacos 加密配置/KMS 注入（`${SHOP_JWT_SECRET}` 无默认值，启动缺失即 fail-fast）；网关验签密钥与内部 Feign 信任体系分离；轮换密钥并使存量 token 失效。

#### H-4 网关白名单基于未归一化路径匹配，存在路径遍历绕过

- 位置：`shop-gateway/.../filter/JwtAuthGlobalFilter.java:59`（`request.getURI().getPath()` 原始路径）、`:37-46`（AntPathMatcher 白名单）、`:101-103`
- 实测证据（AntPathMatcher 6.1.6，对同一原始路径 PathPattern 路由同样命中）：
  - `/api/user/auth/../orders/x` → 白名单 `/api/user/auth/**` 匹配 **true**
  - `/api/user/auth/%2e%2e/orders/x`（编码）→ **true**
  - `/api/user/auth/..;/orders/x`（分号+遍历）→ **true**
  - `/api/marketing/promotions/../../inner/marketing/lock` → 白名单 `/api/marketing/promotions/**` 匹配 **true**
  - `/api/user//auth/../orders/x` → **true**
  - `//api/...` 双前导斜杠不命中路由（此形式安全）；纯分号 `/api/user/auth;a=b/x` 不匹配白名单（此形式安全）
- 原理：过滤器不做 normalize/解码，AntPathMatcher 把 `..` 当普通段，白名单按"前缀目录"放行；而下游 Spring MVC 在路由后会解码并归一化路径，实际处理的是遍历后的受保护资源。此外 `/api/product/**` 整条白名单本身就包含内部路径 `/api/product/inner/**`（无需遍历，与 C-1 叠加为未授权）。
- 风险：白名单边界可被 `..`、URL 编码、`..;` 等形态穿越，访问本应鉴权的用户/营销接口；与 C-1 组合可未授权触达内部端点。
- 修复建议：过滤器改用与下游一致的 `PathPatternParser`，先 `UrlPathHelper` 解码+`normalize()` 再匹配；显式拒绝含 `..`、`;`、反斜杠、连续斜杠的请求；白名单收敛到具体文件级路径（如 `/api/user/auth/login`、`/api/user/auth/register`），禁止 `/api/product/**` 这种整域放行（商品详情改为显式的 `/api/product/products/**` 等公开段）。

### 中（Medium）

#### M-1 敏感数据脱敏不完整，银行卡/渠道账号明文存储与返回（违反 design 10.3）

- 位置/证据：
  - 仅 `shop-user-service/.../profile/util/UserPrivacyUtils.java`（手机 3/4 脱敏）且仅在 `/users/me` 一处使用：`UserQueryServiceImpl.java:39`；`UserDTO.phone`（`shop-api/.../user/dto/UserDTO.java:39`）本身无 `@JsonSerialize` 脱敏注解，`InnerUserController.getUser` 返回未脱敏手机号；
  - 订单收货人手机号明文：`shop-order-service/.../support/OrderAssembler.java:48`（`ReceiverDTO.phone`），内部接口 `InnerOrderController` 原样返回；
  - 提现收款账号/姓名/银行明文落库并原样返回：`shop-settlement-service/.../withdraw/entity/SettWithdraw.java:26`（`channelAccount`）、`MerchantWithdrawController.java:41-47`（分页直接返回实体）、`SettWithdrawAutoConfig.channelAccount`；
  - 全仓未发现身份证字段（design 要求身份证脱敏——无对应业务字段，记为信息项）；密码未见入日志，BCrypt 正确（`AuthServiceImpl.java:59,83` hutool `BCrypt.hashpw/checkpw`）。
- 风险：内部接口一旦被突破（见 C-1）即批量泄露手机号、地址、银行卡号；商户列表接口直接暴露收款账号。
- 修复建议：在 `UserDTO.phone`、`ReceiverDTO.phone`、`channelAccount` 上用 `@JsonSerialize` 统一脱敏序列化器（按调用场景区分内部 Feign 全量视图与 C 端/商户端脱敏视图）；银行卡号按 design 10.3 加密存储、仅留后 4 位；内部 DTO 与对外 VO 分离。

#### M-2 防刷/限流基本缺失（违反 design 10.3"防刷单"）

- 位置/证据：
  - `shop-framework/pom.xml:54` 引入 Sentinel，但 7 个服务配置均为 `eager: false`（各 `application.yml:24-27`），全仓 **无一处 `@SentinelResource`、无 flow 规则/datasource 配置**；
  - 登录 `POST /api/user/auth/login` 匿名开放，无验证码、无失败次数锁定、无 IP 频控（`AuthController.java:34-38`、`AuthServiceImpl.login`）；
  - 领券：`CouponService.claim` 的 `@Idempotent` 默认 24h 窗口 key=`userId:couponId`，只挡"同一人同券"，对不同券/注册小号刷券无频率限制；
  - 秒杀：Lua 原子扣减实现正确（`SeckillStockClient.java:26-33` 单脚本 GET+DECRBY，DB 条件更新兜底，`SeckillService.java:72-86`），但无单用户限购/排队频控（per-user limit 未在秒杀链路看到）；
  - 提现：有金额/日累计/保证金业务校验（`WithdrawService.java:69-124`），无提交频控。
- 修复建议：登录加图形/滑块验证码 + 账号/IP 失败锁定与 Sentinel 热点参数限流；领券/秒杀/提现/下单按 userId+IP 配置 QPS 与日累计阈值；秒杀活动表增加 perUserLimit 并在 Lua 中同时校验用户已购集合。

#### M-3 幂等键设计缺陷（可被构造成业务互斥/拒绝服务）

- 位置：
  - `shop-settlement-service/.../withdraw/controller/MerchantWithdrawController.java:33`：`key = "#request.channelAccount + ':' + #request.amountFen"`，不含 merchantId/业务流水号；
  - `shop-settlement-service/.../deposit/controller/MerchantDepositController.java:45`：`key = "#request.amountFen"`，仅金额；
  - `IdempotentAspect.java:54-58` SET NX EX 实现本身正确，失败回删允许重试。
- 风险：同一商户 24h 内两笔"同账号同金额"的正常提现被误拒；不同商户使用相同收款账号+相同金额时互相挤占幂等键；保证金缴纳仅按金额互斥，两个商户等额缴保证金必有一方被拒。键空间也不带用户身份，无法防跨用户构造。
- 修复建议：幂等键加入服务端身份与服务端生成的业务维度（如 `merchantId + ':' + withdrawNo/clientToken`）；客户端提交随请求的 `clientToken`，服务端校验其与登录用户绑定。

#### M-4 Swagger/OpenAPI 与 prometheus 指标在网关匿名放行

- 位置：
  - `JwtAuthGlobalFilter.java:42-44` 白名单含 `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`；
  - `shop-framework/pom.xml:104-105` 全服务引入 `springdoc-openapi-starter-webmvc-ui`；各服务 `application.yml:61-62` 配置 swagger-ui 路径；
  - 各服务 actuator 暴露 `health,prometheus,info`（各 `application.yml:49-53`）。
- 风险：生产环境外网可抓取全部接口结构（参数/路径，降低攻击成本）与 prometheus 业务指标（QPS、金额标签、错误率）；`/actuator/**` 是整前缀放行，将来新增暴露端点会自动对外。
- 修复建议：Swagger 用 Spring Profile（dev/test only）关闭，网关白名单删除 swagger/api-docs；actuator 白名单收敛为 `/actuator/health/**`，prometheus 仅允许内网监控网段（K8s NetworkPolicy / 独立管理端口）。

#### M-5 支付渠道签名密钥硬编码在源码中

- 位置：`shop-pay-service/.../channel/ChannelLimits.java:45-51`（6 个 `mock_*_secret_2026` 明文常量 Map）
- 现状：验签算法本身正确（`SignVerifier.java:28-38` HMAC-SHA256 + `MessageDigest.isEqual` 常量时间比较；`PaymentServiceImpl.handleNotify` 第 246-318 行先验签、再 notifyId 幂等表、再金额与状态校验，顺序正确）。
- 风险：mock 密钥公开，任何人可按 `SignVerifier.sign` 的公开拼接规则（TreeMap 排序字段）自行计算合法签名，伪造支付成功回调 → 不付款完成订单。接真实渠道时若沿用此模式同样危险。
- 修复建议：渠道密钥一律走 KMS/配置中心加密（环境变量注入，`ChannelLimits.secret()` 改为从密钥源读取）；生产不得启用 MOCK 渠道；建议对回调来源 IP/域名加白。

#### M-6 链路全程未配置 TLS（design 10.3"支付链路全程 SSL 加密"未落地）

- 位置：`deploy/kubernetes/00-namespace-config.yaml:84-93`（网关 Service 仅 80→8080，无 Ingress/证书/TLS）；全仓部署文件无 https/证书配置；JWT、身份头、支付报文在入口为明文 HTTP。
- 修复建议：Ingress/网关统一 HTTPS（证书集中管理、HTTP 强制跳转、HSTS），服务间 mTLS；支付回调仅接受 HTTPS。

### 低（Low）

#### L-1 JWT 无吊销机制且有效期固定 7 天

- 位置：`JwtService.java:25`（`ttl-hours:168`）。用户冻结/注销、密钥轮换后已签发 token 在有效期内仍可用（网关只验签+过期）。建议：缩短 access token 有效期 + refresh token；高风险操作实时校验账户状态；维护 jti 黑名单/版本号。

#### L-2 异常/信息泄露细节

- 支付单不存在等错误把内部单号回显（`PaymentServiceImpl` "支付单不存在: "+payNo）、全局异常对未知异常统一 500 不泄露堆栈（`GlobalExceptionHandler.java:56-60`，此项通过）；建议统一错误响应不回显内部标识。

#### L-3 `X-Trace-Id` 未在网关剥离

- 位置：`JwtAuthGlobalFilter.java:62-67` 剥离 4 个身份头，但 `SecurityHeaders.TRACE_ID`（`SecurityHeaders.java:16`）未清洗，外部可伪造链路 ID 污染日志追踪。风险低，建议一并清洗后由网关注入。

#### L-4 依赖版本基线偏旧

- 位置：根 `pom.xml:34-44`（Spring Boot 3.2.5 / Spring Cloud 2023.0.1 / mybatis-plus 3.5.6 / hutool 5.8.27 / RocketMQ client 5.0.3 / jjwt 0.12.6）。未引入 fastjson（全仓 pom grep 无 fastjson），未使用 log4j2-core（Spring Boot 默认 Logback，无 Log4Shell 面）；Jackson 未开启 default typing（`JsonUtils.java:17-20`、`JacksonConfig.java` 无多态配置），**反序列化多态风险通过**。建议升级到 3.2.x 最新补丁（修复 3.2.5 之后披露的 URL 解析/SpEL 类 CVE）并建立依赖扫描。

#### L-5 数据库口令明文且为弱口令

- 位置：7 个服务 `application.yml:11`（`password: root`）。作为本地默认可接受，但必须确保生产由环境变量/密管覆盖且禁止 root 直连（最小权限账号）。

### 信息（通过项）

| 检查项 | 结论 / 证据 |
|---|---|
| SQL 注入 | **通过**。全仓无 MyBatis XML，全部为注解 SQL，且均使用 `#{}`（如 `NotifyLogMapper.java:15-24`、`AftersaleOrderMapper`）；无 `${}` 拼接。LambdaWrapper 唯一的 `.apply("joined_count < required_people")`（`GroupbuyService.java:64`）无外部输入；各处 `.last("LIMIT " + 常量)`（`PayTimeoutScanJob.java:40` 等）拼接的是类内 int 常量；无用户可控 ORDER BY 字段。 |
| 密码存储 | **通过**。BCrypt 加盐哈希（`AuthServiceImpl.java:59`），登录 `BCrypt.checkpw`（:83），未见密码出现在日志。 |
| 网关身份头清洗 | **基本通过**。`JwtAuthGlobalFilter.java:61-67` 在任何放行/鉴权决策前无条件移除外部 `X-User-Id/X-User-Name/X-User-Type/X-Merchant-Id`，仅验签通过后才由 claims 注入；`X-User-Name` 做了 URL 编码防头注入（:80）。残留问题见 L-3（Trace-Id）与 H-3/H-4（密钥与归一化抵消了清洗价值）。 |
| JWT 过期/伪造/篡改处理 | **通过（密钥问题除外）**。无 token 或验签/过期异常对非白名单路径一律 401（:90-97），jjwt 0.12.6 强制 HMAC 验签。 |
| 订单/地址/购物车/发票水平越权 | **通过**。`OrderQueryServiceImpl.detail:33-41`（买家或本店商户二选一）、`OrderOperateServiceImpl`（:69-70,111-112,144,236,255,272,285 逐操作比对）、`MerchantOrderController.java:48-54`（强制 merchantId）、地址 `AddressServiceImpl.requireOwned:75-81`、下单地址归属 `RegionDeliveryChecker.java:20-23`、购物车全部查询带 userId（`CartServiceImpl`）、发票 `InvoiceServiceImpl.java:44-45`。 |
| 商品/结算商户越权 | **通过**。商品域 `AuthUtils.checkOwner:42-47` 在 `SpuServiceImpl` 8 处写操作调用；结算域 `WebIdentity.requireMerchantId/requirePlatformAdmin` 在所有商户/平台 Controller 调用，且 SQL 均按 merchantId 过滤；售后商户操作 `AftersaleServiceImpl.requireMerchant:905-909` 到位。 |
| 支付回调安全（验签/金额/幂等） | **算法与流程通过，密钥管理见 M-5、伪造面见 C-3**。先验签（`PaymentServiceImpl.java:250-255`）→ notifyId 落表幂等（:257-267）→ 金额严格相等校验（:289-294）→ 状态机 + 条件更新防并发（:312-323），重复成功回调幂等 ACK。 |
| 秒杀原子性 | **通过**。Redis Lua 单脚本完成检查+扣减（`SeckillStockClient.java:26-33`），DB 条件更新双保险并回补（`SeckillService.java:72-86`）；缺单用户限购见 M-2。 |
| MQ 消息可信性 | **可接受（内部信任模型）**。事件为内部 topic（`MqTopics`），消费端以 eventId+业务号做幂等（`PayEventConsumer.java:46`）。无消息签名在纯内网+broker ACL 前提下可接受；生产建议：RocketMQ ACL/Topic 授权、生产者身份校验、资金类消费者与 DB 订单状态/金额交叉校验（支付回调侧已做），防止 broker 被入侵后伪造 `PaymentSucceededEvent`。 |
| Actuator 危险端点 | **通过（暴露面见 M-4）**。8 个应用均仅暴露 `health,prometheus,info`，未暴露 env/beans/heapdump/logfile 等。 |
| `@Anonymous` 使用 | 大部分恰当（注册/登录、商品浏览、评价列表、品牌类目树）；**误用/危险点两处**：内部接口的 `@Anonymous` 与网关暴露叠加（C-1）；`PayInnerController`/`InnerMarketingController` 该有内部认证却只靠登录态（C-1）。 |

---

## 二、必查清单逐条结论

| # | 检查项 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | 网关 JWT 过滤器（绕过/伪造/头剥离） | **不通过**：身份头剥离与 401 处理正确；但路径未归一化，`..`/`%2e%2e`/`..;` 实测可穿越白名单（H-4）；默认密钥（H-3）使 token 可伪造 | `JwtAuthGlobalFilter.java:37-46,59-97` |
| 2 | 横向越权（订单/地址/流水/售后/商户） | **部分不通过**：订单/地址/账户流水/购物车/发票/商户商品结算均有归属校验（信息节）；**售后详情无校验**（H-2）；支付单/退款单查询无归属校验（C-3） | `AftersaleController.java:93-96`、`PayController.java:32-40` |
| 3 | 垂直越权（@Anonymous 与 /inner） | **不通过**：`/inner/**` 经网关全部可达且鉴权形同虚设（C-1）；营销/支付对账/售后仲裁等平台接口无 userType=2 校验（H-1）；商户自助注册（C-2） | 见 C-1/H-1 |
| 4 | SQL 注入 | **通过**：无 `${}`、无 XML、无用户可控排序/表名 | 全仓 grep + mapper 注解抽查 |
| 5 | 敏感数据/密码/JWT 密钥 | **部分通过**：BCrypt 通过；脱敏仅手机号一处且银行卡明文（M-1）；JWT 默认密钥全链路硬编码（H-3）；DB 弱口令（L-5） | 见 M-1/H-3 |
| 6 | 支付安全（验签/金额/幂等/幂等键） | **不通过**：验签与回调流程正确，但密钥硬编码（M-5）、对外支付/退款信任客户端（C-3）、退款对外暴露且无 @Idempotent、提现/保证金幂等键缺身份维度（M-3） | `RefundController.java:25-28`、`MerchantWithdrawController.java:33` |
| 7 | 防刷/限流/秒杀原子性 | **部分通过**：Lua 原子性正确；Sentinel 空转，登录/领券/秒杀/提现无频控（M-2） | 各 `application.yml:24-27` |
| 8 | 反序列化/MQ | **通过（带生产建议）**：无 fastjson、无 Jackson 多态；MQ 无签名在 broker ACL 前提下可接受 | `JsonUtils.java:17-20`、`pom.xml` |
| 9 | Actuator/Swagger | **部分通过**：危险端点未暴露；但 swagger 全量上线、actuator 整前缀经网关匿名放行（M-4） | `JwtAuthGlobalFilter.java:42-44` |
| 10 | 依赖风险 | **通过（建议升级）**：无 fastjson、无 log4j2-core；Spring Boot 3.2.5 建议升到 3.2 最新补丁（L-4） | 根 `pom.xml:34-44` |

---

## 三、可上线阻断项（Release Blockers，必须全部清零）

1. **C-1 收口 `/inner/**`**：网关对规范化后的 `/inner/**` 直接拒绝；内部接口加服务间认证（mTLS/内部 token）；K8s NetworkPolicy 限制仅集群内访问。
2. **C-2 关闭自助商户注册**：注册接口禁止客户端提交 `userType=1/merchantId`，商户账号只走平台审核入驻。
3. **C-3 支付/退款接口鉴权与金额服务端化**：支付创建按 orderNo 校验订单归属与金额；`/refunds` 收口为内部接口；支付/退款查询加归属过滤。
4. **H-1 补齐平台端垂直越权校验**：`/platform/recon`、`/platform/aftersales` 仲裁、marketing 三个 `/admin` Controller、`/coupons/issue` 强制 userType=2 或改为内部接口。
5. **H-2 售后详情补归属校验**。
6. **H-3 JWT 密钥外置并轮换**：移除全部硬编码默认值，启动时强制环境变量/密管注入，缺失即 fail-fast。
7. **H-4 白名单归一化**：网关统一解码+normalize 后匹配，拒绝 `..`/`;`/编码穿越，收敛 `/api/product/**` 等整域白名单。
8. **M-5 支付渠道密钥外置**：生产禁用 MOCK 渠道与硬编码 secret。
9. **M-4 生产关闭 Swagger、收敛 actuator 暴露**。
10. **M-1 银行卡/渠道账号加密存储与脱敏返回**（满足 design 10.3）。
11. **M-2 登录防刷（验证码/锁定/频控）与领券/秒杀/提现限流规则落地**（满足 design 10.3 防刷单）。
12. **M-6 入口与支付链路 TLS 落地**（满足 design 10.3 SSL 要求）。

> 阻断项 1-7 为可直接导致未授权资金操作/身份伪造的高危项，在当前形态下系统不具备上线条件；8-12 为合规与防护基线，应与 1-7 同批整改后复测。
