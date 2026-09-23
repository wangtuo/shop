# GAP_PLAN_MASTER — 缺口修复总控（四份域规划 → 统一波次）

> 本文件是缺口实施波的唯一总控：维护审计项覆盖矩阵、契约/DDL/编号映射、波次与子代理编排。
> 业务实施代理**不得自行修改 shop-api / shop-common / shop-framework**；所有横切变更先在对应域规划 §2 声明，由 W0 契约波统一落地。
>
> 域规划：
> - GAP_PLAN_FUNDS.md（资金：B8/B9/B10/B11、MQ P0-1/P2-1/P2-4/P2-5、韧性 R-B6、契约卡 API-F，共 10 卡）
> - GAP_PLAN_PLATFORM.md（平台/框架/韧性：R-B1–B5 + Z1–Z8/Z11 + MQ P2-2/P2-3/P3-x + 网关限流与包裹体 + API-P + K8S-B；契约 C12–C19）
> - GAP_PLAN_OBSERVABILITY.md（可观测：O1 traceId/MDC、O2 告警、O3 看板、O4 网关 metrics、O5 审计留痕、业务指标；契约 C50–C59）
> - GAP_PLAN_MARKETING.md（营销：B1–B5 营销玩法/审核/砍价抽奖/秒杀/预售侧、P1-2、API-M）
> - GAP_PLAN_TRADE.md（订单/商品域：B1 订单半卡、B5 库存半卡、B7 运费、B13 类目规格/发货扣减、P1-1、API-T）
> - GAP_PLAN_USER.md（用户域：B6 新人礼包/成长值积分、R-B7 签到事务、API-U）

## 1. 审计项覆盖矩阵（防漏）

| 来源 | 项 | 主规划 | 备注 |
|---|---|---|---|
| FEATURES §10.2 | B1 拼团事件消费/续期 | MARKETING + TRADE | 玩法/事件归 MKT，订单流转（退款/转待发货/续期）归 TRADE |
| FEATURES | B2 营销三类后台审核流 + 秒杀到点结束 | MARKETING | |
| FEATURES | B3 砍价/抽奖 | MARKETING | |
| FEATURES | B4 SECKILL_EVENT 消费/锁跳过/对账自愈/限购 | MARKETING（+TRADE 商品库存边界） | P1-2 同卡族 |
| FEATURES | B5 预售计价 + 支付后扣库存 | MARKETING + TRADE | 膨胀计价 MKT，库存状态机 PRODUCT |
| FEATURES | B6 新人礼包/评价晒单分享成长值积分 | USER | 事件生产方在 TRADE（评价）/营销（发券），订阅方在 USER，C40+ |
| FEATURES | B7 运费模板/区域运费服务端定价 | TRADE | 与 FUNDS B11 保费边界对接 |
| FEATURES | B8 真实渠道骨架/退款回调/查询/状态机 | FUNDS | 与 P2-5 同批共用收敛漏斗 |
| FEATURES | B9 技服费守恒 | FUNDS | 已证伪，仅回归测试卡 |
| FEATURES | B10 保证金/打款/罚款/90 天纠纷 | FUNDS | C1/C2/C8 + settlement V5 |
| FEATURES | B11 运费险购买侧 | FUNDS | C4–C9 + order V3/aftersale V3/settlement V5 |
| FEATURES | B12 网关限流 + 统一包裹体 | PLATFORM | PERF_REPORT §3 雪崩实证驱动 |
| FEATURES | B13 虚拟类目/规格主数据/发货扣减库存 | TRADE | |
| MQ | P0-1 穿仓消费者/工单/扫表/追缴 | FUNDS | topic 与 cg_sett_shortfall 已预建，勘误不重建 |
| MQ | P1-1 普通库存对账 | TRADE | product 服务新建 Job |
| MQ | P1-2 秒杀对账自愈 | MARKETING | |
| MQ | P2-1 售后超时消息 eventId/消费流水 | FUNDS | shop-aftersale-service |
| MQ | P2-2 null eventId 框架统一兜底 | PLATFORM | |
| MQ | P2-3 9 个孤儿 topic 巡检/废弃/外部方声明 | PLATFORM | 外部消费方清单进 ACCEPTANCE 残留 |
| MQ | P2-4 对账逐差异 REQUIRES_NEW | FUNDS | |
| MQ | P2-5 退款 HTTP 出事务三段式 | FUNDS | 与 B8 共用 RefundConvergeService |
| MQ | P3-1 消费事务内 Feign 模式 | PLATFORM（模式）/ FUNDS（settlement 执行） | |
| MQ | P3-2 DLQ 告警/重放台面 | PLATFORM | 与韧性 Z4 合卡 |
| MQ | P3-3 Outbox DDL 自检/迁移 | PLATFORM | |
| RESILIENCE | B1 Druid 有界连接池 | PLATFORM | |
| RESILIENCE | B2 Feign 超时/熔断/连接池/ErrorDecoder | PLATFORM | 框架级空体解码支撑 R-B6 |
| RESILIENCE | B3 网关/Sentinel 限流事实为零 | PLATFORM | 与 B12 同卡 |
| RESILIENCE | B4 Redis 故障 fail-open/closed 策略 | PLATFORM | chaos 流量覆盖补齐 |
| RESILIENCE | B5 支付/退款/关单大事务 | PLATFORM（模式）+ FUNDS（逐点） | P2-5 为退款侧执行 |
| RESILIENCE | B6 售后 null Result 当成功 | FUNDS | |
| RESILIENCE | B7 签到自调用事务失效 | USER | 顺带清查 user 服务（原 TRADE 已拆出） |
| RESILIENCE | Z1 裸 @Transactional 模式 | PLATFORM / FUNDS 执行 | |
| RESILIENCE | Z2 root/root 硬编码 | PLATFORM | env/Secret 注入 + prod fail-fast |
| RESILIENCE | Z3 consumeMaxAttempts 死配置 | PLATFORM | |
| RESILIENCE | Z4 %DLQ% 无消费/重放/告警 | PLATFORM | 并 P3-2 |
| RESILIENCE | Z5 outbox biz_key UK | PLATFORM | shop-common DDL |
| RESILIENCE | Z6 调度器单线程 | PLATFORM | |
| RESILIENCE | Z7 ShedLock 前缀隔离 | PLATFORM | |
| RESILIENCE | Z8 探针分组 | PLATFORM | |
| RESILIENCE | Z9 AftersaleTimeoutListener 无 eventId | FUNDS | 即 P2-1 |
| RESILIENCE | Z10 关键指标告警闭环 | PLATFORM | 并可观测告警卡 |
| RESILIENCE | Z11 Nacos 不可用行为验证 | PLATFORM | |
| OBSERVABILITY §7 | 阻断 1 链路追踪（traceId/MDC） | PLATFORM | |
| OBSERVABILITY §7 | 阻断 2 告警规则 | PLATFORM | |
| OBSERVABILITY §7 | 阻断 3 看板四连修 | PLATFORM | |
| OBSERVABILITY §7 | 阻断 4 网关 prometheus registry 404 | PLATFORM | |
| OBSERVABILITY §7 | 阻断 5 管理后台审计留痕 | PLATFORM | |
| API 契约 | 网关裸 500 非业务包裹体 | PLATFORM | 雪崩证据链：exit137→Connect refused→裸 500 |
| API_CONTRACT §7.3 | 重要 1/2/3 售后：举证 IDOR、责任方自报、items @Valid | FUNDS（卡 API-F） | aftersale |
| API_CONTRACT | 重要 4/5/6/7 营销：admin GET 鉴权、三保存 DTO 校验、changeStatus 状态机、管理分页、领券 @Valid | MARKETING（卡 API-M） | |
| API_CONTRACT | 重要 8 渠道回调 @Valid + 非法终端 10001 + 对账入参 | FUNDS（卡 API-F） | pay/recon |
| API_CONTRACT | 重要 9 GlobalExceptionHandler 两类 400 + E-1/E-4 | PLATFORM（卡 API-P） | BizException 保持 200+body.code 契约不变 |
| API_CONTRACT | 建议：批量 @Size/orderType 白名单/URL scheme/@Validated/请求体上限/403 口径 | TRADE（API-T）+ PLATFORM（API-P 全局项）+ FUNDS（API-F 售后串） | shop-api DTO 注解随 W0 |
| K8S §A | 托管中间件、真实 registry | 残留（不做假实现） | ACCEPTANCE 残留声明：云托管中间件 + registry.example.com |
| K8S §B | B2/B3/B4/B7/B8/B9/B10 清单硬化 | PLATFORM（卡 K8S-B） | kind 可验证项必须过 KIND_DEPLOY+ha-check；useSSL/securityContext 受限项列残留 |
| SECURITY H-3 | JWT 密钥 prod 强制注入复核 | PLATFORM（随 Z2 密钥卡） | kind Secret 已隔离，prod fail-fast 复核 |

