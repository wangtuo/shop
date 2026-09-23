/**
 * 事务模板组件（R-B5 + Z1）：把"事务外预取/远程调用、短事务只落库"的正向样板固化为可复用组件，
 * 供支付、退款、清算、对账批处理等高风险链路引用。
 *
 * <h2>五条事务硬规约（评审 checklist）</h2>
 * <ol>
 *   <li><b>回滚语义完整</b>：所有声明式 {@code @Transactional} 必须 {@code rollbackFor = Exception.class}，
 *       只读方法标 {@code readOnly=true}，批量/长事务显式 {@code timeout}（如 10s），
 *       消除 checked exception 不回滚缺口（Z1）。本模板以编程式事务等价保证 checked exception 也回滚。</li>
 *   <li><b>锁包事务，事务不包锁</b>：{@code tryLock} 在事务外执行，{@code finally} 解锁发生在事务提交之后
 *       （参照 marketing MarketingAppService：事务外 tryLock → 代理调用事务方法 → 事务外 unlock）。
 *       禁止在事务方法内获取分布式锁，避免锁等待计入事务持有时长、锁在回滚前泄露。</li>
 *   <li><b>事务内只允许 DB 与 outbox 登记</b>：禁止 Feign / 直连 HTTP / MQ 直发 / Redis 长操作。
 *       远程数据一律事务外预取；必须先落状态的，先在短事务写 PROCESSING + outbox 并提交，
 *       提交后再做远程调用，最后用独立短事务 CAS 终态（参照 order OrderCreateServiceImpl/OrderPersister）。</li>
 *   <li><b>批处理逐元素 REQUIRES_NEW</b>：使用 {@link com.shop.framework.tx.RequiresNewBatchExecutor}，
 *       单条失败不污染外层（不被标记 rollback-only），失败项进失败列表交由重试 Job 接管。
 *       REQUIRES_NEW 必须经独立 Bean/代理调用，禁止同类 this 自调用（用
 *       {@link com.shop.framework.tx.SelfInvoker} 走代理）。</li>
 *   <li><b>MQ 消费事务同规约</b>：事件体已携带的数据不回查（P3-1）；必须回查的在事务外先查完，
 *       再开短事务只做 DB + outbox（settlement PaymentSucceededListener、aftersale claimInsurance 同此模式）。</li>
 * </ol>
 */
package com.shop.framework.tx;
