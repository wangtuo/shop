# FIXES_G —— P2-5 生产修复：同一订单支持多次支付尝试（墓碑槽位）

> 负责人：支付域
> 日期：2026-09-16
> 基线：shop-pay-service 本地实测 **71 tests / 0 failures**（FIXES_D 时为 69，FIXES_F 后为 71）
> 收尾：**shop-pay-service 79 tests（+8）/ failures 0 / errors 0 / skipped 0**；`mvn -o -pl shop-api,shop-pay-service test` 全绿（未改 shop-api，仅复用既有 `OrderStatuses` 枚举）
> 活库：V4 已在 docker `shop-mysql` 的 `shop_pay` 库执行并二次重放验证幂等，全链路语义手工验证通过（见第六节）

---

## 一、问题（FIXES_D P2-5 留痕缺口）

`t_pay_order` 原唯一键 `uk_order_no(order_no)` 决定一个订单只能有一行支付单；
`PaymentServiceImpl.lockAndCreate` 对**任意状态**旧单都幂等返回旧单。当第三方渠道支付单
进入 FAIL(40)/CLOSED(50)（渠道关单、支付失败、15 分钟 PAY_RESULT 延时核查置关闭）后，
用户对仍处于"待付款"的订单点"重新支付"只能拿到一张死单，无法再次支付——与
design.md 5.3.3"超时未付才自动取消订单；超时前用户必须能重新发起支付"相悖。

## 二、方案：墓碑槽位（对齐 DDL_REVIEW.md 既有模式）

沿用本仓库已建立的"0=活 / 自身雪花 id=死，唯一键含槽位列"模式：

- `active_slot=0`：当前活跃支付单，每订单至多一条（`uk_order_active(order_no, active_slot)` 保证）；
- `active_slot=id`：被取代的 FAIL/CLOSED 终态历史行入自身槽位，释放 `(order_no,0)` 活跃槽位；
- 同一订单可有多条历史尝试行，各自的渠道流水/回调日志按 `pay_no` 天然隔离。

### 发起支付新语义（均在既有 `pay:lock:create:{orderNo}` 分布式锁 + 同一数据库事务内）

| 旧活跃单状态 | 行为 |
|---|---|
| 不存在 | 直接新建 WAIT 单（新 payNo + 新 channel flows + 新 PAY_RESULT 延时核查 outbox） |
| FAIL(40) / CLOSED(50) | 先 CAS 旧单入墓碑槽位（`active_slot=id WHERE id=? AND active_slot=0 AND status IN (40,50)`，更新 1 行才继续；0 行重读活跃行幂等返回/冲突报错），再新建 WAIT 单 |
| WAIT(10) / PAYING(20) | 幂等返回旧单，**即使 expireTime 已过也不直接新建**：过期等待单仍可能收到渠道迟到成功回调/线下已付款，直接开新单有重复扣款风险；由 `scanTimeout` 核查渠道置 CLOSED 后，下次发起才走终态入槽 + 新建（代码注释固化此决策） |
| SUCCESS(30) / REFUNDING(60) / REFUNDED(70) | 已支付成功，幂等返回旧单，绝不新建；成功行永远保留 active_slot=0，槽位不再释放 |

- C-3 既有防线保留：C 端金额一律取订单域反查的应付金额（不信请求体）、userId 取网关注入身份；
- 余额重新支付：新尝试以新 payNo 作为扣款 bizNo（用户域幂等键不撞失败旧单），同事务扣款成功路径保留 P1-2 `completeSuccess` CAS，仅获胜方 publishPaid（ORDER_PAID/PAY_RESULT 各一次）；
- 第三方新尝试同样登记 15 分钟 PAY_RESULT 延时核查 outbox，全部走既有 `OutboxPublisher`（无 MqProducer 直发）。

### 订单状态联动（第五节）

