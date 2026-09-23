package com.shop.framework.ratelimit;

/**
 * Redis 故障策略矩阵（R-B4，冻结到 RUNBOOK/评审 checklist）。
 *
 * <p>限流等「保护性资源」在 Redis 不可用时必须明确选择拒绝或降级，禁止默认放行漏限，
 * 也禁止无限等待拖垮线程：
 *
 * <table>
 *   <caption>R-B4 七类路径策略</caption>
 *   <tr><th>路径</th><th>策略</th><th>枚举</th></tr>
 *   <tr><td>网关桶 / 服务层 @RateLimit</td><td>必须 fail-closed（拒绝优于漏限）</td><td>{@link #FAIL_CLOSE}</td></tr>
 *   <tr><td>幂等切面（IdempotentAspect）</td><td>必须 fail-closed（无幂等窗口放行=重复扣款）</td><td>{@link #FAIL_CLOSE}</td></tr>
 *   <tr><td>分布式锁（支付/退款/清算/提现/库存/签到）</td><td>必须 fail-closed（无锁不互斥）</td><td>{@link #FAIL_CLOSE}</td></tr>
 *   <tr><td>秒杀库存</td><td>fail-open 到 DB CAS（业务侧实现，非本注解资源）</td><td>{@link #FAIL_OPEN}</td></tr>
 *   <tr><td>订单号生成</td><td>fail-closed→补本地号段（业务卡执行）</td><td>{@link #FAIL_CLOSE}</td></tr>
 *   <tr><td>商品读缓存（SpuDetailCache 等）</td><td>允许 fail-open 回源</td><td>{@link #FAIL_OPEN}</td></tr>
 *   <tr><td>账户/资金余额读</td><td>禁止 stale 缓存参与决策：只回源或拒绝</td><td>{@link #FAIL_CLOSE_NO_STALE}</td></tr>
 * </table>
 *
 * <p>本枚举只在 ratelimit 包表达矩阵；幂等/锁/缓存路径的具体实现分别在对应包，
 * 不得在限流包里越权改造。{@link #FAIL_CLOSE_NO_STALE} 与 {@link #FAIL_CLOSE} 动作相同
 * （均拒绝），区别是评审/告警语义：资金读不允许后续用「降级读缓存」方式翻牌。
 */
public enum RedisFailurePolicy {

    /** Redis 异常时拒绝请求（抛 {@code BizException}），不放行、不等待。默认。 */
    FAIL_CLOSE,

    /** Redis 异常时降级放行（读缓存类回源）；严禁用于写链路/互斥/幂等资源。 */
    FAIL_OPEN,

    /** 资金/余额读取：Redis 异常时拒绝，且明确禁止以 stale 缓存值参与资金决策。 */
    FAIL_CLOSE_NO_STALE
}