## 2. 编号映射（防冲突）

| 段 | 占用 |
|---|---|
| 契约 C1–C11 | FUNDS（payScene、保费事件字段、AftersaleClient、内部命令注解 C11 等） |
| 契约 C12–C19 | PLATFORM（原 C11 段顺延；以平台规划 §2 实际编号为准并回填本表） |
| 契约 C20–C29 | MARKETING |
| 契约 C30–C39 | TRADE（order/product） |
| 契约 C40–C49 | USER（成长/评价事件、发券对接） |
| 契约 C50–C59 | OBSERVABILITY（MDC/traceId、审计 AOP、metrics 配置） |
| FlowChangeTypes | 16=INSURANCE_PREMIUM_INCOME、43=DEPOSIT_FINE_INCOME（FUNDS 已分配）；其余各规划在 §2 续编登记到本表 |
| 新 topic（W0 落 create-topics.sh） | USER 规划已定：`shop_user_registered`（:17 后追加；消费组 cg_marketing_user_registered 追加 :37，营销侧消费发新人券）、`shop_comment_created`（同追加；生产方=TRADE 评价服务，消费组 cg_user_comment_created 追加 :34）；FUNDS：cg_sett_deposit_pay 追加 :39（topic 复用 ORDER_PAID） |
| DDL | pay V5、settlement V5、order V3、aftersale V3 = FUNDS；order V4+ = TRADE；product/user 续编以 sql/ 现状为准 |
| DDL 现状基线（2026-09-17 核实） | common V3/V5（outbox）；user V2→V3（USER：t_user_share_log）；product V2→V3（TRADE 运费模板）→V4（TRADE 类目规格/库存）；marketing V2/V3/V4→V5；order V2→FUNDS V3（TRADE V4 编号空置，无 order DDL）；pay V2/V4→V5；settlement V2/V3/V4→V5；aftersale V2→V3。全部 information_schema 守卫、无 Flyway |
| FlowChangeTypes 现状 | settlement 服务内枚举（非 shop-api）：已用 10-15、20-23、30-34、40-42；FUNDS 分配 16=INSURANCE_PREMIUM_INCOME、43=DEPOSIT_FINE_INCOME；44+ 待各规划登记 |
| topic/消费组 | cg_sett_deposit_pay 新增（FUNDS B10，create-topics.sh :39 追加，topic 复用 ORDER_PAID）；REFUND_SHORTFALL/cg_sett_shortfall 已存在不重建 |

### 2.1 W0 交叉审校裁决（2026-09-17，五规划对表后生效，优先级高于各域规划原文）