C 端发起新增订单域状态校验：仅 `OrderStatuses.WAIT_PAY(10)` 允许发起/重新发起支付；
已取消(50，含 design 5.3.3 超时自动取消)或支付后状态(20+) 抛 `ORDER_STATUS_ERROR(50002)`。
不在 pay 库重建订单状态，全部以订单域 Feign 反查为准；内部 Feign 入参（订单域服务端构造）契约不变。

## 三、SQL（新文件，幂等可重复执行）

`sql/pay/V4__pay_multi_attempt.sql`：

1. `information_schema.COLUMNS` 守卫：`ALTER TABLE t_pay_order ADD COLUMN active_slot BIGINT NOT NULL DEFAULT 0 COMMENT '...' AFTER order_no`（存量行经旧 uk_order_no 保证一订单一行，默认 0 不撞新键）；
2. `information_schema.STATISTICS` 守卫：存在 `uk_order_no` 则 `DROP INDEX`；
3. 不存在 `uk_order_active` 则 `ADD UNIQUE KEY uk_order_active (order_no, active_slot)`；
4. 风格对齐 `sql/settlement/V3__settlement.sql`（DROP PROCEDURE / DELIMITER / information_schema / CALL / DROP PROCEDURE），表保持 `utf8mb4_unicode_ci`。

活库执行：`docker exec -i shop-mysql mysql -uroot -proot shop_pay < sql/pay/V4__pay_multi_attempt.sql`（已执行，见第六节）。

## 四、代码改动清单（file:line）

### 主代码（4 个文件）

1. `sql/pay/V4__pay_multi_attempt.sql`（**新增**）：见第三节。
2. `shop-pay-service/.../payment/entity/Payment.java:25-31`（修改）：新增 `Long activeSlot` 字段（map-underscore-to-camel-case 既有配置映射 `active_slot`）。
3. `shop-pay-service/.../payment/mapper/PaymentMapper.java:16-39`（修改）：
   - `retireActiveSlot(id)`（:17-23）：终态旧单 `active_slot 0→id` CAS，限定 `status IN (40,50)`；
   - `selectActiveByOrderNo(orderNo)`（:25-30）：只取 `active_slot=0` 活跃行；
   - `selectRefundableByOrderNo(orderNo)`（:32-39）：退款专用，`active_slot=0 AND status IN (30,60,70)`。
4. `shop-pay-service/.../payment/service/impl/PaymentServiceImpl.java`（修改）：
   - :114-117 C 端发起新增订单状态 WAIT_PAY 校验（订单域为准）；
   - :132-160 `lockAndCreate` 多尝试分支：终态入槽 CAS + 新建 / WAIT·PAYING·成功系幂等返回 / CAS 落败重读，含过期 WAIT 不新建的决策注释；
   - :232 `buildPayment` 显式 `setActiveSlot(0L)`；
   - :626-632 `findByOrderNo` 语义改为活跃行查询（getByOrderNo/viewByOrderNo 同步受益）。
5. `shop-pay-service/.../refund/service/impl/RefundServiceImpl.java:298-310`（修改）：
   `requirePaymentByOrderNo` 改用 `selectRefundableByOrderNo`，显式定位 SUCCESS/REFUNDING/REFUNDED 活跃行；
   后续 `addRefundedFen`/`enterRefunding`/`markRefunded` 均按该行 payNo 落账，防止重新支付场景串到历史失败单；retry 路径仍按 payNo 直达（payNo 全局唯一，不受影响）。

### 全量审计结论（无需改动部分）

