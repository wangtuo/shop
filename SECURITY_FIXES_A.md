# 安全修复清单 A（lane：framework / user / order / product / gateway / 公共配置）

- 修复日期：2026-09-16
- 覆盖项：C-2、H-3、C-1、M-4、M-2、M-1（手机号脱敏部分）、H-4 复核、sentinel eager
- 未触碰：shop-pay / settlement / aftersale / marketing 的任何 Java 文件（仅按授权修改其 application.yml）

## 1. C-2 关闭自助商户注册（shop-user-service）

**改动文件**
- `shop-user-service/.../auth/service/impl/AuthServiceImpl.java`：注册链路不再信任请求体。
  `userType=1`（商户）直接抛 `BizException(PARAM_INVALID, "商户账号请通过平台入驻流程开通")`；
  `userType=2` 等非 0 值拒绝；userType 缺省/为 0 时一律强制落库为消费者（`setUserType(0)`、`setMerchantId(null)`），
  即使请求体携带 merchantId 也被忽略。校验发生在查重/落库之前。
- `.../auth/dto/RegisterRequest.java`：字段保留（兼容旧请求体），注释标明客户端不可信。
- `.../auth/service/AuthService.java` / `.../auth/controller/AuthController.java`：login 增加 clientIp 入参（X-Forwarded-For 首段 → X-Real-IP → remoteAddr）。
- 平台/商户账号的服务端创建链路未在本服务发现自助入口，未做改动（商户账号走入驻/管理链路的既有行为保留）。

**测试**（`AuthServiceImplTest`，改写而非删除）
- 匿名注册携带 userType=1+merchantId=888 → 业务异常且不查库不落库；
- userType=0/缺省但携带 merchantId → 落库消费者、merchantId 为 null；
- 平台账号、手机号冲突等原有用例保留。

## 2. H-3 JWT 密钥外置 + prod fail-fast

**改动文件**
- `shop-framework/.../security/JwtService.java`：@Value 默认值改为 `dev-local-only-jwt-secret-key-0123456789abcdef`。
- 新增 `shop-framework/.../security/ShopSecurityProperties.java`（`shop.security.require-env-secrets`，默认 false）。
- 新增 `shop-framework/.../security/ShopSecretEnvironmentValidator.java`（InitializingBean）：
  激活 `prod` profile 或开关打开时，JWT secret 与 internal token 任一为空/空白/等于内置默认值，即抛 IllegalStateException 使 ApplicationContext 启动失败；dev 默认 profile 允许内置值单机启动。
- 网关为 webflux 不依赖 framework，新增同构 `shop-gateway/.../config/GatewaySecretValidator.java`（只校验 JWT）；
  `JwtAuthGlobalFilter` 的 @Value 默认值同步替换（未改过滤器任何鉴权逻辑）。
- 8 个 application.yml：`shop.jwt.secret: ${SHOP_JWT_SECRET:dev-local-only-jwt-secret-key-0123456789abcdef}`。
- `deploy/local/start-apps.sh`：显式 `export SHOP_JWT_SECRET`（同时 export SHOP_INTERNAL_TOKEN）。
- `deploy/kubernetes/kind/00-kind-infra.yaml`：确认/补齐 Secret——jwt-secret 已存在；新增 `internal-token` 与 `data-enc-key` 键（10-services.yaml 的 7 个 Deployment 已以 secretKeyRef 显式引用 SHOP_JWT_SECRET/SHOP_INTERNAL_TOKEN，kind 层只需让 Secret 键齐备，未改 10-services.yaml）。

**测试**：`ShopSecretEnvironmentValidatorTest`（6 例：prod 默认值/空值 fail-fast、外部注入放行、dev 默认值放行、开关生效）。

## 3. C-1 纵深防御：服务间 X-Internal-Token（framework 自动生效）