1. **CommentCreatedEvent 唯一形态以 USER C42 为准**：`String eventId、Long commentId、String orderNo（注意 USER 原文误写 Long，orderNo 全系统为 String）、Long userId、Long spuId、Long skuId（可空，吸收 MARKETING C22-②）、Integer behaviorType（1评价 2晒单）、Boolean withImage、Long eventTime（epoch ms）`。MARKETING C22-② 的重复定义（commentNo/showOrder/withImages 命名）**作废**，不得另建类；TRADE C-COMMENT 按本形态生产。
2. **UserRegisteredEvent 唯一形态以 USER C41 为准**（userId/registerTime epoch ms/userType，无手机号）；MARKETING C22-① 的 `registeredAt LocalDateTime` 版作废，仅引用 C41。
3. **ShareEvent / topic `shop_share_action` 取消**（MARKETING C22-③ 作废）：分享无后端事件，走 USER C44 `POST /api/user/users/shares` 同步链路。
4. **order DDL 编号**：MARKETING D3（`V3__order_groupbuy_presale.sql`，group_succeed/renew_count/parent_order_no）**整段作废**——TRADE B1 用既有 group_no+status+expire_time 条件更新，无需新列；预售父子单用 activityId+userId 反查 + product V4 stock_log.ref_order_no 关联。`sql/order/` 仅 FUNDS D3 一个 V3（`V3__order_freight_insurance.sql`）；TRADE V4 继续空置。
5. **product DDL 编号**：MARKETING D2（`V3__product_presale_recon.sql`）作废并入 TRADE：product V3=TRADE 运费模板两表；product V4=TRADE 类目规格/库存（含 t_product_stock_reconcile_log，issue_type 含 3=预售支付后未扣，P1-1/P1-2 普通+预售对账共用）。营销库内 `t_stock_reconcile_log`（scope 1秒杀Redis/DB、2预占回补）保留在 marketing V5。
6. **团长价字段命名**：试算**响应**统一 `leaderPriceFen`（TRADE C37）+ `leaderFlag`；试算**请求/CalcItem 快照**用 MARKETING C21 的 `activityPriceFen`（砍价成交价快照同字段复用）+ leaderFlag/groupNo/groupbuyActivityId；W0 落地时两个方向各自只加一次。
7. **create-topics.sh W0 最终追加清单**：topic 仅 2 个——`shop_user_registered`、`shop_comment_created`（:17 shop_points_changed 行后）；消费组 7 个——`cg_user_comment_created`（:34 user 行尾）、`cg_marketing_user_registered`、`cg_marketing_groupbuy`、`cg_marketing_seckill`（均在 :37 marketing 行尾，与既有组同风格续行）、`cg_order_groupbuy`、`cg_product_shipped`、`cg_sett_deposit_pay`（GROUPS 末尾续行，各卡实施前必须重跑脚本）。
8. **拼团双消费组并存**：shop_groupbuy_event 由 `cg_marketing_groupbuy`（营销半卡：团长价/营销资源）与 `cg_order_groupbuy`（TRADE 半卡：续期/关单/退款编排）各自独立消费，均以 groupNo#type+eventId 双幂等。
9. TRADE 规划中出现的 C40/C42/C47 为对 USER 段的跨域引用（C-COMMENT 生产半卡），非重复占用。
10. **拼团失败已支付退款**：TRADE 仅发起一次 `PayClient.refund`（refundNo 幂等），退款单/状态机/三段式全部归 FUNDS（P2-5+B8 RefundConvergeService），订单侧禁止自建退款表。

## 3. 波次与子代理编排（≥20 实施子代理）

- **W0 契约波（1 代理，串行闸门）**：shop-api/shop-common 四份规划 §2 全部变更一次合入 + FlowChangeTypes/MqTopics/create-topics.sh。完成后 mvn -pl shop-api,shop-common,shop-framework install。
- **W0.5 DDL 波（1 代理）**：四份规划全部 SQL 落库脚本（information_schema 守卫幂等），宿主 MySQL 执行验证；kind 重建时由初始化链路携带。
- **W1 框架韧性波（4 代理并行，shop-framework + 网关 + 部署）**：①Feign 超时/熔断/ErrorDecoder/空体解码（B2/P3-1 模式/Z1 模式）②Druid 有界 + 调度器 + ShedLock + 探针（B1/Z6/Z7/Z8）③Redis fail 策略 + 网关限流 + 统一包裹体（B3/B4/B12/API 契约）④MQ 平台侧（P2-2/P2-3/Z3/Z4+P3-2/Z5/P3-3）+ 密钥 env 化（Z2）。
- **W2 资金高危波（2 代理，同评审）**：P2-5+B8 共用 RefundConvergeService（1 代理串行做两者）；P2-4 独立（1 代理）。
- **W3 资金业务波（4 代理）**：B11 保费 / B10 保证金+Remit / P0-1 穿仓工单（自动补扣钩子在 B10 后联调）/ P2-1+R-B6 aftersale 双卡。
- **W4 营销波（4 代理）**：B2 审核流 / B1 拼团 / B3 砍价抽奖 / B4+P1-2 秒杀消费与自愈 / B5 营销侧（按卡数并）。
- **W5 交易/用户波（6 代理）**：TRADE：B1 订单侧 / B5+B13 商品库存与类目规格 / B7 运费 / P1-1+API-T；USER：B6 成长体系 / R-B7+API-U。
- **W6 可观测波（2 代理）**：traceId/MDC/审计留痕（若未随 W1）+ 告警/看板/prometheus/业务指标 + Z11 Nacos 故障演练。
- **W7 验收波**：final-acceptance.sh 十二阶段全门（单测/E2E/chaos/HA/k6 20TPS/超卖），韧性整改后重跑拐点扫描更新 PERF_REPORT §3；ACCEPTANCE 勾选、CODE_REVIEW 定稿、残留清单（含 P2-3 孤儿 topic 外部方、真实渠道、运费定价数据源、保证金收款账户等）。

每卡代理交付 = 生产代码 + 单测 + 必要 E2E 增补 + 本模块 `mvn test` 绿；严禁改其他服务源码与 shop-api/common/framework（W0 已封板）。

## 4. 波次准入/准出