- **回调 `handleNotify`**：全程按 payNo 直达（`uk_pay_no` 唯一），金额交叉校验命中回调对应行；FAIL/CLOSED 终态行收到 SUCCESS 回调在状态机分支即拒绝（`markSuccess` CAS `status IN (10,20)` 双保险），已补测试。
- **主动查询 `activeQuery` / 关单 `scanTimeout`**：按 payNo / status IN(10,20) 工作；同一时刻仅一条活跃 10/20 行（新尝试只在旧单 40/50 后创建），历史终态行天然不参与。
- **t_pay_channel_flow / t_pay_notify_log**：均按 payNo 关联（flow 的 `uk_channel_order_no` 中渠道单号由 mock 按 payNo+seq 生成，多尝试不撞键）；mapper 无"一订单一行"假设。
- **对账 ReconcileServiceImpl**：本地集合 `selectSuccessBetween` 按成功状态 + payNo 参与对账，多尝试行各自独立；差错补单 `handleLong` 按 payNo 查单且只补 10/20 行，成功行 active_slot 恒为 0，补单 ORDER_PAID 只认成功行；无需改动。
- **订单域**（只读确认，未改）：`PayTimeoutScanJob`/`PayTimeoutListener` 仅对 10 状态订单做 10→50 超时取消 CAS；`PayEventConsumer.onPaid` 的 `markPaid` 为 10→20 CAS 幂等；支付域新增的 WAIT_PAY 校验在扣款前拦截已取消订单，杜绝"钱扣了、订单无法落单"的资金悬挂。`OrderOperateServiceImpl:91` 按 payNo 查支付，不受影响。

## 五、状态机保护与边界

- 终态不可逆：旧 FAIL/CLOSED 行的迟到 SUCCESS 回调 → `CONFLICT(10005)` + notify 记处理失败(handle_status=3)，`markSuccess` 0 行，不翻单、不发 ORDER_PAID（测试 :595）。
- 并发：分布式锁内 `retireActiveSlot` CAS 0 行时重读活跃行返回获胜方，绝不带 active_slot=0 强插（即便绕过应用层，`uk_order_active` 也以 1062 回滚事务）。
- 过期 WAIT：宁可让用户等核查 job 关单后再发起，也不承担重复扣款风险（测试 :457）。
- 订单取消后发起：订单域状态 50002 拦截（测试 :515）。

## 六、测试清单与结果

命令：`mvn -o -q -pl shop-api,shop-pay-service test`（离线）。shop-pay-service 聚合 surefire：
**tests=79 failures=0 errors=0 skipped=0**（基线 71，+8）。

### PaymentServiceImplTest：19 → 25（+6）

| 用例 | 行 | 说明 |
|---|---|---|
| `createPayment_旧单PAYING_幂等返回旧单不新建`（保留，stub 迁移至 selectActiveByOrderNo） | :348 | WAIT/PAYING 现状不变 |
| `createPayment_旧单FAIL或CLOSED_旧单入墓碑槽位并新建第二张支付单`（D 现状用例改写） | :371 | FAIL+CLOSED 两态循环：旧单 active_slot=自身 id、新单 slot=0/新 payNo/新延时核查、ORDER_PAID 未提前发 |
| `createPayment_旧单SUCCESS_幂等返回旧单绝不新建`（新） | :432 | 不入槽/不 insert/不登记延时 |
| `createPayment_旧单WAIT已过期_仍返回旧单不新建`（新） | :457 | 固化过期 WAIT 决策 |
| `createPayment_终态旧单入槽CAS落败_幂等返回并发获胜方新单不新建`（新） | :484 | retire 0 行 → 返回并发获胜方 WAIT 单 |
| `createPayment_订单已取消_订单域状态校验挡住不发起支付`（新） | :515 | order status=50 → 50002，不查支付单/不 insert |
| `createPayment_FAIL旧单后余额重新支付_扣款成功且ORDER_PAID仅一次`（新） | :535 | 入槽+余额扣款 bizNo=新 payNo，ORDER_PAID/PAY_RESULT 各 times(1)，无延时核查 |
| `handleNotify_FAIL旧单迟到成功回调_终态不可逆转不翻单不发ORDER_PAID`（新） | :595 | CONFLICT、markSuccess never、notify 置失败、旧单仍 FAIL |

