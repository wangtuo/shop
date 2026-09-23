# 生产级验收 Checklist（对照需求与非功能指标）

> 勾选口径（2026-09-20）：功能/高可用项以同一套最终产物（源码→jar→镜像）经
> final-acceptance.sh 十二门实证——全量单测 2530、E2E 59 例、混沌 30/30、
> HA 74/74、K6 冒烟/超卖（winners=losers=paid=20）；证据目录
> deploy/loadtest/evidence/final-20260920-1800-r425（旧 final-20260920-102225 归档）。
> 未勾项均为受单节点 kind/托管环境/真实资质约束的保留项，见文末残留清单与
> PERF_REPORT.md §6，不做假实现。

## 功能完备性（design.md 逐条）

### 用户模块
- [x] 四类用户（游客/普通/商户/平台）注册登录鉴权
- [x] L0-L4 五级成长值、区间、折扣（9.8/9.5/9.2/9折）、积分倍率（1.1/1.5/2/3）
- [x] 成长值规则：消费1:1、评价+10、晒单+20、连签7天+50、年末80%折算保级
- [x] 余额/赠金/积分/优惠券四类账户；积分获取四场景及每日上限
- [x] 积分 365 天有效期、FIFO 冲减、过期清零
- [x] 积分抵现 100:1、单笔 50% 封顶
- [x] 地址：20 个上限、1 个默认、手机号验证

### 商品模块
- [x] 三级类目 + 属性模板；SPU/SKU 模型
- [x] 商品八态状态机与审核
- [x] 可售/预售/锁定/占用四库存与三个扣减节点
- [x] 库存预警（默认阈值10）、0 自动下架、补货自动上架
- [x] 五价取最低、促销/秒杀互斥
- [x] 评价：15天时效、三维评分、字数/图/视频限制、追评180天、回复、敏感词、好评率

### 营销模块
- [x] 五类促销 + 六类券 + 五类活动
- [x] 六层优惠叠加顺序实现
- [x] 互斥：秒杀全互斥、同层券1张、满减满折互斥、拼团无券无积分、预售仅尾款用券
- [x] 分摊最大余数法，金额守恒不差 1 分
- [x] 券生命周期与五种发放方式、过期作废
- [x] 拼团：2/3/5/10人、24h、每人1次、失败退款、团长优惠
- [x] 预售：定金膨胀、尾款3天、未付定金不退

### 订单模块
- [x] 订单结构、18位订单号规则
- [x] 九态状态机
- [x] 8 项下单前置校验、3 步核心流程
- [x] 超时矩阵：普通30分/秒杀15分/拼团24h/预售3天、自动收货10天
- [x] 购物车：99上限、分组、失效、勾选、清理
- [x] 发票三类型、完成后开具、退款冲红

### 支付模块
- [x] 7 种支付方式与限额
- [x] 创建支付单→渠道→回调（验签+幂等）扇出
- [x] 7 种支付状态
- [x] 原路退回/余额实时/混合按比例；累计不超实付
- [x] T+1 对账：长款/短款/金额不符三类差错处理

### 清算模块
- [x] 四类角色账户
- [x] 分账公式（佣金/技服费0.5/通道费0.6%/营销补贴）
- [x] 三清算节点 + S/A/B/C 四级结算周期
- [x] 提现：100元起、日50万、前3笔免费其后0.1%最低2元、T+1、自动提现
- [x] 退款清算瀑布（待结算→保证金）、通道费不退
- [x] 保证金：1000-50000、低于50%限提、清退90天

### 售后模块
- [x] 五类售后（仅退/退货退款/换货/补发/价保）
- [x] 三套状态流转
- [x] 申请时限（发货前/收货前/15天/质保期）
- [x] 可退金额公式、优惠不退、积分按比例退、运费三方承担
- [x] 商家审核 2/3/5 天超时自动化
- [x] 运费险：25元封顶、72h理赔、每单一次
- [x] 平台介入：3天举证、5工作日裁决
- [x] 价保：7天（大促30）、单单一次、活动价排除

## 非功能（design 第十章）
- [ ] 下单 5000 TPS 容量设计（无状态扩容 + Redis 原子扣库存）——架构已实证（20 库存/40 并发超卖零差错、30 TPS 拐点优雅降级），5000 TPS 指标保留多节点 perf 集群复测（PERF_REPORT §6）
- [ ] 支付回调 ≤2s（异步 MQ 扇出）——链路已实证，严格分位值未单独埋点，保留 perf 集群补测
- [ ] 商品详情 ≤200ms（缓存）——代码已实现，分位值未埋点，保留 perf 集群补测
- [ ] 订单列表 ≤500ms（分页索引）——代码已实现，分位值未埋点，保留 perf 集群补测
- [ ] 日终清算 ≤2h（分页批量）——分页批处理已实现，限时指标保留补测
- [x] 库存强一致（条件更新+锁+幂等流水）
- [x] 支付订单最终一致（MQ+补偿+对账）
- [x] SSL/脱敏/全链路日志/防重/防超卖/防刷