- **W0 契约波：✅ 已绿（2026-09-17）**。12 新类 + shop-api/common 改点，11 模块编译通过，common 单测 4/4。落地偏差：C23 建单请求类在 order 服务内（CreateOrderRequest.parentOrderNo 留 W5）；C33/C32 实际路径 `/inner/order/orders/status`、`/inner/product/freight/calc`；C36 用 @Pattern+@Size 常量形态（common 无 validation 依赖）；C38 refOrderNo 实体留 W5；ErrorCode 新增 DEPENDENCY_TIMEOUT=10010（10008/10009 既有）。create-topics.sh 已改未执行（2 topic/7 group）。
- **W0.5 DDL 波：✅ 已绿（2026-09-17）**。9 文件：user V3、product V3/V4、marketing V5、order V3（仅 FUNDS 运费险）、pay V5、settlement V5、aftersale V3、common V6；7 库两遍幂等。V6 定稿 **UK(topic,tag,biz_key)**（同 topic+bizKey 跨 tag 合法多行，窄索引证伪）。
- **部署链路缺口（DEPLOY-1）：✅ 已绿（2026-09-17）**。新增 `deploy/apply-sql.sh`（七库全量版本唯一入口：t_shop_schema_history 账本/checksum 漂移拒绝/BASELINE 模式；common V3/V5 随各库执行、V6 自带 7 库循环整体执行一次；mysql stdin 吞噬管道与 shasum→sha256sum 两个坑已修）+ `deploy/mysql/10-apply-sql.sh`（镜像 initdb 钩子，SQL_DIR=/opt/shop-sql socket+密码）；compose 移除 7 个裸 V2 挂载统一收敛；final-acceptance MIDDLEWARE 阶段挂接（03-apply-sql.log）。宿主库已 BASELINE 全账；**一次性 MySQL 容器 fresh-init 实测**：37 条账本全 exec 0 错、五张 product 新表/shortfall 工单/七库 uk_topic_tag_bizkey 齐、二次运行全 skip。
- **波次进度（2026-09-17）**：W2-1 ✅（退款三段式+6 渠道骨架+退款回调+RefundQueryJob，pay 119/119）；W3 售后 P2-1+R-B6 ✅（93）+B11 售后侧+C8 ✅（aftersale 105/105）；W5-USER ✅（B6 全卡+W1-D user 残留清理，136/136）；W4-1 ✅（B2+API-M+B6 消费，63 例）；W4-3 ✅（砍价抽奖 37 例）。W4-2/W4-4 ✅、W3-S3 ✅（保费清算守恒：SplitEngine 恒等式/RefundCalculator 剔保费/executor 流水分录 16 全额不退/scene=4 守卫，43 例 javac 待总控 mvn）；**marketing 总控集成 mvn test 257/257 全绿（四代理合流点 ActivityRule/ActivityMapper 收敛验证通过，2026-09-17）**。TRADE-1 ✅（拼团订单流转+B11 订单侧运费/B10 scene 守卫+API-T 白名单，order 209/209 mvn 全绿；双通路幂等 gbflow:{groupNo}:{3|4}、SUCCESS 续期 1800s CAS 只宽不窄、refundNo=GB:{orderNo} 单次；代理报 create-topics 缺失已核实证伪——W0 第 18/50 行齐全）；W2-2 ✅（P2-4 对账逐差异 REQUIRES_NEW+批次状态推导+短款渠道 SPI 不伪平账，叠加 payScene=4 支付侧，pay 142/142 mvn 全绿，**pay 模块封板**）；**TRADE-1 偏差④已由总控修复**：拼团失败 FULL 退款到达时订单仍在 20 态，closeAfterAftersale 谓词仅匹配 60/61/62 导致无法关单——OrderMapper 谓词扩为 20/60/61/62（正常售后必先转 60+，20+FULL+无 aftersaleNo 唯此编排；30/40 刻意不纳入）+ 消费层新增拼团关单测试，order 总控 mvn **210/210 全绿**。W3-S2 ✅（P0-1 穿仓工单 10→20→30/三层幂等/RESIDUAL-扫表跨页/补扣 DP20+流水31 LEAST 重放零扣，17 例 javac；clawbackOnDepositPaid 钩子已在 S1 在途代码 DepositPaySettlementService:108 合流）；**product 总控集成 mvn 224/224 全绿（TRADE-2/3/4 三代理封板）**：预售四仓状态机 0→1→4→3/1→5、定金 presale-=occ+ 与尾款 ref_order_no、发货 1→4 出账、类目规格主数据、运费内部端点、COMMENT_CREATED 同事务 outbox、StockReconcileJob 全部合流；修复 1 处 TRADE-2 自测严格桩不一致（事件助手硬编码 ORDER_NO 与 DEPOSIT_NO 桩冲突，内联事件修正）。W6-2 ✅（19 条告警规则 promtool check/test 全绿+AM v0.27.0 log-only 占位+compose 挂载+Grafana 7→16 面板 jq 合法；rule_files 显式文件名避开 test 文件被严格解析；**总控实环境复验**：compose 拉起 shop-alertmanager 后 AM /-/healthy OK、prom 4 group/19 rule 加载、自动发现 1 个 active AM、Grafana /api/health up；8 服务 ShopServiceDown/InstancesLow pending 系当前业务副本未运行所致（语义正确，W7 HOSTAPPS/KIND_DEPLOY 后清除；本地单副本恒 <2 已在 description 标注 silence）；`app` 标签由 static scrape config 每 target 注入，K8s 抓取栈属环境残留）。运行中：W3-S1（settlement，S2/S3 已绿待三包总控 mvn）、W6-1（framework/gateway 观测框架件）。
- **settlement 总控集成 mvn 185/185 全绿（W3-S1/S2/S3 三代理封板，2026-09-17）**：B10 保证金缴费三段式（DP 10→20→30）/退还（10→20 置零+30+41，失败保留余额告警）/罚款 43/清退 90 天纠纷校验/remit SPI mock+real/RemitQueryJob/提现 WD 20→30→40 状态机，clawbackOnDepositPaid 三包符号合流，B11 保费守恒 scene=4 守卫全部收敛。**S1 提示残留**：deposit_log 退还失败原因暂并入 remark(≤250)，结构化 remit_fail_reason/query_count 列如需补须走**新 V7 迁移**（V5 已入账 37 条 checksum，禁止改 V5），列入 W7 残留决策；remit 配置代码均有默认值（mock-enabled 默认 true），prod 真实渠道密钥 SHOP_SETTLE_REMIT_BANK_SECRET/ALIPAY_SECRET 缺失 fail-fast，K8s ConfigMap/Secret 注入属 §A 环境残留。
- **W6-1 ✅（2026-09-17）**：framework 121（基线 106+15）/gateway 22（19+3）mvn test install 全绿。TraceMdcFilter/GatewayTraceGlobalFilter/Feign 透传/8 份 logback pattern/micrometer-tracing-bridge-otel 1.2.5（BOM 管版本无 exporter，采样率 env TRACING_SAMPLE_RATE 默认 1.0）/AuditLog+Aspect（logger=AUDIT）/BizMetrics+DruidBinder+OutboxSampler+Scheduler AOP/gateway prometheus registry/histogram 默认属性源；无高基数标签。风险已登记：@Async 线程池无 requestId（不串号）、/actuator/prometheus 整上下文 W7 起服务验证。
- **O5/O6 扫尾波（3 代理并行，2026-09-17 派出→全绿）**：SW-1 marketing+product（10 写端点 @AuditLog：4 admin controller+AdminGoods，action 偏差为 upsert 单端点 _SAVE/_STATUS_CHANGE 与聚合审核 MARKETING_AUDIT+captureArgs；shop_stock_alert_total{level=WARNING/SOLD_OUT/RESTOCK}）；SW-2 settlement+pay+user（14 写端点：动款/等级/罚款/对账/退款 inner/账号开通，shop_pay_total/_failed_total/_seconds、shop_refund_total{result,operator_type}、shop_deposit_insufficient_total{event=FINE/CLAWBACK/RESIGN}；登记缺失端点：提现人工审批、账号禁用/角色调整 HTTP 端点不存在）；SW-3 order+aftersale（**仲裁 4 处 operatorId=0L→WebIdentity.requirePlatformAdminUser() 真实 ID，仅系统超时流转保留 ROLE_SYSTEM/0L**；4 售后管理端点审计；shop_order_created_total{result,channel}+_seconds；order 无管理端点）。
- **缺口修复波总控封板（2026-09-17）**：全 reactor `mvn clean test` BUILD SUCCESS，13 模块 0 失败：common 4 / framework 121 / gateway 22 / user 137 / product 227 / marketing 259 / order 214 / pay 149 / settlement 190 / aftersale 110（E2E 54 例编译通过，待 -Dshop.e2e=true 黑盒跑）。代码波（W0→W6+扫尾）全部收口，进入 W7 同一套最终产物验收。
- **Broker 就绪（2026-09-17 总控执行）**：create-topics.sh -r 16 -q 1 已在运行中 broker 重放，16 消费组全 [OK]、2 新 topic 幂等生效；DLQ 历史遗留 6 条（cg_sett_refund 1/cg_order_refund 1/cg_product_aftersale 4，前序 chaos/E2E 窗口产生），脚本不自动重放——**W7 最终验收证据采集前须经 admin endpoint 人工处置/清空后再跑同一套产物**。W6 观测波已派出：框架侧（O1/O4/O5 框架件/O6 框架侧/O7）+ 部署侧（O2/O3）；@AuditLog 端点铺设与业务指标埋点待业务波收口后扫尾。
- **W1 框架韧性波：✅ 已绿（2026-09-17）**。4 代理：A Feign HC5 池+Resilience4j 熔断舱壁+ShopErrorDecoder/ResultDecoder（框架永不返回 null）；B Druid 分档有界基线+ThreadPoolTaskScheduler+ShedLock env 前缀+探针分组+事务模板三件套+GlobalExceptionHandler 400 契约；C 网关 Redis 令牌桶（order 20/40 初值、fail-closed）+WebFlux 统一 envelope（429→10007/503→10008/504→10010/5xx→10009，禁泄 IP 堆栈）+@RateLimit 七路径故障矩阵+chaos POST 写链路段+K8S 硬化（HPA/PDB/securityContext/Service/IfNotPresent/TLS1.2/NetworkPolicy audit）；D EventNormalizer 合成 eventId+死键经 create-topics -r 16 -q 1（规划 -d 被证伪为广播参数）+DLQ 巡检/actuator 台面+outbox 启动自检+DB/Redis 口令 env 化+prod 弱口令 fail-fast。framework 106/106、gateway 19/19 全绿；6 业务服务编译兼容（pay 随 W2 验）。
- **W7 最终验收缺口修复波（第四轮，2026-09-18/19/20，✅ 同一套最终产物实证）**：由 final-acceptance.sh 实证失败驱动，24 项（R4-1…R4-23 已闭环；R4-24 已修复并本地实证、同套十二门复跑收口；详见 CODE_REVIEW.md 第四轮）：R4-1 **Feign 子上下文 feign.Client 压制 LB 包装**（自定义 feign.Client 致服务名直 DNS UnknownHost→10008，E2E 33→20 例失败的最大根因；改只暴露 HC5 HttpClient 官方扩展点）；R4-2 10008 归一化保留 cause 链；R4-3 **网关 429 空体**（reactor-netty 吞 decorator 补体，自定义限流工厂在 deny 点直写 10007 包裹体）；R4-4 start/stop-apps 幂等重启+JVM_EXIT 可观测（僵死 JVM KILL -9）；R4-5 chaos 身份/地址/SKU 自助（旧 WSKU 取列表 spuId 永空=假覆盖）；R4-6 E2E 保证金对齐 B10 三段式（发起→mock 回调→轮询入账）；R4-7 验收链 trim/磁盘水位运维件；R4-8 **2xx 业务码被 DecodeException 二次包装**（拆包必须在熔断统计前，否则业务失败误计失败率+兜底 10009，100 次滑窗单测锁死）；R4-9 chaos.sh 执行环境健壮性（locale 无效根因下钻：macOS /bin/bash 3.2 即使 LC_ALL=UTF-8 仍吞全角字节，真修复为 deploy/**/*.sh 11 处 `${VAR}` 花括号定界，locale 仅留给 sed/awk）+ 恢复探针轮询 Redisson 重连窗口；R4-10 **CHAOS 门正则误抄 ha-check 的「HA 结果:」**（chaos.sh 实输出「混沌结果:」，实证 PASS=30 FAIL=0 仍被 die，该门 W7 从未真绿；门串已按脚本锁定并注释）；R4-11 **镜像命名 USER app 与 runAsNonRoot 不兼容**（7 业务 Pod 全 CreateContainerConfigError；Dockerfile 钉死数字 UID/GID=10001 对齐 fsGroup）；R4-12 **Service link 注入 SHOP_GATEWAY_PORT=tcp://... 撞网关端口变量**（gateway NumberFormatException crashloop；podSpec enableServiceLinks:false + 显式 env 8080，并补齐网关同款安全硬化关闭 W1-C 网关残留）；R4-13 **kind Secret 沿用 root/root 被 prod fail-fast 拒绝**（R-Z2 首次在 K8s 实证有效；新增 mysql/20-kind-app-user.sql 幂等建强口令 shop_app 仅授七库，compose initdb + MIDDLEWARE 补执行 + Secret 轮换）；R4-14 **网关 Deployment 漏注入 SPRING_DATA_REDIS_HOST**（容器内落 localhost→限流 fail-closed 全量 429，HA 入口探测 FATAL；补 configMapKeyRef REDIS_HOST 与 7 业务同源；pick_https_port 可达判据改「非 000 即通」+45s 重试，200 硬判定留给流量线程）；R4-15 单节点 HPA 滚动风暴扩出不可调度副本污染资源/PDB 断言（ha-check 单节点分支自动 live-cap HPA 2-2 + 钉 Deployment=2，PERF §7.5 人工操作门内自动化）；R4-16 seed-oversell 把 data 对象整体当 SPU/活动 id（响应已包成 {spuId,warnings}；jq 按类型归一取 .spuId/.activityId 兼容标量/对象）。R4-17 **K6_SMOKE 20TPS 大面积 10008/10010 的两个独立容量根因**：(1) 本地 MySQL 出厂 max_connections=151 被 16 Pod Druid 池（理论 ~400）打满（实测 146 连接、product 191 次 1040）——compose MySQL `--max-connections=600` + ha-check 对 7 业务 live-cap `SHOP_DATASOURCE_TUNING_MAXACTIVE=10`（生产清单分档不改，云托管配额属 K8S §A）；(2) RocketMQ 5.3.1 写门 diskSpaceWarningLevelRatio 源码硬上限 0.90 且有 0.85 迟滞（broker.conf/-D 均不可抬高，已核官方源码），VM 盘 0.92 时 outbox 全量 50001——安全回收（未动 shop 数据卷/在用镜像）压到 0.898 冷启动，broker 关 commitlog 预分配/预热防打穿、fileReservedTime=6，mqadmin SEND_OK 实测；final-acceptance MIDDLEWARE 新增 max_connections 自动放宽与 broker 水位 ≥90% die+回收指引两道容量守卫。R4-18 **K6_SMOKE 冷启动污染 p95 的方法学修复**：R4-17 后成功率已达 99.36%（错误仅集中于 HA 扰动后头 25s），但 p95=2.3s 越阈——k6-order.js smoke 增加 90s 不计阈值的 warmup 场景，阈值以 `{scenario:smoke}` 标签只统计测量窗，门可从冷态复跑。另修 settlement 真实库 IT 非hermetic断言（runDailySettle 全库返回值按 IT 商户号段过滤）。实证处置：broker 磁盘水位 0.92/0.90（含等于）拒写，安全回收 Docker build cache/dangling（不动 volume/在用镜像）降至 0.89 + 重启 broker，outbox 全量排空；切 Wi-Fi 致 Tailscale IP 漂移，final-acceptance KIND_DEPLOY 改为「基础配置→kind 覆盖→按当前 LAN IP patch ConfigMap→再建业务 Pod」。R4-19 **全链 E2E 25 例失败的双重环境根因**：(1) **broker store bind 宿主目录致磁盘采样错位**——bind 时 `df store` 经 virtiofs0 采样的是 Mac 宿主系统盘（460Gi/可用常年 ~45Gi/水位 90–91%），R4-17 的安全回收结构上无法降到 0.85（mqadmin 实测 CODE:14 disk full，宿主 order 日志 27,066 次 50001，TCC/扇出/outbox 全链卡死）；compose store 改 Docker 命名卷 `shop-rmq-data`（落 VM overlay，实测 31.8%、SEND_OK），旧 bind 目录保留不删；新命名卷 root 属主致 uid 3000  broker `store/lock Permission denied` 崩溃循环，entrypoint 改 root chown 后 runuser 降权。(2) **残留 kind 16 Pod 与宿主 HOSTAPPS 共用中间件**——Nacos group 隔离但 MQ 消费组同名共享偷消费、宿主→10.244.x 网络不对称超时；final-acceptance 增两道形态互斥前置：HOSTAPPS 前删 8 HPA+scale 全部 kind 工作负载=0，KIND_DEPLOY 前 stop-apps.sh 停宿主 8 应用。(3) 借空 store 暴露的潜伏缺陷：create-topics.sh 误用 bash 只读保留数组名 `GROUPS`（OS GID 列表）致 34 个真实消费组从未由脚本创建（靠 autoCreateSubscriptionGroup 兜底，-r16-q1 重试/DLQ 参数未生效，生产关 autoCreate 必炸），改名 `MQ_GROUPS` 后 26 topics/34 groups 全建、数字 GID 垃圾组已清。R4-20 **MQ 消费者冷启动宽限**：实证 Nacos 首次服务列表推送在 Started 后 15s 才到，broker 陈旧积压（3,191 条关单消息/200 消费线程）在 PushConsumer 一注册时即打在 JIT/Druid/路由全冷的 marketing 上，Resilience4j 默认滑窗被冷调用打 OPEN 后半开探针反复失败、熔断持续整个 E2E 窗（13 例 10008）；框架修复 `shop.mq.consume-start-delay-millis=20000`，消费者注册推迟到 ApplicationReadyEvent+20s 宽限（三标志幂等门控、健康宽限态 OUT_OF_SERVICE、宽限消息留 broker 由同组在线实例承担），新增 2 条框架单测；同套产物实证 E2E 59 例全绿、宽限后四服务 0 次 CB OPEN、CHAOS 30/30、HA 74/74。另：链在 KIND_DEPLOY 后被 macOS 低内存回收 kill（非验收失败），final-acceptance 增 EVIDENCE_DIR 复用证据目录 + ONLY_STAGES 续跑机制。R4-21 **K6_SMOKE 负载模型缺陷（压测侧，非被测系统）**：constant-arrival-rate 每迭代轮换 uid → ~20 次/s BCrypt(cost=10) 登录风暴 CFS 打满 user-service 2 核，失败迭代不缓存 token + 扩 VU 正反馈雪崩；k6-order.js 改 uid 绑定 VU 生命周期、warm/measure 单场景分段、setup() 受控预登录 160 用户+预建地址、loginToken 退避重试、preAlloc/maxVUs=120/160；四轮实证成功率 0.735→1.0、p95 6524→3987→1725/1167→清洁环境 v6 **p95=161.5ms、0/5400 失败、19.26 TPS**；尾延迟归因证伪容量说（order 服务端 max 0.70s、MySQL Threads_running 2–6 零慢 SQL、GC max 95ms），尾在整集群冷启动重放 + 历史超时取消波扇出。R4-22 **k8s 七服务 JDBC URL 缺 allowPublicKeyRetrieval=true**：Docker VM 被 macOS 内存回收重启、MySQL 丢失 caching_sha2 内存快认证缓存后七服务全 CrashLoopBackOff（Public Key Retrieval is not allowed）；7 条 SPRING_DATASOURCE_URL 无条件追加（TLS 下无副作用），滚动重启后全 Running、HA 74/74。R4-23 **InnerMarketingController/PayInnerController 漏标类级 @Anonymous**：MQ 消费/调度线程（PayTimeoutListener 超时取消、拼团失败退款）经 Feign 调 /inner 只带 X-Internal-Token、无 X-User-Id，被 AuthInterceptor 恒判 401（实证 marketing /inner/marketing/release 560 次全 401 而 lock/calculate 全 200）——超时单营销资源（券/预占）永不释放（资金/库存正确性）+ 401 计入按 client 共享熔断毒化前台下单；两类加 @Anonymous（InternalTokenInterceptor 对 /inner/** 的 X-Internal-Token 强制不变，纵深不降级；@AuditLog 无用户上下文本就降级 SYSTEM/0），另 4 个内部控制器本已标注，6 个 @FeignClient 全量清查无遗漏（settlement 无入站 inner 控制器）；新增 5 个回归断言（两注解存在性 + AuthInterceptorTest 三例：类级匿名放行/无用户头 401/有头放行）；framework/marketing/pay mvn 全绿后同套产物重跑十二门。R4-24 **t_sett_mq_consume 单列 event_id 唯一键致同 topic 多消费组扇出静默吞保证金到账（资金正确性，生产级事故）**：十二门同套复跑 E2E 59 例中 SettlementE2ETest.depositPayAndRecords 1 例失败（保证金余额 20s 未增）。逐层取证排除消费者未注册/业务异常/线程死锁/rocketmq 客户端竞态（5.0.7 源码实证标准 POP 路径 ACK 必经 listener.consume）后定位真因——shop_order_paid 被两个独立消费组 cg_sett_paid（清算，scene=4 登记后直接 ACK）与 cg_sett_deposit_pay（保证金入账）订阅，两组共用 t_sett_mq_consume 而唯一键只有 uk_event_id(event_id)：先落库组使另一组 INSERT IGNORE 静默返回 0，业务分支跳过、消息照常 ACK，无消费流水/无错误日志/broker 不重投（启动追赶期哪组抢跑纯竞态，故稳态隔离复跑可偶发通过、HA 每次滚动发布都可能触发）。修复两道：(1) V7__settlement_r4_24.sql 幂等迁移把唯一键改为 uk_event_group(event_id,consumer_group)（V6 版本号被 common 七库 outbox UK 脚本占用故域脚本用 V7；其余六服务每库同 topic 仅一组，清查无需改）；(2) 新增 DepositPayRecoveryJob 对账兜底（ShedLock 集群单节点/60s 定频/90s 在途宽限/单轮 100 笔）：扫描 pay_no 已回写仍 status=10 的缴费单，以支付域 status=30 为唯一事实源、orderNo 与金额分毫不差才合成确定性 rec-<payNo> 事件驱动原有三重幂等入账事务，非成功降频 touch 复查、不一致 ERROR 挂起人工、RPC/补账异常不 touch 下轮重试，把『静默丢失』变为『最终一致』。实证：新 Job 首轮即自动补做历史 3 笔被静默吞掉的保证金（含本次 E2E 失败单 P…075，余额 1014415→1314415 分毫不差、rec- 幂等行齐、零 ERROR），全新缴费事件两组 mq_consume 行并存（event 2d7cc6cf…），SettlementE2ETest 7/7、全量 E2E 59/0/0/4，DepositPayRecoveryJobTest 7 例 + 既有相关单测全绿；随后同套产物重跑十二门收口。
- **W7 最终验收缺口修复波（第五轮 R4-25，2026-09-20，✅ 同一套最终产物十二门全绿）**：七库 t_mq_outbox 统一 UK `uk_topic_tag_bizkey(topic,tag,biz_key)`（common/V6）下，OutboxPublisher 事务内纯 INSERT、relay 只置 status=1 行永不删除——凡「同一业务单号在一次业务生命周期内多次发出同 topic/tag 事件」，第二次起 DuplicateKeyException 直接回滚整事务（或被 catch 后静默丢失），是一族同族碰撞缺陷。统一修复范式：**bizKey 加业务维度后缀，消息体 bizNo 保持裸业务单号，消费侧按随机 eventId/消息体字段幂等**。五项收口：(1) **积分** AccountServiceImpl 变更事件键 `bizNo#ct{changeType}`（冻结/扣减/退回同单多阶段不撞），到期聚合跨分页事件键额外 `#p{pageCursor}`（BATCH_SIZE=200，同用户同日 200+ 笔 grant 不撞）；(2) **秒杀多 SKU** marketing V9__seckill_multisku.sql 重设计：t_seckill_order 改每 (order_no,sku_id) 一行（uk_order_sku），限购从订单计数迁到新表 t_seckill_user_buy 行锁条件占件（claim: total_qty+?<=limit，首单 insert 撞 UK 负方重试 claim，releaseQty GREATEST 回减），锁定/扣减/释放逐 SKU 落库，事件键 `orderNo#sku{skuId}`；(3) **评价** 同笔订单多评价行为键 `commentNo#b{behaviorType}`；(4) **库存预警** product V7__stock_warning_dedup.sql 加 t_product_sku.low_stock_alerted，StockServiceImpl 边沿闸门 casLowStockAlertOn==1 才 fire、恢复 casOff，事件键 `skuId:warningId`（治同一 SKU 每日 03:00 重扫与多节点竞争重复告警），event.bizNo 仍裸 skuId；(5) **保证金 DEPOSIT_ALERT** settlement V9__settlement_r4_25_deposit_alert_dedup.sql 加 t_sett_deposit_log.hang_alerted，四路（罚款/扣款赔/清退人工挂起/查询失败）复用商户终生裸键改为实例级键 `FINE:{fineLogNo}`/`CLAW:{refundNo}`/`HANG:{log40LogNo}`/`FAIL:{refundLogNo}`（前缀与历史裸键天然隔离无需回填），HANG 走 casHangAlerted 持久 CAS（每笔 log40 仅告警一次，治每日重扫+多节点），DepositAlertEvent 增 alertType/refNo 运营维度，publishAlert 捕获 DuplicateKeyException 仅 error 日志降级（单条 INSERT 失败不回滚资金事务）。另同步修复前四窗口主代码改键后遗留的假绿/未编译测试（积分 3 断言+分页游标新例、拼团续期 #gbrenew 3 断言、预售 #final、售后 publish 6 参 transitionLogId + StatusLog mock 回填 id 严格桩 lenient），新增 PresaleEventListenerTest 5 例（含 body eventId 空白回退 MqConsumeContext.currentEventId() 上下文）。全 reactor 单测 **2530** 全绿（含 SettlementRepairRealDbTest 真实库 5 例，容器 Up 后）；**十二门实证（证据 final-20260920-1800-r425）**：UNIT 2530（真实库 IT 5/5）、E2E 59/0/0/4、CHAOS 30/0、HA 74/0、K6_SMOKE 5400 迭代 0 失败 avg=168.5ms p95=389.3ms（阈值 800ms）、K6_OVERSELL 40 尝试 winners=losers=paid=20 + DB 对账 orders_status20/pay_status30=20/20。详见 CODE_REVIEW.md 第五轮。
- **W7 最终验收缺口修复波（第六轮 R4-26，2026-09-20，✅ 同套十二门全绿，R4-25 全绿后的同族预防收口）**：(1) **售后跨轮乱序（时效正确性）**——R4-25 让同一售后单可两次进入同一状态（拒绝 55 后修改重提回 10、仲裁买家胜诉回 41），旧轮延时消息（audit/receive/exchange_ship）若因 broker 重投/消费积压/停机追赶在第二轮窗口才投递，状态机幂等守卫挡不住（第二轮恰好处于同一守卫状态），会把买家第二轮审核/收货窗口提前最多 2/3 天自动流转甚至系统自动退款。修复：AftersaleTimeoutServiceImpl.dispatchTracked 落库前增加**轮次新鲜度守卫**——按售后单当前 audit/receive/exchangeShip deadline 现算 roundKey（与登记/扫表同一 DATETIME 列），消息 roundKey 与当前不一致即 ACK 丢弃并 warn；当前 deadline 已清空而消息带轮次同样丢弃；insurance/evidence 无轮次维度不校验；R4-25 前登记的历史无 roundKey 存量消息无法判别，维持状态机守卫既有语义放行；订单查不到放行交业务既有异常重试路径。(2) **product 消费 eventId 兜底（防御加固）**——StockServiceImpl.tryRecord 在消息体 eventId 空白时回退 MqConsumeContext.currentEventId()（框架 EventNormalizer 已在 MqConsumerRegistrar 对所有 listener 统一绑定，含 noid 合成），非 MQ 线程且无 eventId fail-fast。活路径 BaseEvent 构造即随机 UUID 不空白，本项消除历史无信封消息的 null 键风险。测试：aftersale 110→114（守卫 4 例：旧轮迟到丢弃/当前轮放行/当前无截止点丢弃/evidence 不校验）、product 新增 2 例（上下文兜底落库、无上下文 fail-fast；模块 232 全绿）；同套产物重跑十二门（证据 final-20260920-1840-r426）✅ 全绿：UNIT 13 模块 BUILD SUCCESS（2536 例 0 failure 0 error）、E2E 59/0/0/4、CHAOS 30/0、HA 74/0、K6_SMOKE 测量窗 0 失败 exit=0（warmup 50 条 10008 冷启动噪声，全量聚合 p95=1865ms 口径越线但非阈值口径，见 PERF §2.0b）、K6_OVERSELL winners=losers=paid=20、DB 20/20。
- **W7 最终验收缺口修复波（第七轮 R4-27，2026-09-20，🔄 同套十二门复跑中，R4-26b 的七域同族预防收口）**：框架 EventNormalizer 已对所有 listener 统一解析 eventId（header→body→`noid:{topic}:{bizKey|msgId}` 合成）并在 MqConsumerRegistrar 绑定 MqConsumeContext ThreadLocal，但消费幂等落库点对该上下文的采用长期不一致——order（MqConsumeService）、user（MqConsumeServiceImpl）已用 `MqConsumeContext.currentEventId()`，R4-26b 只补了 product 一处；全库清查后确认其余四域仍只信消息体，历史无信封/反序列化丢字段消息会以 null/空白键写消费流水（uk 列空白行去重语义退化，且同 topic 多条无键消息互相碰撞静默吞消费）。统一收口四处、范式完全一致（**body 非空白用 body，否则回退上下文，双空 fail-fast IllegalStateException 不写库**）：(1) **marketing** `MqConsumeTemplate.resolveEventId`（static，模板一处覆盖该域全部 listener：券/秒杀/拼团/预售/新人礼等）；(2) **settlement** `MqConsumeService.resolveEventId`（static，tryRecord 一处覆盖 6 调用点：缴费到账/清算冲正/清算×3/缺口工单；ORDER_PAID 双消费组已由 R4-24 uk_event_group 隔离）；(3) **pay** `MqConsumeSupport.firstConsume`（空白时取上下文并**回填 event.setEventId** 后照旧构造 MqConsumeLog，下游 payload/日志一致）；(4) **aftersale** `AftersaleMqServiceImpl` 新增 private static resolveEventId（复用既有 hasText），ORDER_SHIPPED/ORDER_CONFIRMED/REFUND_SUCCESS 三站点统一调用。活路径 BaseEvent 构造即随机 UUID（子类 @Builder 经隐式 super() 仍执行字段初始化器，已实证）不空白，本轮消除的是历史/异常消息空键风险；七域至此全部归一化（order/user/product 先前已合规）。测试新增 14 例：marketing MqConsumeTemplateTest 新建 4 例（body 优先/空白回退/重复跳过/双空 fail-fast）、settlement MqConsumeServiceTest 2→5、pay MqConsumeSupportTest 新建 4 例（含回填事件体断言）、aftersale AftersaleMqServiceImplTest 17→20（onOrderShipped/onRefundSuccess 上下文回退落库 + 双空 fail-fast 不写流水，@AfterEach clear ThreadLocal）；四模块 mvn test 全绿（marketing 274 / pay 154 / settlement 202 / aftersale 117，0 failure 0 error）。同套产物十二门复跑证据 final-20260920-1943-r427，结果回填于 CODE_REVIEW 第七轮。
- **W7 最终验收缺口修复波（第八轮 R4-28，2026-09-20，验收设施缺陷，非业务代码）**：r427 同套复跑 E2E 门 1 例失败（SettlementE2ETest.cycleS_dueDateAfterConfirm，stage=20 等待 20s 超时；orderNo 260920012000040600），最终一致在 19:55:08 实际达成（超时后 98s）。DB 取证定位真因——shop_order.t_mq_outbox 存在 **5363 行上一轮（r426，kind 形态）遗留的 status=0 待投递行**：R4-19 形态互斥在 HOSTAPPS 前静默 kind 工作负载、KIND_DEPLOY 前停宿主应用，但两轮验收背靠背时，上一轮 K6/E2E 期间登记的 outbox 行（含未来 deliver_at 的延时行）在宿主应用 19:48 启动后陆续到期，OutboxRelayJob（2s/轮、batch 100、同步 FIFO）对整个积压波补投约 6 分钟，E2E 19:52:49 新登记的 order_created/confirmed 排队到 19:55:06/08 才发出，消费者本身零错误。系统自愈行为正确（最终一致、无丢失），缺的是验收环境的「起始就绪」判据。修复：final-acceptance.sh E2E 阶段前新增 **R4-28 七库 outbox 到期积压排空闲忙门**（04b-outbox-drain.log 留证，90×10s 超时 die，七库 status=0 且 deliver_at<=NOW() 合计必须为 0 才放行 E2E；延时未到期行不拦）。首跑即实证：续跑时积压已被 relay 自然排空，门一次通过。同套产物 E2E 及后续 CHAOS→OVERSELL 七阶段续跑结果回填。
- W0/W0.5 未绿，W2–W5 不得开工（编译基线）。
- 每代理工时内自带模块单测；跨域契约靠 shop-api 冻结版本。
- W1 先于 W2/W3/W5（Feign/事务模式被业务卡引用）；W2 先于 W3；W4 与 W5 的 B1/B5 对接点在两规划中互相引用，联调放在 W7 E2E。
- 全部完成后只允许在同一套最终产物（最终源码→jar→镜像）上跑 final-acceptance.sh，禁止不同批次产物混采证据。
