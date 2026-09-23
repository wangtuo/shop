# FIXES_D —— shop-pay-service / shop-aftersale-service 整改报告（P1-1 / P1-2 / P1-11 / P2-2 / P2-5 / P2-8）

> 负责人：支付域 + 售后域
> 日期：2026-09-16
> 基线：pay 62 tests、aftersale 59 tests 全绿
> 收尾：**pay 69 tests（+7）/ failures 0 / errors 0；aftersale 62 tests（+3）/ failures 0 / errors 0**（均按 surefire `TEST-*.xml` 聚合）
> DDL：两个库的 `t_mq_outbox` 已由 `sql/common/V3__outbox.sql` 建表（docker mysql 实测 `shop_pay`、`shop_aftersale` 均已存在），本次无新增 DDL。
> 未改动：`application.yml`、`sql/`、`deploy/`、`shop-framework`、`shop-gateway` 及其他模块。

---

## 一、整改总览

| 项 | 内容 | 结果 |
|---|---|---|
| P1-1 | 两模块所有"DB 事务内同步 `mqProducer.send/sendAsync/sendDelay`"改为同事务 `outboxPublisher.publish/publishDelay`，构造器注入 | 8 个发送点全部迁移，两模块 src 下 `MqProducer` 引用归零 |
| P1-2 | 并发重复支付回调：`markSuccess` CAS 唯一获胜方才登记 outbox，重复回调 ACK 但不重复发 ORDER_PAID | 已修 + 并发测试 |
| P1-11 / P2-8 | `startRefund`：refundNo 售后域先生成并以 REQUIRES_NEW 先落库 → 再调支付域；重试复用同一 refundNo；调用失败 FAIL 以 REQUIRES_NEW 保留 | 已修 + 3 个测试 |
| P2-2 | `ChannelNotifyController.payNotify` 移除 `@Idempotent` 切面，重复通知由服务层 `t_pay_notify_log` 幂等并返回成功 | 已移除 + 反射测试 |
| P2-5 | 重复发起支付：按现状补测试（旧单任意状态均幂等返回旧单），未动 C-3 鉴权（金额强制取订单应付、userId 取登录身份） | 补 2 个测试 + 风险登记 |

### outbox 发送点清单（8 处，全部在状态变更的同一 `@Transactional` 方法内）

| # | 文件:行 | topic / tag | 事务边界 |
|---|---|---|---|
| 1 | shop-pay-service/.../payment/service/impl/PaymentServiceImpl.java:148 | PAY_RESULT / "check"（延时 15min） | `lockAndCreate` 第三方渠道分支，与 payment insert 同事务 |
| 2 | 同文件:403 | ORDER_PAID / "paid" | `publishPaid()`，仅 `completeSuccess` CAS 获胜方调用 |
| 3 | 同文件:405 | PAY_RESULT / "result" | 同上 |
| 4 | shop-pay-service/.../refund/service/impl/RefundServiceImpl.java:240 | REFUND_SUCCESS / "refund" | `executeRefund` 仅被 `@Transactional refund()/retry()` 调用 |
| 5 | shop-pay-service/.../recon/service/impl/ReconcileServiceImpl.java:237 | ORDER_PAID / "recon" | `handleDiff/runReconcile/retryPendingDiffs`（均 @Transactional），且在 `markSuccess rows>0` 唯一获胜分支内 |
| 6 | shop-aftersale-service/.../support/AftersaleEventPublisher.java:51 | AFTERSALE_CHANGED / newStatus | 各 @Transactional 入口在状态 CAS 之后调用 |
| 7 | shop-aftersale-service/.../mq/service/impl/AftersaleMqServiceImpl.java:230 | AFTERSALE_TIMEOUT / "insurance"（延时 72h） | `onRefundSuccess` 消费事务内，与运费险 insert 同事务 |
| 8 | shop-aftersale-service/.../aftersale/service/impl/AftersaleServiceImpl.java:853 | AFTERSALE_TIMEOUT / kind（延时） | `sendDelay`，所有调用方均为 @Transactional 入口 |

---

## 二、修改/新增文件清单

