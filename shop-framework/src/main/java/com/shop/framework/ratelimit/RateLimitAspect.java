package com.shop.framework.ratelimit;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.metrics.BizMetrics;
import com.shop.framework.web.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.env.Environment;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * {@link RateLimit} 切面：SpEL 取维度值（上下文内置 #userId / #clientIp），
 * key 留空按登录用户（优先）或客户端 IP 自动维度；命中上限抛 TOO_MANY_REQUESTS。
 * 与 {@link com.shop.framework.idempotent.IdempotentAspect} 同款 SpEL 约定，
 * 参数名依赖编译开关 -parameters（根 pom 已全局开启）。
 *
 * <p>切点写死注解全限定名、不使用 {@code @annotation(rateLimit)} 形参绑定：绑定型 Advice
 * 的 JoinPointMatch 靠 ExposeInvocationInterceptor 暂存当前 MethodInvocation 才能解析，
 * 一旦本切面（或同一链上的其他顾问）经 {@code @Order} 排到 Expose 之前，且调用嵌套在
 * 外层代理内（如 Controller 限流包裹 Service 幂等），就会抛
 * 「JoinPointMatch was NOT bound in invocation」。静态切点不依赖该绑定，注解反射获取，
 * 可与任意 order 共存（同 {@link com.shop.framework.idempotent.IdempotentAspect} 的处理）。
 */
@Aspect
@Component
public class RateLimitAspect {

    private static final String UNKNOWN_IP = "unknown";

    private final RateLimiter rateLimiter;
    private final Environment environment;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    /** O6：限流命中与 Redis 故障计数；字段注入可空，既有 2 参构造的单测不受影响。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BizMetrics bizMetrics;

    public RateLimitAspect(RateLimiter rateLimiter, Environment environment) {
        this.rateLimiter = rateLimiter;
        this.environment = environment;
    }

    @Around("@annotation(com.shop.framework.ratelimit.RateLimit)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        // 注解实例反射获取（不使用 @annotation 形参绑定），原因见类注释的 Expose 排序陷阱。
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : null;
        Method mostSpecific = targetClass != null
                ? AopUtils.getMostSpecificMethod(method, targetClass) : method;
        RateLimit rateLimit = AnnotatedElementUtils.findMergedAnnotation(mostSpecific, RateLimit.class);
        if (rateLimit == null) {
            throw new IllegalStateException("限流切点命中但未解析到 @RateLimit 注解: " + method);
        }

        String dimension = resolveDimension(rateLimit, method, pjp.getArgs());
        String redisKey = "rl:" + rateLimit.prefix() + ":" + dimension;
        long permits = resolveLong(rateLimit.permitsConfig(), rateLimit.permits());
        long windowSeconds = resolveLong(rateLimit.windowConfig(), rateLimit.windowSeconds());
        RateLimiter.Outcome outcome = rateLimiter.tryAcquireWithPolicy(redisKey, permits,
                Duration.ofSeconds(windowSeconds), rateLimit.failurePolicy());
        String service = environment.getProperty("spring.application.name", "unknown");
        switch (outcome) {
            case REJECTED_LIMIT -> {
                if (bizMetrics != null) {
                    bizMetrics.ratelimitHit(service, rateLimit.prefix());
                }
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, rateLimit.message());
            }
            // R-B4：Redis 故障 fail-closed（含资金读 NO_STALE）→ 可配错误码，默认 10007；
            // 统一包装为 BizException，可被全局处理器映射语义化状态并分类告警（不再裸 10009）。
            case REDIS_DOWN_DENY -> {
                if (bizMetrics != null) {
                    bizMetrics.ratelimitHit(service, rateLimit.prefix());
                    bizMetrics.redisFailure("ratelimit");
                }
                String downMessage = rateLimit.redisDownMessage().isBlank()
                        ? rateLimit.redisDownCode().getMessage()
                        : rateLimit.redisDownMessage();
                throw new BizException(rateLimit.redisDownCode(), downMessage);
            }
            // FAIL_OPEN（仅读缓存类显式声明）：降级放行，照常进入业务方法回源。
            case ALLOWED, REDIS_DOWN_ALLOW -> {
                if (outcome == RateLimiter.Outcome.REDIS_DOWN_ALLOW && bizMetrics != null) {
                    bizMetrics.redisFailure("ratelimit");
                }
                // 放行
            }
        }
        return pjp.proceed();
    }

    /** 外部配置键存在且为正整数时覆盖注解默认值，否则回退注解值。 */
    private long resolveLong(String configKey, long fallback) {
        if (configKey != null && !configKey.isBlank()) {
            String value = environment.getProperty(configKey);
            if (value != null && !value.isBlank()) {
                try {
                    long parsed = Long.parseLong(value.trim());
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // 配置非法时回退默认值，避免错误配置阻断业务
                }
            }
        }
        return fallback;
    }

    private String resolveDimension(RateLimit rateLimit, Method method, Object[] args) {
        EvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = discoverer.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        context.setVariable("userId", UserContext.getUserIdOrNull());
        context.setVariable("clientIp", clientIp());

        if (!rateLimit.key().isBlank()) {
            Expression expression = parser.parseExpression(rateLimit.key());
            Object value = expression.getValue(context);
            if (value == null || value.toString().isBlank()) {
                // 与幂等切面同理：SpEL 取空绝不能退化为全局共用一个限流键
                throw new BizException(ErrorCode.PARAM_INVALID, "限流键取值为空，请检查请求参数");
            }
            return value.toString();
        }
        Long userId = UserContext.getUserIdOrNull();
        return userId != null ? "u:" + userId : "ip:" + clientIp();
    }

    /**
     * 取直连对端 IP：优先 X-Forwarded-For 首个网段（网关已做头清洗/追加，
     * 外部无法在 XFF 中伪造任意来源跳过计数），其次 X-Real-IP，最后 servlet 对端地址。
     */
    static String clientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return UNKNOWN_IP;
            }
            HttpServletRequest request = attrs.getRequest();
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String first = xff.split(",")[0].trim();
                if (!first.isEmpty() && !UNKNOWN_IP.equalsIgnoreCase(first)) {
                    return first;
                }
            }
            String real = request.getHeader("X-Real-IP");
            if (real != null && !real.isBlank() && !UNKNOWN_IP.equalsIgnoreCase(real)) {
                return real.trim();
            }
            return request.getRemoteAddr() != null ? request.getRemoteAddr() : UNKNOWN_IP;
        } catch (Exception e) {
            return UNKNOWN_IP;
        }
    }
}