**改动文件**
- 新增 `shop-framework/.../web/InternalTokenInterceptor.java`：仅拦截 `/inner/**`（相对 context-path，全项目无 server.servlet.context-path），
  头 `X-Internal-Token` 等于 `shop.internal.token` 才放行，否则 401。
- `shop-framework/.../web/WebMvcConfig.java`：internal 拦截器 order(0) 且仅挂 `/inner/**`；AuthInterceptor order(1) 挂 `/**`，
  原有 @Anonymous 放行语义与排除路径完全保留（internal 先于 auth 执行）。
- `shop-framework/.../feign/FeignRequestInterceptor.java`：新增第二个 Feign RequestInterceptor，
  对所有 Feign 请求注入 `X-Internal-Token=${shop.internal.token:dev-local-only-internal-token}`；
  该类经 `@ShopService` 的 @Import 装配，7 个业务服务自动生效，无需业务代码改动。
- 7 个业务服务 application.yml 增加 `shop.internal.token: ${SHOP_INTERNAL_TOKEN:dev-local-only-internal-token}`，并纳入第 2 点 prod fail-fast。
- 网关侧确认：`JwtAuthGlobalFilter` 已无条件 `headers.remove(INTERNAL_TOKEN)`，外部无法伪造；/inner 段归一化后直接 404（H-4 已完成，未改其逻辑）。

**测试**：`InternalTokenInterceptorTest`（4 例：无 token/错 token/空白 token → 401，正确 token 放行）。

**关键决策**：7 个业务服务（含 pay/marketing 等无 lane 服务）零 Java 改动即获得防护——它们都依赖 shop-framework 且经 @ShopService 装配。

## 4. H-4 商品公开路径与网关白名单一致性（只复核，未改网关）

商品服务公开浏览实际文件级前缀（@Anonymous）：
- `/products/**`（GoodsController：列表、/{spuId}、/{spuId}/skus、/skus/{skuId}/price）
- `/brands/**`、`/categories/**`（tree）、`/comments/products/{spuId}`（评价列表）

与网关新白名单 `/api/product/products/**, /brands/**, /categories/**, /comments/**` 一致，**不存在 /spus/ 等其他文件级前缀**。
管理/商户端为 `/admin/products`、`/merchant/products`，不在白名单。
遗留观察（非本次 lane）：白名单 `/comments/**` 在路径层也覆盖了评价的 POST 写接口，但这些方法未标 @Anonymous，
服务端 AuthInterceptor 会因缺 X-User-Id 返回 401，故实际不可匿名写；如需收敛可由网关 owner 将白名单细化为 `/comments/products/**`。

## 5. M-4 Swagger 生产关闭

8 个应用（7 服务 + 网关）application.yml 末尾以多文档段（`---` + `spring.config.activate.on-profile: prod`）统一配置：
`springdoc.api-docs.enabled=false`、`springdoc.swagger-ui.enabled=false`。默认/dev profile 下文档与 swagger-ui 保持可用；
actuator 暴露面（health/prometheus/info）未改动。8 个 yml 均通过 YAML 多文档解析校验。

## 6. M-2 登录防刷 + sentinel eager

**改动文件**
- 新增 `shop-user-service/.../auth/security/LoginLockService.java`：
  用户名 key `auth:login:fail:user:{account}` + IP key `auth:login:fail:ip:{ip}` 双维度；
  阈值 5 次、锁定 15 分钟；计数 INCR 与 PEXPIRE 在单条 Lua 脚本内原子完成（每次失败滑动续期，避免 INCR 后宕机造成无 TTL 永久锁）；
  锁定判定也是单条 Lua；成功登录 delete 两 key；IP 缺失时只走用户名维度。
- `AuthServiceImpl.login`：进入先 `assertNotLocked`（锁定中直接 TOO_MANY_REQUESTS，不校验密码）；
  账号不存在/密码错 `recordFailure`；冻结/注销在密码正确后判定（不计失败）；成功 `clearLock`。
- `AuthController` 解析客户端 IP 透传 service。
- 7 个业务服务 yml：`spring.cloud.sentinel.eager: true`（仅配置行）。