### shop-pay-service（主代码 3 个）

1. `src/main/java/com/shop/pay/feature/payment/service/impl/PaymentServiceImpl.java`（修改）
   - 构造器第 9 参注入 `OutboxPublisher`，删除 `MqProducer` 字段/import；现 11 参：
     `(paymentMapper, channelFlowMapper, notifyLogMapper, payNoGenerator, channelRouter, signVerifier, stateMachine, lockTemplate, outboxPublisher, userClient, orderClient)`。
2. `src/main/java/com/shop/pay/feature/refund/service/impl/RefundServiceImpl.java`（修改）
   - 构造器第 9 参 `MqProducer` → `OutboxPublisher`。
3. `src/main/java/com/shop/pay/feature/recon/service/impl/ReconcileServiceImpl.java`（修改）
   - 构造器末参注入 `OutboxPublisher`，长款补单事件迁移。
4. `src/main/java/com/shop/pay/feature/payment/controller/ChannelNotifyController.java`（修改，P2-2）
   - 删除方法上的 `@Idempotent(prefix="pay:notify", key="#channel + ':' + #request.notifyId", ttlSeconds=24*3600L)` 及 import，类 Javadoc 说明重复通知必须 ACK。

### shop-pay-service（测试 4 个，新增 2 / 修改 2）

5. `src/test/java/com/shop/pay/feature/payment/service/PaymentServiceImplTest.java`（修改，+4 用例）
6. `src/test/java/com/shop/pay/feature/refund/service/RefundServiceImplTest.java`（修改，mock 置换 + 幂等用例补 outbox 校验）
7. `src/test/java/com/shop/pay/feature/payment/controller/ChannelNotifyControllerIdempotentTest.java`（**新增**）
8. `src/test/java/com/shop/pay/feature/recon/service/ReconcileServiceImplTest.java`（**新增**，2 用例）

### shop-aftersale-service（主代码 4 个，新增 1）

9. `src/main/java/com/shop/aftersale/support/AftersaleEventPublisher.java`（修改）：`MqProducer` → `OutboxPublisher`。
10. `src/main/java/com/shop/aftersale/mq/service/impl/AftersaleMqServiceImpl.java`（修改）：构造器 13 参，末参 `OutboxPublisher`。
11. `src/main/java/com/shop/aftersale/aftersale/mapper/AftersaleRefundMapper.java`（修改）：新增 `markFail` 条件更新。
12. `src/main/java/com/shop/aftersale/support/AftersaleRefundStore.java`（**新增**）：REQUIRES_NEW 独立事务写入器。
13. `src/main/java/com/shop/aftersale/aftersale/service/impl/AftersaleServiceImpl.java`（修改）：`startRefund/invokePayRefund` 重写（P1-11/P2-8）；`sendDelay` 迁移 outbox；构造器现 19 参（末两参 `outboxPublisher, refundStore`），删除 `MqProducer`。

### shop-aftersale-service（测试 2 个，均修改）

14. `src/test/java/com/shop/aftersale/mq/service/AftersaleMqServiceImplTest.java`（修改，13 参构造 + outbox 校验）
15. `src/test/java/com/shop/aftersale/aftersale/service/AftersaleServiceImplTest.java`（修改，+3 用例）

---

## 三、逐项说明（文件:行号 + 关键代码）

### P1-1 + P1-2 支付域：CAS 获胜者同事务登记 outbox

**余额支付即时成功**（PaymentServiceImpl.java:137-146）：

```java
if (isAllBalance(parts)) {
    debitBalance(payment, flows);
    Payment paid = findByPayNo(payment.getPayNo());
    boolean advanced = completeSuccess(paid, channelFlowMapper.selectByPayNo(payment.getPayNo()),
            "BALANCE_TXN_" + payment.getPayNo(), "BALANCE_DEBIT", LocalDateTime.now());
    // 仅条件更新获胜者登记 outbox（与状态变更同事务），杜绝重复 ORDER_PAID（P1-2）
    if (advanced) {
        publishPaid(findByPayNo(payment.getPayNo()));
    }
}
```

