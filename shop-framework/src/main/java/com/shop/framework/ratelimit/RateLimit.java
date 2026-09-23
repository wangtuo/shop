package com.shop.framework.ratelimit;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 滑动窗口限流（design 10.3 防刷单）：基于 Redis ZSET 时间窗，
 * 窗口内超过 permits 次调用直接抛 {@code TOO_MANY_REQUESTS}，不进入业务方法。
 *
 * <p>key 支持 SpEL，上下文内置两个变量：{@code #userId}（未登录为 null）、{@code #clientIp}。
 * key 留空时默认按「登录用户 → 客户端 IP」自动取维度。典型：领券、秒杀下单、提现。</p>
 *
 * <p>Redis 故障策略见 {@link RedisFailurePolicy}（R-B4 矩阵）：默认 {@link RedisFailurePolicy#FAIL_CLOSE}，
 * 与现状「Redisson 异常直接传播、请求不进入业务方法」的拒绝语义一致，仅把原生异常统一包装为
 * {@link BizException}（默认码 {@code 10007}），便于映射 503 与分类告警。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** key 前缀，建议 域:动作，如 coupon:claim */
    String prefix();

    /** SpEL 表达式，如 "#userId + ':' + #skuId"；留空按 userId（登录）或 clientIp（匿名）限流 */
    String key() default "";

    /** 窗口内允许的请求次数（含边界：第 permits+1 次起拒绝） */
    long permits();

    /** 窗口长度，秒；若设置了 {@link #windowConfig()}，以配置值为准。 */
    long windowSeconds();

    /**
     * 可选：permits 的外部化配置键（如 {@code shop.ratelimit.auth.login.permits}）。
     * 配置存在时覆盖 {@link #permits()}，便于运维按环境（NAT 出口/压测/大促）调参，
     * 无需改码重发版；配置缺失时回退注解默认值。
     */
    String permitsConfig() default "";

    /** 可选：窗口秒数的外部化配置键（如 {@code shop.ratelimit.auth.login.window-seconds}）。 */
    String windowConfig() default "";

    /** 被限流时的提示语 */
    String message() default "操作过于频繁，请稍后再试";

    /**
     * Redis 故障策略（R-B4 七类路径矩阵）。默认 {@link RedisFailurePolicy#FAIL_CLOSE}，
     * 即 Redis 异常时拒绝并抛 {@link BizException}；读缓存类资源可显式声明
     * {@link RedisFailurePolicy#FAIL_OPEN} 降级放行回源。
     */
    RedisFailurePolicy failurePolicy() default RedisFailurePolicy.FAIL_CLOSE;

    /**
     * Redis 故障且策略为 fail-closed 时抛出的错误码，默认 10007（TOO_MANY_REQUESTS）。
     * 需要被上游识别为「依赖故障」（映射 503、分类告警）的资源可配
     * {@code ErrorCode.DEPENDENCY_FAIL}（10008）。
     */
    ErrorCode redisDownCode() default ErrorCode.TOO_MANY_REQUESTS;

    /** Redis 故障拒绝时的提示语；留空使用 {@link #redisDownCode()} 的枚举默认文案。 */
    String redisDownMessage() default "";
}