## 高可用
- [x] 全部组件多副本无状态（K8s 2 副本 + PDB + 反亲和 + HPA）——单节点 kind 下 HPA live-cap 2-2 验证滚动/中断（R4-15、HA 74/74）；真实多节点反亲和调度保留 §A 集群
- [x] 注册中心故障剔除
- [x] 分布式锁/幂等/选主定时任务
- [x] MQ 重试/死信/消费幂等
- [x] 超时双保险（延时消息 + 扫描补偿）
- [x] 中间件健康检查、Prometheus/Grafana 监控

## W7 最终残留清单（受托管环境/单节点 kind/真实资质约束，只记录不做假实现）

| 残留项 | 现状 | 解除条件 |
| --- | --- | --- |
| 真实渠道凭证与 KMS 托管 | mock 六渠道可闭环；prod profile 弱口令/缺失密钥 fail-fast，Secret 经 env/Secret 注入 | 真实微信/支付宝/银行/银联/花呗/白条商户号与密钥入 KMS，create-secrets.sh 接真实来源 |
| 托管中间件（MySQL/Redis/RocketMQ/Nacos HA + 备份） | 清单以 shop-infra ConfigMap/Secret 占位，本地 compose/kind 单实例验证 | §A 托管实例（主从/集群/备份）落地后替换真实端点与凭据 |
| broker 卷容量/水位监控告警 | ~~RUNBOOK §4.1 0.90 处置~~ R4-17 已核 RocketMQ 5.3.1 写门**源码硬上限 90%、85% 迟滞不可配**；本地侧关 commitlog 预分配/预热 + final-acceptance MIDDLEWARE 水位守卫（≥90% die+回收指引） | 生产 broker 节点磁盘 80% 预警/85% 扩容告警接入 Prometheus + Alertmanager |
| broker autoCreateTopic 环境变量化 | broker.conf.tpl 硬编码 true，主题靠 create-topics.sh 预建 | broker Deployment 改 RMQ_AUTO_CREATE_TOPIC=false + GitOps 主题预建流水线 |
| 运费按距离/重量计费 | 现有内部运费端点支持固定/模板规则 | 接入地址距离矩阵与重量阶梯计价模型 |
| 保证金清退账户绑定 | 复用最近成功提现账户，无则 DEPOSIT_ALERT 人工挂起 | 商户清退专用账户绑定/实名校验流程 |
| deposit_log 结构化 remit 字段 | 退还失败原因并入 remark(≤250) | 需要结构化 remit_fail_reason/query_count 时走 V7 迁移（V5 已入账 checksum 禁改） |
| 外部链路追踪/告警通道 | micrometer-tracing-bridge-otel 无 exporter；Alertmanager log-only | OTLP collector + Tempo/Jaeger；告警接钉钉/飞书/电话 |
| 孤儿 topic 治理 | 演练/历史 topic 保留在 broker | topic 生命周期清单 + 下线回收流程 |
| 5000 TPS 多节点容量 | 单节点 kind 实测拐点 20 TPS（PERF_REPORT §3），脚本已参数化 MODE=capacity | 多 worker 节点 perf 集群 + 独立中间件 + 分布式发压复测 |
| NetworkPolicy 强制 | 30-networkpolicy.yaml audit 零选中；kind/40 强制层默认关闭 | 多节点 + 强制 CNI 集群 ENFORCE_NETWORKPOLICY=1 灰度 |
| C59 审计落库 | @AuditLog 写 AUDIT logger（14+ 写端点全覆盖） | 审计日志接持久化存储（ES/数仓）与查询后台 |
| 提现人工审批、账号禁用/角色调整端点 | SW-2 登记端点缺失（当前状态机自动受理+规则拦截） | 运营后台端点 + 双人审批流 |
| @Async 线程池 requestId 透传 | W6-1 登记风险：异步线程不串 traceId | TaskDecorator/MDC 上下文包装 |

### W7 第五/六/七/八轮（R4-25/R4-26/R4-27/R4-28，2026-09-20）收口与保留