**第三方渠道：15 分钟延时补偿查询改 outbox 延时消息**（PaymentServiceImpl.java:147-151）：

```java
outboxPublisher.publishDelay(MqTopics.PAY_RESULT, "check",
        new PayCheckMessage(payment.getPayNo(), payment.getOrderNo()),
        payment.getPayNo(), MqTopics.DELAY_15_MIN_SECONDS);
```

与 `paymentMapper.insert` 在同一 `lockAndCreate` 事务：支付单回滚则延时核查事件一并消失，不会出现"有核查消息无支付单"。

**渠道回调：落败的重复回调仍 ACK，但只有获胜方发事件**（PaymentServiceImpl.java:344-356）：

```java
boolean advanced = completeSuccess(payment, channelFlowMapper.selectByPayNo(payment.getPayNo()),
        params.getChannelTxnNo(), params.getNotifyId(), paidTime);
// 不同 notifyId 的重复通知也要 ACK（渠道重发属正常行为，P2-2）
markNotifyDone(params);
if (advanced) {
    publishPaid(findByPayNo(payment.getPayNo()));
}
```

**CAS 获胜判定**（PaymentServiceImpl.java:365-389）——`completeSuccess` 返回 boolean：

```java
int rows = paymentMapper.markSuccess(payment.getPayNo(), channelTxnNo, notifyId, paidTime);
if (rows == 0) {
    Payment latest = findByPayNo(payment.getPayNo());
    if (latest != null && latest.getStatus() == PayStatuses.SUCCESS.getCode()) {
        return false;                       // 并发落败：幂等返回，调用方不得再发事件
    }
    throw new BizException(ErrorCode.CONFLICT, "支付单状态并发冲突");
}
// ... 渠道流水 CAS 推进 ...
return true;
```

三条成功路径（余额下单、渠道回调、主动查询 activeQuery:452-455）统一走该判定，`ORDER_PAID`/`PAY_RESULT` 全局面上只可能由唯一获胜线程登记一次。

**事件登记**（PaymentServiceImpl.java:391-406）：

```java
event.setBizNo(payment.getPayNo());
outboxPublisher.publish(MqTopics.ORDER_PAID, "paid", event, payment.getPayNo());
outboxPublisher.publish(MqTopics.PAY_RESULT, "result", event, payment.getPayNo());
```

`OutboxPublisher` 无活动事务同步时 fail-fast 抛 `IllegalStateException`，从机制上保证不会误在事务外登记。

### P1-1 退款域：REFUND_SUCCESS 同事务

RefundServiceImpl.java:238-240：

```java
// P1-1：在退款事务内登记 outbox（executeRefund 仅被 @Transactional 的 refund/retry 调用）
outboxPublisher.publish(MqTopics.REFUND_SUCCESS, "refund", event, refund.getRefundNo());
```

`executeRefund`（RefundServiceImpl.java:137）为私有方法，调用方只有 `refund()`（:67 @Transactional）与 `retry()`（:95 @Transactional）；退款单/流水/拆分的状态推进与 outbox 行同提交回滚。

### P1-1 对账域：长款补单只在补单 CAS 获胜时登记

ReconcileServiceImpl.java:215-240：

```java
int rows = paymentMapper.markSuccess(payment.getPayNo(), diff.getChannelTxnNo(),
        "RECON_LONG_" + diff.getBatchNo(), LocalDateTime.now());
if (rows > 0) {
    // ... 渠道流水补登 ...
    // P1-1：补单事件随差错处理事务登记 outbox（rows>0 的唯一获胜分支）
    outboxPublisher.publish(MqTopics.ORDER_PAID, "recon", event, latest.getPayNo());
}
diffMapper.updateStatus(diff.getId(), diff.getStatus(), 30, "SUPPLEMENT_ORDER", ...);
```

`handleDiff`（:184 @Transactional）、`runReconcile`（:62）、`retryPendingDiffs`（:168）均为事务入口；补单落败（rows=0）不发事件，下游按 payNo 幂等消费。

### P1-1 售后域：状态变更事件 + 两类延时消息

- AftersaleEventPublisher.java:51：售后状态 CAS 后同事务登记 `AFTERSALE_CHANGED`（bizNo=aftersaleNo）：