### RefundServiceImplTest：8 → 10（+2；3 个既有用例 stub 迁移至 selectRefundableByOrderNo）

| 用例 | 行 | 说明 |
|---|---|---|
| `refund_多次支付尝试_仅定位成功活跃行并按其payNo落账`（新） | :213 | 历史 P1 FAIL + 活跃 P2 SUCCESS：累计退款/全额退款落 P2，订单维度从不走无过滤 selectOne，不查 P1 流水 |
| `refund_仅有失败尝试行_成功行过滤后查无支付单_抛NOT_FOUND`（新） | :251 | 无成功行时 404，不发 REFUND_SUCCESS |

## 七、活库验证（shop_pay，已清理造数）

1. V4 执行后 `information_schema`：列 `active_slot BIGINT NOT NULL DEFAULT 0`（中文注释完整），索引 `uk_order_active(order_no,active_slot)` 唯一，`uk_order_no` 已不存在，`uk_pay_no` 等其余键不变；
2. 脚本二次重放成功（列/键守卫全部跳过），幂等性验证通过；
3. 手工生命周期（`OTESTV4`）：
   - 插入 WAIT(PTESTV4_1) → 渠道置 FAIL(40) → retire CAS（active_slot 置为自身 id=1）→ 新建 WAIT(PTESTV4_2, slot=0)：两行共存、**仅一行 active_slot=0**；
   - PTESTV4_2 置 CLOSED(50) → 再入槽(slot=2) → 新建 PTESTV4_3(slot=0)：三行、历史槽位各为自身 id；
   - 对 FAIL 旧单 PTESTV4_1 执行 markSuccess 同条件 `UPDATE ... SET status=30 WHERE pay_no=? AND status IN (10,20)`：影响 0 行，状态保持 40；
   - 强插第二行 `(OTESTV4, active_slot=0)` 被拒：`ERROR 1062 Duplicate entry 'OTESTV4-0' for key 't_pay_order.uk_order_active'`；
   - 验证后已 `DELETE` 造数（leftover=0）。

## 八、残留风险

1. **历史数据**：V4 上线时存量行均置 active_slot=0（旧 UK 保证一订单一行），无回填风险；但若存在人工改过库的重复 order_no 脏数据，加 `uk_order_active` 不会失败（默认 0 才冲突），而补列后新键创建会直接 1062 失败——上线前建议 `SELECT order_no,COUNT(*) FROM t_pay_order WHERE active_slot=0 AND deleted=0 GROUP BY order_no HAVING COUNT(*)>1` 预检（当前活库为空表级数据量，无此问题）。
2. **过期 WAIT 体验**：过期但未被核查 job 置 CLOSED 的窗口期内，用户点重新支付仍拿到旧 WAIT 单（无新支付链接或旧链接已失效）。这是"防重复扣款优先"的有意取舍；依赖 15 分钟延时核查 + scanTimeout 收敛，极端情况下用户需等待一个核查周期。如需更好体验，后续可在 lockAndCreate 对过期 WAIT 同步触发一次 `activeQuery`（本次不做，避免扩大事务内外部调用）。
3. **渠道单号/退款**：mock 渠道单号按 payNo 生成保证多尝试唯一；若未来接入真实渠道且渠道侧以业务 orderNo 作为幂等键，重新支付时需用新 payNo 下单（现状即如此），渠道侧旧单需已关闭，否则可能被渠道拒绝建单——属渠道对接约束，非本次缺陷。
4. **内部 Feign 发起**：`CreatePaymentCommand` 路径按既有契约不反查订单（订单域服务端构造），订单状态校验只加在 C 端入口；订单域自身只会在 10 状态发起支付，调用方约束未变。

## 九、未改动声明

未改：shop-api（仅消费既有 `OrderStatuses`）、shop-framework、shop-gateway、其他服务、deploy/、其他 sql 目录、application.yml；无 MqProducer 直发，事件全部走既有 OutboxPublisher。
