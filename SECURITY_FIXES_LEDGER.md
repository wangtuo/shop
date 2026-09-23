# 安全阻断项整改闭环台账（SECURITY_REVIEW.md → 修复证据）

- 建立日期：2026-09-16
- 对照：SECURITY_REVIEW.md《三、可上线阻断项》12 项
- 整改链路：SECURITY_FIXES_B.md（agent B）、FIXES_D/E/F.md（P1 整改三代理）、DDL_REVIEW.md、本台账新增项
- 验收方式：代码证据 + 单测 + 网关路径实测 + kind prod-profile 启动 fail-fast 实测（SMOKE_REPORT.md）

| # | 阻断项 | 状态 | 修复位置与证据 |
|---|---|---|---|
| 1 | C-1 `/inner/**` 收口 + 服务间认证 + NetworkPolicy | ✅ | 网关 `JwtAuthGlobalFilter`：路径四次解码+归一化后按段识别 inner 直接 404（C-1/H-4 同一实现），外部 `X-Internal-Token` 无条件剥离；测试 `InnerPathDetectionTest`（5 类，含 %25 双重编码/`..;`/反斜杠/矩阵参数）。下游 `InternalTokenInterceptor` 对 `/inner/**` 强制 X-Internal-Token（`InternalTokenInterceptorTest`），Feign 侧 `FeignRequestInterceptor` 注入。网络层 `deploy/kubernetes/30-networkpolicy.yaml`（业务 Pod 仅网关+同命名空间；网关仅 ingress-nginx）。冒烟 smoke.sh §3 五变体 404。 |
| 2 | C-2 关闭自助商户注册 | ✅ | `AuthServiceImpl.register`：userType=1 直接 PARAM_INVALID「商户账号请通过平台入驻流程开通」，其余非 0 类型拒绝；服务端强制 `user.userType=0, merchantId=null`。商户账号唯一入口：平台 `POST /api/user/users/admin/accounts/merchant`（userType=2）+ 清算域 `/api/settlement/admin/merchants` 入驻。 |
| 3 | C-3 支付/退款鉴权与金额服务端化 | ✅ | `PayController.create`：userId 强制取 `WebIdentity.requireUser()` 登录身份，body userId 被覆盖；金额由支付域按订单反查（validateInternalRequest/订单域命令口径）。退款 C 端创建入口已删除（`RefundController` 仅查询/重试，注释声明唯一触发路径为售后域 `/inner/pay/refund`）；查询 `getByRefundNoForViewer` 按归属过滤；重试仅 `requirePlatformAdmin()`。平台对账 4 个端点全部 `requirePlatformAdmin()`（userType=2）。另见 FIXES_G.md（终态支付单重新支付，多尝试槽位）。 |
| 4 | H-1 平台端 userType=2 校验 | ✅ | pay `ReconcileController` 4/4 端点、aftersale `PlatformAftersaleController:29` + `AftersaleServiceImpl.arbitrate:538`、marketing `ActivityAdminController`/`CouponAdminController`/`PromoAdminController` 写操作全部 `WebIdentity.requirePlatformAdmin()`；`/coupons/issue` 收口为 `/inner/marketing/coupon/issue`（`CouponIssueRemovedTest`、`InnerCouponIssueTest`）。商品/结算域审计时已正确（AuthUtils/WebIdentity）。 |
| 5 | H-2 售后详情归属校验 | ✅ | `AftersaleController.detail` 传入 `WebIdentity.requireUser()`；`AftersaleServiceImpl.detail(no, viewer)` 买家或归属商户二选一（与订单详情同模型）。 |
| 6 | H-3 JWT 密钥外置 fail-fast | ✅ | 7 服务 yml 全部 `${SHOP_JWT_SECRET:dev-...}`；`ShopSecretEnvironmentValidator` prod profile 下空值/内置值拒绝启动（单测）；网关同构 `GatewaySecretValidator`。k8s Secret 注入（00/10 清单 secretKeyRef），生产清单值为 CHANGE_ME 占位且 fail-fast 拦截。HMAC 全链路统一密钥（内部 Feign 另有独立 internal-token，已分离）。 |
| 7 | H-4 白名单归一化 + 收敛 | ✅ | `JwtAuthGlobalFilter.canonicalPath`：重复 URL 解码（≤4 次，覆盖双重编码）→ 反斜杠转正斜杠 → 去 `;` 矩阵参数 → 解析 `.`/`..`，再匹配；白名单由整域改为文件级（login/register/bootstrap-admin、products/brands/categories/comments 段、coupons/center、actuator/health/**）；`/api/product/inner/**` 不再可能被白名单覆盖。 |
| 8 | M-5 渠道密钥外置，prod 禁 mock | ✅ | `ChannelSecretProvider`：6 渠道密钥配置化（env `SHOP_PAY_CHANNEL_MOCK_*_SECRET`）；prod 下启用 mock 或任一密钥=内置值 fail-fast（7 单测）。kind HA 验收唯一豁免路径：双开关 `SHOP_PAY_MOCK_CHANNELS_ENABLED=true` + `SHOP_PAY_ALLOW_MOCK_UNDER_PROD=true` **且**六密钥全部外置非内置（`ChannelSecretProviderTest` 两例：豁免但默认密钥仍 fail-fast；豁免且外置才通过）。生产清单 `SHOP_PAY_MOCK_CHANNELS_ENABLED=false`。 |
| 9 | M-4 生产关 Swagger、收敛 actuator | ✅ | 7 服务 + 网关均有 prod profile 段 `springdoc.api-docs/swagger-ui.enabled=false`；actuator 仅暴露 health,prometheus,info；网关白名单仅 `/actuator/health/**`，swagger/api-docs/prometheus 不经网关放行（prometheus 靠 NetworkPolicy 限集群内）。 |
| 10 | M-1 敏感字段加密/脱敏 | ✅ | 结算：`DataCipher` AES-256-GCM `enc:v1:`（IV12‖密文‖TAG16），提现/自动提现 4 列加密落库（V3__data_encryption.sql 扩 VARCHAR(512)，已应用活库），对外全换 `WithdrawVO/AutoWithdrawConfigVO` 脱敏（`AccountMask` 留尾 4），prod 默认密钥 fail-fast（DataCipherTest 10 例）。手机：`PhoneMaskingSerializer` 挂在 UserDTO.phone、OrderDTO/ReceiverDTO.phone（`PhoneMaskingSerializerTest`），内部 Feign 全量视图与对外 VO 分离。历史明文行可读（无前缀原样返回），**存量明文→密文迁移任务为遗留项**。 |
| 11 | M-2 防刷/限流 | ✅（端点层） | 登录：`LoginLockService` 用户名+IP 双维度 5 失败锁 15 分钟（Lua 原子计数滑动续期）。接口频控：框架新增 Redis ZSET 滑动窗口 `@RateLimit`（`RateLimiter` Lua + `RateLimitAspect`，9 单测；活 Redis 验证：放行/拒绝/窗口滑出恢复）。落地点：领券 30 次/分钟（用户维度）、下单 5 次/秒、提现 5 次/分钟、秒杀锁定 1 次/3 秒 + P2-7 `uk(activity_id,user_id,deleted)` 每活动每人一单（`V3__seckill_user_uk.sql`）。幂等键 M-3 同步修复：提现/保证金均为「服务端 merchantId + clientToken/业务单号」（WithdrawService:80、DepositService:72）。**遗留**：Sentinel 仍为基础设施层（dashboard 已接，无全局 QPS 流控规则）；验证码未接入（锁定+频控已显著抬高批量撞库成本）。 |
| 12 | M-6 TLS | ✅ | `20-tls-ingress.yaml`：Ingress TLS（shop.example.com）、80→443 强制跳转（308）、HSTS 1 年含子域 preload、cert-manager 注解（生产自动签发）；自签验收 `tls/gen-self-signed.sh`；ha-check.sh §5 断言 308/301。服务间在 K8s 内网 + NetworkPolicy + X-Internal-Token（mTLS 为后续网格演进项）。 |

## 低风险项处置
- L-1 JWT 无吊销：当前 7 天 TTL 维持，**遗留**（refresh token/短 access token 演进项，高风险操作已有实时状态校验：登录冻结拦截、支付/提现身份校验）。
- L-2 内部单号回显：pay 已去除对外 message 中 payNo/orderNo 后缀（SECURITY_FIXES_B.md L-2）。
- L-3 X-Trace-Id：网关注入前已剥离（JwtAuthGlobalFilter headers.remove 含 TRACE_ID）。
- L-4 依赖基线：Spring Boot 3.2.5 无 fastjson/log4j2-core/Jackson default-typing；补丁升级列遗留。
- L-5 DB 弱口令：仅本地 root/root；生产清单 `shop_app` + Secret 注入，最小权限账号由 DBA 授予。

## 遗留项汇总（不阻断功能验收，纳入上线前运维清单）
1. 提现收款账号历史明文行一次性加密迁移（DataCipher 已兼容读取）。
2. Sentinel 全局 QPS 流控规则接入（dashboard 已部署；端点频控已由 Redis @RateLimit 覆盖关键路径）。
3. 登录图形/滑块验证码（当前失败锁定 + IP 频控）。
4. JWT refresh token / jti 吊销机制。
5. 服务间 mTLS（当前 NetworkPolicy + X-Internal-Token 双层）。
6. Spring Boot 3.2.x 补丁版本升级与依赖扫描流水线。