```java
outboxPublisher.publish(MqTopics.AFTERSALE_CHANGED, String.valueOf(newStatus), event,
        o.getAftersaleNo());
```

- AftersaleMqServiceImpl.java:224-232：REFUND_SUCCESS 消费事务内，运费险落库与 72h 理赔延时消息同事务：

```java
// P1-1：延时事件在本消费事务内登记 outbox，与 t_aftersale_insurance 同提交/回滚
outboxPublisher.publishDelay(AftersaleDelayTopics.AFTERSALE_TIMEOUT,
        AftersaleDelayTopics.KIND_INSURANCE, msg, o.getOrderNo(), INSURANCE_DELAY_SECONDS);
```

- AftersaleServiceImpl.java:849-854：`sendDelay`（商家审核超时、自动收货等）同事务登记延时事件。

### P1-11 / P2-8 售后发起退款：refundNo 先落库、复用、FAIL 独立事务保留

**独立事务 Bean**（AftersaleRefundStore.java，解决同类自调用导致 REQUIRES_NEW 不经过代理的问题）：

```java
@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
public void insertWaitingInNewTx(AftersaleRefund refund) { refundMapper.insert(refund); }

@Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
public void markFailInNewTx(String refundNo, String failReason) {
    String reason = failReason == null ? "支付域退款失败" : failReason;
    if (reason.length() > MAX_FAIL_REASON) reason = reason.substring(0, MAX_FAIL_REASON); // 500
    refundMapper.markFail(refundNo, reason);
}
```

配套 Mapper（AftersaleRefundMapper.java:33）：

```java
@Update("UPDATE t_aftersale_refund SET status = 40, fail_reason = #{failReason}, update_time = NOW() "
      + "WHERE refund_no = #{refundNo} AND deleted = 0")
int markFail(@Param("refundNo") String refundNo, @Param("failReason") String failReason);
```

**startRefund 重写**（AftersaleServiceImpl.java:749-778）：

```java
AftersaleRefund refund = refundMapper.selectByAftersaleNo(o.getAftersaleNo());
if (refund == null) {
    refund = new AftersaleRefund();
    refund.setRefundNo(noGenerator.nextRefundNo());   // refundNo 由售后域生成
    // ... 赋值 status=REFUND_WAIT(10) ...
    refundStore.insertWaitingInNewTx(refund);          // P1-11：Feign 前先独立事务提交
    invokePayRefund(o, refund, amount);
} else if (status == REFUND_WAIT || status == REFUND_FAIL) {
    // WAIT=崩溃在调用窗口、FAIL=上次支付域失败：复用同一 refundNo（pay 侧按 refundNo 幂等返回）
    invokePayRefund(o, refund, amount);
} else {
    // PROCESSING/SUCCESS：支付域已受理/成功，不重复调用，仅幂等推进售后单
    o.setRefundNo(refund.getRefundNo());
    orderMapper.updateById(o);
}
change(o, AftersaleStatuses.REFUNDING, operatorId, role, remark, null);
```

**invokePayRefund**（AftersaleServiceImpl.java:780-815）：

```java
CreateRefundCommand cmd = CreateRefundCommand.builder().refundNo(refund.getRefundNo())...build();
try {
    dto = unwrap(payClient.refund(cmd));
} catch (BizException ex) {
    refundStore.markFailInNewTx(refund.getRefundNo(), ex.getMessage()); // P2-8：FAIL 独立事务保留
    throw ex;                                                          // 外层照常回滚售后单变更
}
if (dto == null || dto.getStatus() == RefundStatuses.FAIL.getCode()) {
    refundStore.markFailInNewTx(refund.getRefundNo(), reason);
    throw new BizException(ErrorCode.DEPENDENCY_FAIL, reason);
}
int updated = refundMapper.updateStatus(refundNo, REFUND_WAIT, REFUND_PROCESSING, ...);
if (updated == 0) updated = refundMapper.updateStatus(refundNo, REFUND_FAIL, REFUND_PROCESSING, ...); // FAIL 重试
if (updated == 0) throw new BizException(ErrorCode.CONFLICT, "退款单状态并发冲突");
```