| 项 | 现状 | 解除条件 |
| --- | --- | --- |
| ~~outbox 同单号多事件撞 UK（积分/秒杀/评价/库存预警/保证金）~~ | ✅ R4-25 五项全收口（维度后缀键 + CAS 边沿闸门 + DuplicateKeyException 兜底），同套十二门全绿（final-20260920-1800-r425） | 无 |
| ~~售后跨轮旧延时消息提前自动流转~~ | ✅ R4-26a 轮次新鲜度守卫（当前 deadline 现算 roundKey 比对，旧轮 ACK 丢弃），同套十二门复跑（final-20260920-1840-r426） | 无 |
| ~~七域消费幂等 eventId 归一化（product 空键起查）~~ | ✅ R4-26b 修 product；R4-27 全库清查后统一收口其余四域（marketing 模板一处 / settlement tryRecord 一处覆盖 6 调用点 / pay support 回填事件体 / aftersale 三站点），order/user 先前已合规；统一范式 body→MqConsumeContext 上下文→双空 fail-fast；新增 14 例单测 | 无 |
| ~~背靠背复跑上轮 outbox 积压补投致 E2E 假失败~~ | ✅ R4-28 验收脚本 E2E 前新增七库到期积压排空闲忙门（15min 超时 die，04b-outbox-drain.log 留证）；实证 5363 行 r426 遗留补投致新事件 +137s，系统最终一致零丢失，属验收设施缺陷非业务代码 | 无 |
| 其余六库 mq_consume 单列 uk_event_id | R4-24/R4-26 全量清查：除 settlement（已升 uk_event_group）外，每库每个 topic 仅一个消费组，多组扇出场景不存在，无需迁移 | 未来某 topic 新增第二消费组时必须同步升级为 (event_id,consumer_group) |
| K6 warmup 窗口冷启动噪声 | HA 滚动后 warmup 头 8–25s 偶发 10008（504/10010 同类），不计阈值（{scenario:smoke} 仅测量窗，R4-18 方法学），测量窗历次 0 失败；全量含 warmup 的 p95 会越阈，属口径差异非容量问题 | 若要全窗口达标：HA 后静置/主动预热再压测，或扩冷启动容量（perf 集群） |
| R4-25 前历史无 roundKey 售后延时存量消息 | R4-26 守卫对无 roundKey 消息放行（无法判别轮次），继续由状态机守卫兜底；仅「升级前登记 + 升级后恰好重提」窄窗可能并存，新登记消息全部带键 | 存量延时消息全部投递/超时后自然清零（最长 5 天） |
| 复跑环境启停顺序 | 验收脚本已内建互斥（HOSTAPPS 前静默 kind 负载、KIND_DEPLOY 前停宿主 8 应用）；手工复跑须保持该顺序，中间件容器（mysql/redis/rmq-*/nacos）保持 Up、数据卷不删 | 无（操作手册见 RUNBOOK/final-acceptance.sh 头注释） |

## W1-C 残留项（受文件边界/kind 单节点/§A 托管未落地约束，只记录不做假实现）
| 残留项 | 现状 | 解除条件 / 责任波次 |
| --- | --- | --- |
| 托管 MySQL/Redis/RocketMQ/Nacos 高可用+备份 | 清单仅以 shop-infra 命名空间/VPC 注释占位（ConfigMap 键 MYSQL_HOST/REDIS_HOST/ROCKETMQ_ENDPOINTS/NACOS_ADDR） | §A 托管实例与备份策略落地后，由 W1-D/总控替换真实地址与凭据 |
| registry.example.com 镜像仓库 | 8 个工作负载镜像仍为占位域名；kind 靠 imagePullPolicy=IfNotPresent + 本地 load | 真实仓库域名替换、imagePullSecrets 配置、CI 镜像签名/ digest 固定 |
| 中间件 TLS（useSSL 等） | 业务 JDBC URL 已参数化 `useSSL=$(SHOP_DB_USE_SSL)`，默认 false；Redis/RMQ/Nacos TLS 键仅注释占位 | 托管中间件 TLS 就绪后 W1-D 在 shop-infra-config 注入 true 及对应 TLS 配置 |
| 网关容器 imagePullPolicy/securityContext | ✅ **R4-12/R4-14 已闭环**：00-namespace-config.yaml 已补 IfNotPresent、数字 UID runAsNonRoot、只读根+emptyDir、drop ALL、enableServiceLinks=false、REDIS_HOST 注入，HA 六节实证 2/2 Running | 无（生产清单已与 7 业务同款硬化） |
| broker autoCreateTopic 环境变量化 | deploy/rocketmq/broker.conf.tpl 硬编码 autoCreateTopic=true，不在 W1-C 可改目录 | broker Deployment 侧改环境变量（如 RMQ_AUTO_CREATE_TOPIC=false）+ 主题预建，W7 后总控跟进 |
| JVM RAM 百分比复算 | Dockerfile 共享 JAVA_OPTS InitialRAMPercentage=40/MaxRAMPercentage=60（Max≈1.2Gi/2Gi limit） | 调整 memory request 时须同步复算；Dockerfile 改动归后续波次 |
| NetworkPolicy 强制生效 | 30-networkpolicy.yaml 默认仅零选中 audit 标记；default-deny/egress 白名单为注释模板（§A 占位未替换） | 多节点+强制 CNI 集群跑通 ha-check/chaos 后，ENFORCE_NETWORKPOLICY=1 验证再灰度模板 |
| 只读根文件系统可写面 | ✅ R4-12 起 8 个工作负载均 readOnlyRootFilesystem + /tmp、/tmp/heapdump、/app/logs emptyDir；W7 HA + k6 20TPS 全量负载下无相对路径写文件报错 | 无（kind 实证通过） |
| Ingress TLS 下限注解 | 已加 ssl-min-version=TLSv1.2；TLSv1.3 优先需控制器 ConfigMap ssl-protocols | W7 验证控制器版本支持该注解并补 TLSv1.3 全局配置 |

