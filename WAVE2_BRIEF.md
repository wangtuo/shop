# Wave-2 服务实现任务书（通用部分，各代理必读）

你负责一个 Spring Boot 业务服务的**完整生产级实现**。工作目录 /Users/bytedance/bits/shop。

## 0. 必读（按顺序）

1. `CONTRACTS.md` —— 工程铁律、状态码、事件契约
2. `API_CONTRACTS.md` —— shop-api 中你要实现/调用的 Feign 与事件（以源码为准：`shop-api/src/main/java/com/shop/api/**`）
3. `design.md` 中你被分配的章节 —— 每条规则都必须落地，不允许 TODO
4. 框架能力（直接用，不要重复造）：
   - `com.shop.framework.web.UserController` 模式参考：`UserContext.getUserId()`
   - `com.shop.framework.id.IdGenerator` 雪花 ID
   - `com.shop.framework.lock.DistributedLockTemplate`
   - `com.shop.framework.idempotent.@Idempotent`
   - `com.shop.framework.mq.MqProducer` / `MqListener<T>`
   - `com.shop.common.util.MoneyUtils`（金额分摊）
   - `com.shop.common.exception.BizException/ErrorCode`

## 1. 代码组织（服务内）

```
com.shop.<domain>.<feature>.controller   C 端/商户端 HTTP（/xxx），内部接口 controller 必须放在 /inner/<domain> 下并与 Client 的 @*Mapping 路径逐字一致
com.shop.<domain>.<feature>.service(.impl)
com.shop.<domain>.<feature>.entity / mapper / dto / enums
com.shop.<domain>.<feature>.mq           MqListener 实现 + 消费幂等
com.shop.<domain>.<feature>.job          @Scheduled + @SchedulerLock
com.shop.<domain>.config
```
Mapper 必须加 `@org.apache.ibatis.annotations.Mapper`。XML（仅复杂 SQL）放 resources/mapper。

## 2. 数据库

- DDL 写 `sql/<domain>/V2__<domain>.sql`，文件开头：
  `CREATE DATABASE IF NOT EXISTS shop_<domain> DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; USE shop_<domain>;`
- 表 `t_<domain>_<name>`：id BIGINT 主键、create_time/update_time DATETIME DEFAULT CURRENT_TIMESTAMP[ ON UPDATE]、deleted TINYINT DEFAULT 0、并发敏感表 version INT DEFAULT 0。
- 每个业务单号字段建唯一索引（防重）；高频查询字段建普通索引；金额 BIGINT 存分。
- 每个 MQ 消费者建消费流水表 `t_<domain>_mq_consume(id,event_id VARCHAR(64) UNIQUE,topic,biz_no,status,create_time)`，消费处理与流水插入在同一事务；重复 eventId 直接 ACK。
- 中文 COMMENT 写全。

## 3. 关键模式（必须照做）

### 3.1 Controller
```java
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    @PostMapping
    public Result<String> create(@Valid @RequestBody CreateOrderRequest req) {
        return Result.success(orderService.create(req, UserContext.getUserId()));
    }
}
```
内部接口：`@RestController @RequestMapping("/inner/user")`，路径/HTTP 方法必须打开对应 Client 源码逐字对齐。

### 3.2 状态机
- 状态流转集中在一个 `XxxStateMachine`（无状态 Spring 组件），非法跳转抛 BizException(ORDER_STATUS_ERROR 等)；必须有单测覆盖全部合法/非法边。
- DB 更新带状态条件：`UPDATE ... SET status=? WHERE id=? AND status=?`，影响行数 0 即并发冲突。

### 3.3 防超卖/防透支
库存、余额一律条件更新：`UPDATE t_... SET available=available-#{qty} WHERE sku_id=#{id} AND available>=#{qty}`，影响 0 行抛 STOCK_NOT_ENOUGH/POINTS_NOT_ENOUGH；热点再叠加 Redisson 锁。

### 3.4 MQ 消费者
```java
@Component
@RequiredArgsConstructor
public class XxxListener implements MqListener<SomeEvent> {
    public String topic(){ return MqTopics.ORDER_PAID; }
    public String consumerGroup(){ return "cg_<domain>_<action>"; }
    public Class<SomeEvent> type(){ return SomeEvent.class; }
    public void onMessage(SomeEvent e){
        // 1) INSERT IGNORE 消费流水(event_id)，重复直接返回
        // 2) 业务条件更新保证幂等
        // 3) 抛异常由 Broker 重试
    }
}
```

### 3.5 超时双保险
关键超时：发 MQ 延时消息（MqProducer.sendDelay + MqTopics.DELAY_*）+ `@Scheduled` 扫描（`@SchedulerLock(name="...", lockAtMostFor="PT5M", lockAtLeastFor="PT1M")`），两边都做状态条件更新，结果天然幂等。

### 3.6 Feign 失败
注入 api 中的 Client 直接调用；跨域写优先用 MQ。Feign 兜底由后续专项统一加，你的代码要把 Feign 调用放在业务层方法内并处理 BizException 语义（失败抛业务异常触发回滚/补偿）。

## 4. 测试（JUnit5 + Mockito，禁止连中间件）

- 纯单元测试放 `src/test/java`，至少覆盖：
  - 规则计算类：全部 design 规则边界（金额边界、等级边界、天数边界、上限边界）
  - 状态机：全部合法边 + 典型非法边
  - Service：成功路径 + 失败/并发路径（mock mapper/feign，用 when/verify）
- 测试命名 `方法_场景_期望`；金额断言注意分。
- 目标：核心规则类/状态机类行覆盖 ≥70%。

## 5. 完成标准（你必须自己执行）

1. `source ~/.sdkman/bin/sdkman-init.sh && mvn -q -pl shop-<domain>-service -am test` 全绿；
2. SQL 语法人工检查（MySQL 8）；
3. 不许改：shop-common、shop-framework、shop-gateway、pom.xml（父）、其他域目录、shop-api（如发现契约缺字段/方法错误，**不要自己改 shop-api**，在最终报告里列出"契约缺口"，由架构师统一修补）；
4. 报告：文件清单、表清单、HTTP 接口清单、消费的/发出的事件清单、覆盖的 design 规则点、遗留契约缺口。