时序保证：

1. refundNo 生成 → REQUIRES_NEW 提交（外层后续回滚也抹不掉）；
2. Feign 调用（含 Feign 重试、售后侧重试）始终携带同一 refundNo，pay 侧 `RefundServiceImpl.refund` 按 refundNo 幂等，不产生第二笔退款；
3. 支付域失败：REQUIRES_NEW 把退款单置 FAIL(40) 并保留 fail_reason，外层异常回滚只回滚售后单推进，人工/定时重试走 REFUND_FAIL → PROCESSING CAS；
4. PROCESSING/SUCCESS 已存在时绝不重复调支付域，只幂等推进到 REFUNDING 等待 REFUND_SUCCESS 事件。

### P2-2 回调端点移除 @Idempotent

ChannelNotifyController.java:32-35 方法上已无任何 `@Idempotent`：

```java
@PostMapping("/pay/{channel}")
public Result<PaymentDTO> payNotify(@PathVariable("channel") String channel,
                                    @RequestBody PayNotifyRequest request) {
```

重复通知（含渠道更换 notifyId 的重发）进入服务层：已 SUCCESS 的支付单走"幂等 ACK"分支（PaymentServiceImpl.java:331-335 `markNotifyDone` 后正常返回），渠道拿到成功响应即停止重发，不再收到 REPEAT_SUBMIT。

### P2-5 重复发起支付：按现状补测试（未改行为）

`lockAndCreate` 当前对**任意状态**的旧单都幂等返回旧单（数据库 `t_pay_order.uk_order_no` 一个订单只允许一行支付单，服务层加分布式锁后没有也无法新建第二行）。已补两个测试锁定现状：

- `createPayment_旧单PAYING_幂等返回旧单不新建`（PaymentServiceImplTest.java:347）——WAIT/PAYING 返回旧单，符合预期；
- `createPayment_旧单CLOSED或FAIL_现状幂等返回旧单不新建`（PaymentServiceImplTest.java:370）——CLOSED/FAIL 也返回旧单，测试注释明确标注与"终态允许重新支付"目标的差距及 UK 根因。

C-3 鉴权未受影响：金额仍由订单应付强制覆盖、userId 仍取登录身份，测试 `createPayment_伪造他人userId支付他人订单_403`（:256）持续守护。

---

## 四、测试明细

全部为纯 Mockito 单测（无 Spring 上下文、不依赖 outbox 表），`OutboxPublisher` 以 `@Mock`/`mock()` 构造器注入，用 `verify(...)` 断言 topic/tag/payload/bizNo。

### shop-pay-service：62 → 69（+7），0 failures / 0 errors / 0 skipped

新增用例：

1. `PaymentServiceImplTest#createPayment_旧单PAYING_幂等返回旧单不新建`（:347，P2-5）
2. `PaymentServiceImplTest#createPayment_旧单CLOSED或FAIL_现状幂等返回旧单不新建`（:370，P2-5 现状留痕）
3. `PaymentServiceImplTest#createPayment_第三方渠道_同事务登记15分钟延时查询outbox`（:396，断言 `publishDelay(PAY_RESULT,"check",PayCheckMessage.class,payNo,900)`，且从未 publish ORDER_PAID）
4. `PaymentServiceImplTest#handleNotify_并发重复回调不同notifyId_仅CAS获胜方登记outbox`（:428，P1-2：markSuccess 先返回 1 再返回 0；两个 topic 均 `times(1)`；`notifyLogMapper.updateResult` 仍 times(2) 保证两次都 ACK）
5. `ChannelNotifyControllerIdempotentTest#渠道回调端点不允许挂Idempotent切面`（反射断言 `payNotify` 上 `@Idempotent == null`）
6. `ReconcileServiceImplTest#handleLong_补单CAS获胜_登记ORDER_PAID_outbox`（断言 tag="recon"、bizNo=payNo、事件金额/渠道流水号正确，差错单置 30）
7. `ReconcileServiceImplTest#handleLong_补单CAS落败_不登记outbox`（markSuccess 返回 0：`outboxPublisher never publish`、流水不补登）