**测试**：`LoginLockServiceTest`（5 例：未锁放行、达阈值 429 且双 key 正确、失败原子脚本 TTL=900000ms、IP 空单维度、成功删双 key）；
`AuthServiceImplTest.login_连续5次失败后_第6次即使密码正确也被锁定拒绝`、密码错误记一次失败、成功清零等。

## 7. M-1 手机号脱敏 + 内部 Feign 明文视图

**关键决策**：脱敏序列化器必须放在 shop-common——被脱敏的 UserDTO/ReceiverDTO 在 shop-api，而 shop-api 只依赖 shop-common（不依赖 framework）。
- 新增 `shop-common/.../jackson/PhoneMaskingSerializer.java`：前 3 后 4（`13812345678→138****5678`），短号/空原样；
  实现 ContextualSerializer，当活动 Jackson 视图为 Internal 时退化为明文输出。
- 新增 `shop-common/.../jackson/JsonViews.java`（Internal 视图标记）。
- `shop-api`：`UserDTO.phone`、`ReceiverDTO.phone`、`OrderDTO.userPhone` 标注
  `@JsonView(Internal.class) @JsonSerialize(using = PhoneMaskingSerializer.class)`。
  无视图的外部序列化（网关 C 端流量）一律脱敏；视图仅影响标了 @JsonView 的字段，DTO 其余字段在 Internal 视图下正常输出。
- 内部明文通道：`InnerUserController.getUser` 与 `InnerOrderController.getByOrderNo` 方法级 `@JsonView(Internal.class)`，
  Feign 回查可拿到完整手机号/收货电话；Feign 反序列化不受视图影响。
- `UserQueryServiceImpl` 不再手动 mask（改由序列化层按视图决定），删除未用 import；`UserPrivacyUtils` 保留未删。
- 外部出口（UserProfileController.me、OrderController 详情/分页等）无视图，返回自动脱敏。

**测试**：`PhoneMaskingSerializerTest`（framework，3 例：默认视图脱敏、Internal 视图文、短号/null）；
order 既有 `OrderAssemblerTest` 直接断言 POJO getter 不受影响（154 测试全绿）。

## 8. 验证结果

- `mvn -pl shop-common,shop-framework,shop-api install -DskipTests`：成功。
- 单测：shop-framework 13、shop-user-service 106、shop-order-service 154、shop-product-service 148、shop-gateway 5，全部 0 失败 0 错误。
- `mvn -DskipTests package`（lane 7 模块）：成功；4 个可执行应用均产出 spring-boot repackage fat jar
  （shop-gateway.jar ~48MB，三个业务服务 ~111MB，均有 .original 伴生文件）。

## 遗留风险 / 需上游知晓

1. JWT 密钥网关与服务仍共用同一密钥（按本次要求），内部信任与外部信任的分离由 X-Internal-Token 承担；后续可拆分验签密钥。
2. 登录 IP 维度信任 X-Forwarded-For 首段——前提是入口网关覆盖/重写该头；网关目前会透传客户端给的 XFF（Spring Cloud Gateway 默认追加），
   建议后续在网关固定 XFF 链。IP 维度是用户名维度之外的叠加项，不影响账号维度锁定的有效性。
3. `/api/product/comments/**` 白名单粒度偏宽（见第 4 点），服务端鉴权兜住了写操作，建议网关侧细化。
4. AddressDTO.phone（收货地址簿）未在本次 M-1 范围内，当前为明文返回（外部地址查询接口本身有登录+归属控制）；如需一并脱敏可后续在 shop-api 同模式加注解，但要同步评估 InnerUserController.getAddress 的内部明文需求（需再给该方法加 Internal 视图）。
5. 生产部署需在 Secret 注入非默认强随机 SHOP_JWT_SECRET/SHOP_INTERNAL_TOKEN，否则 prod profile 启动即 fail-fast（预期行为）。
