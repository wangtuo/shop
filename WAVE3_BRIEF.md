# Wave 3 验收与加固任务清单（代理 #15–#21）

> 前置：Wave-2 七个服务实现全部合入、`mvn clean package -DskipTests` 全绿。
> 基础设施：docker compose（shop-infra）9 容器运行中；本地 K8s：`kubectl --context kind-kind`。
> 铁律：禁止修改 shop-common/shop-api/shop-framework 及其他服务模块源码；发现契约缺口
>       统一汇总给协调者集中修复。每个任务结束给出：修改文件清单、验证证据（命令+输出摘要）、遗留问题。

## #15 E2E 跨域场景测试（shop-e2e 模块）
- 位置：shop-e2e/src/test，JUnit5 + Spring Boot Test + RestTemplate/WebClient。
- 默认 Profile：`e2e`，指向本地网关 http://localhost:8080；中间件直连 docker compose。
- 必须覆盖主链路（design 全模块联动）：
  1. 平台/商户/用户注册登录（含 JWT、伪造身份头被网关剥离 → 401）
  2. 商户建类目/SPU/SKU → 审核上架 → 商品详情缓存
  3. 营销：建券/发券/秒杀活动/拼团/预售；六层试算与最大余数分摊金额守恒断言
  4. 购物车增删改（99 上限、失效勾选）→ 下单（普通/秒杀/拼团/预售/换货5类型）
  5. 订单号规则 18 位（YYMMDD+业务2位+userId 末4+6位序列）断言
  6. 支付：7 渠道 mock 回调、错误签名拒绝、重复回调幂等、混合支付退款按比例
  7. 支付成功事件扇出：库存实扣、积分发放、成长值、清算登记（轮询断言最终一致）
  8. 发货→收货（超时自动收）→完成→评价；发票开具
  9. 售后五类：仅退/退货退款/换货/补发/价保；退款瀑布（待结算→保证金）；运费险
  10. 清算：S/A/B/C 周期、提现费用（前3免/0.1%/最低2元）、保证金限制
  11. 异常：超卖（条件更新）、并发同券、库存预警、MQ 消费幂等（重复投递事件结果不变）
- 数据准备：每个场景自建数据（唯一 clientToken/用户名），可重复执行。

## #16 活库 DDL 校验
- 将 sql/*/V2__*.sql 在运行中的 shop-mysql 对应 schema 全部执行（先备份现有库）。
- 校验：外键/唯一索引/逻辑删除/乐观锁字段/金额 bigint 分/状态码 tinyint/注释完整。
- 反向校验实体字段 ↔ 列一一对应；mq_consume 幂等表每库存在。
- 输出 DDL_REVIEW.md：问题清单（按严重度），只改 sql/ 目录，不改服务代码。

## #17 全量启动 + 网关路由 + K8s 高可用
- 用 deploy/local/start-apps.sh 起 8 个进程，全部 /actuator/health UP、Nacos 注册数=1。
- 网关白/鉴权路由逐条 curl 验证；OpenFeign 内部链路至少跑通一个跨服务调用。
- K8s（kind-kind context）：`kubectl --context kind-kind apply -f deploy/kubernetes/`；
  校验 2 副本、podAntiAffinity、PDB、HPA、Service；删除一个 Pod 服务不中断（循环 curl）。
- 输出 SMOKE_REPORT.md。不得 apply 到非 kind 集群。

## #18 安全审计
- 网关：白名单绕过、身份头伪造（X-User-Id 直连网关/直连服务端口两种路径）、JWT 篡改/过期。
- 服务：越权（改 userId 访问他人订单/地址/账户）、SQL 注入（MyBatis ${} 扫描）、
  敏感字段脱敏（手机号/身份证/银行卡）、日志脱敏、BCrypt 强度、支付验签、
  @Idempotent 覆盖写接口、管理/商户接口鉴权。
- 输出 SECURITY_REVIEW.md（漏洞/证据/修复建议）；仅审计不改码，修复由协调者分派。

## #19 性能基线
- 校准 deploy/loadtest/seed.sh（按真实 API 调整），docker run grafana/k6 跑 smoke 档。
- 给出：单实例与水平扩展下单 TPS、P95/P99、回调耗时、详情耗时、订单列表耗时；
  Redis 原子扣库存正确性（并发 N=2×库存，成功数=库存，无超卖）。
- 输出 PERF_REPORT.md + 调优建议（连接池/线程池/索引/缓存）。

## #20 编译与代码质量加固
- 全 reactor `mvn clean verify`；修复编译/测试 flaky；统一 Checkstyle 级问题（包结构、@Mapper、Result 包装、Long 分）。
- 关键并发代码审查：条件更新、分布式锁、幂等、TCC 补偿空回滚/悬挂。
- 输出 CODE_REVIEW.md。

## #21 需求逐条验收
- 按 ACCEPTANCE.md 92 项逐条用证据（API 调用/DB 记录/日志/截图路径）勾选；
- 差距项开单（模块/条款/缺口/建议负责人），更新 ACCEPTANCE.md。