另：`RefundServiceImplTest` 全部用例 mock 置换为 OutboxPublisher；重复 refundNo 幂等用例额外断言 `outboxPublisher never publish`。

### shop-aftersale-service：59 → 62（+3），0 failures / 0 errors / 0 skipped

1. `AftersaleServiceImplTest#audit_调支付域失败_refundNo先落库且FAIL用独立事务保留`（:317，P1-11/P2-8：`InOrder` 验证 insertWaitingInNewTx → payClient.refund → markFailInNewTx("RFIXED...")；外层售后单 updateStatus 从未发生）
2. `AftersaleServiceImplTest#audit_FAIL退款单重试_复用同一refundNo不新建`（:343：WAIT→PROCESSING CAS 返回 0 后回退 FAIL→PROCESSING 返回 1；`noGenerator never nextRefundNo`、`refundStore never insert`）
3. `AftersaleServiceImplTest#audit_PROCESSING退款单_支付域已受理_不重复调用仅幂等推进`（:379：`payClient never refund`，售后单照常推进 REFUNDING）

另：`AftersaleMqServiceImplTest` 13 参构造置换 outbox，运费险用例验证 `publishDelay(...,72h)`，"每单一次"用例验证从未登记延时。

执行命令：

```bash
set +u; source ~/.sdkman/bin/sdkman-init.sh; set -u
mvn -o -q -pl shop-pay-service test
mvn -o -q -pl shop-aftersale-service test
# 数字按 target/surefire-reports/TEST-*.xml 的 tests/failures/errors/skipped 聚合
```

---

## 五、残留风险与后续建议

1. **P2-5 终态旧单仍无法重新支付（最大缺口，本次按约束只留痕未修）**
   `t_pay_order.uk_order_no` 限定一个订单仅一行支付单，`lockAndCreate` 对 CLOSED/FAIL/超时旧单也只能返回旧单；要真正支持"终态后重新发起支付"，需产品确认后改表（去掉/改造 uk_order_no，如改为 uk_order_no + 活跃态，或新建支付单并作废旧单）并同步改造查询与对账关联，属于 sql/ + 流程变更，超出本次"不改 sql/"授权。已有测试 `createPayment_旧单CLOSED或FAIL_现状幂等返回旧单不新建` 固化现状并注释根因，防止后续误判为已支持。

2. **outbox 为至少一次投递，幂等责任在消费方**
   relay 2s 轮询 + 发送端重试可能重复投递，所有消费方必须按 bizNo（payNo / refundNo / aftersaleNo）去重。本次核对：售后侧 `mq_consume_log.insertIgnore` 已幂等；订单/营销/清算等其他域的消费幂等不在本模块范围内，建议联调时统一回归。

3. **REQUIRES_NEW 的 FAIL 保留路径自身失败的极端情形**
   若 `markFailInNewTx` 提交失败（如 DB 宕机），异常会覆盖原 BizException 向上抛，退款单停留 WAIT(10)；这是安全方向（重试/定时任务可重新发起，pay 侧 refundNo 幂等兜底），但需要依赖差错扫描兜底 WAIT 滞留单，建议后续补一个扫描 WAIT 超时单的补偿任务。

4. **支付域调用点在 Feign 之前提交退款单（P1-11 的固有窗口）**
   refundNo 独立提交后、Feign 返回前若进程崩溃，退款单停留 WAIT；重新流转时复用同号调用 pay 侧（幂等返回已存在的退款单），不会多退，但该单在售后列表会短暂可见，属可接受的最终一致窗口。

5. **消息顺序不在本次保证范围（P1-9 类问题）**
   同一支付单若极端情况下补偿与回调交叉，ORDER_PAID 由 CAS 保证只登记一次，不存在重复；但不同实体间事件无全局顺序保证，下游不应依赖事件到达顺序。

6. 两模块 src 下已无任何 `MqProducer`/`mqProducer` 引用（grep 归零）；框架侧 `MqProducer` 保留给 OutboxRelayJob `sendRaw` 使用，未改动。
