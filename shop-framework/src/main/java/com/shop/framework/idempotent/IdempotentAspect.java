package com.shop.framework.idempotent;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.metrics.BizMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 幂等切面：基于 Redis SET NX EX。
 *
 * <p><b>请求回放语义（生产级幂等）：</b>首个请求落 {@code __INFLIGHT__} 占位并执行业务；
 * 成功后把返回值 JSON 覆写回同一 key（带 TTL）。窗口内的重复请求（含与首请求并发、
 * 在首请求执行期间到达的请求）等待首请求完成，然后<b>原样回放同一返回值</b>
 * （如下单返回同一个 orderNo），而不是简单报「重复提交」。业务执行失败立即删除标记，
 * 允许快速重试。
 *
 * <p>等待采用短间隔轮询（单实例本地等待，跨实例经由 Redis 可见）：并发双发的后到请求
 * 通常在数毫秒内拿到结果；超过 {@link #MAX_WAIT_SECONDS} 兜底失败，避免悬挂。
 *
 * <p><b>切面顺序（H3）：</b>{@code @Order(HIGHEST_PRECEDENCE)} 保证本切面位于
 * {@code @Transactional} 拦截器（默认 LOWEST_PRECEDENCE）<b>外侧</b>。这样
 * {@code pjp.proceed()} 返回时业务事务已经提交，失败回滚时异常也会先穿过本切面删除
 * 幂等标记；若顺序颠倒，切面会在事务提交前把返回值写入 Redis，一旦提交失败
 * （死锁/唯一键冲突在 commit 期抛出），重复请求将回放一个从未落库的「成功」结果。
 *
 * <p><b>禁止使用参数绑定型切点（{@code @annotation(idempotent)} 形参写法）：</b>本切面
 * order=HIGHEST_PRECEDENCE，在 AspectJ 顾问排序中会排到 ExposeInvocationInterceptor
 * （非 Ordered，等效 LOWEST_PRECEDENCE）<b>之前</b>。绑定型 Advice 的 JoinPointMatch
 * 依赖 Expose 暂存的当前 MethodInvocation 才能完成注解实参绑定；Expose 不在链首且本次
 * 调用嵌套于外层代理（如 Controller 上的限流切面）时，暂存键校验方法不匹配而缺失，抛
 * 「Required to bind 2 arguments, but only bound 1 (JoinPointMatch was NOT bound)」。
 * 故切点写死注解全限定名（静态匹配、isRuntime=false），注解实例改由方法反射获取，
 * 与 SentinelResourceAspect 的成熟写法一致。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IdempotentAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    /** 首请求在途占位值；重复请求见到该值即等待。public 以便跨模块单测断言。 */
    public static final String INFLIGHT = "__INFLIGHT__";
    /** 并发重复请求等待首请求完成的最长时间。 */
    private static final long MAX_WAIT_SECONDS = 30L;
    private static final long POLL_MILLIS = 20L;

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final DefaultParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();

    /** O6：Redis 故障计数；字段注入可空，既有 2 参构造的单测不受影响。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BizMetrics bizMetrics;

    public IdempotentAspect(RedissonClient redissonClient, ObjectMapper objectMapper) {
        this.redissonClient = redissonClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 与类上 {@code @Order(HIGHEST_PRECEDENCE)} 保持同一取值（显式实现 Ordered
     * 同时便于单测断言顺序约束）：必须先于事务拦截器（LOWEST_PRECEDENCE）执行。
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Around("@annotation(com.shop.framework.idempotent.Idempotent)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        // 注解实例反射获取（不使用 @annotation 形参绑定），原因见类注释中的排序陷阱说明。
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : null;
        Method mostSpecific = targetClass != null
                ? AopUtils.getMostSpecificMethod(method, targetClass) : method;
        Idempotent idempotent = AnnotatedElementUtils.findMergedAnnotation(mostSpecific, Idempotent.class);
        if (idempotent == null) {
            throw new IllegalStateException("幂等切点命中但未解析到 @Idempotent 注解: " + method);
        }
        EvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = discoverer.getParameterNames(method);
        Object[] args = pjp.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        Expression expression = parser.parseExpression(idempotent.key());
        Object keyValue = expression.getValue(context);
        // 防呆：SpEL 解析出 null/空白（如缺参、参数名丢失）时，绝不能退化成
        // 「所有请求共用 idem:prefix:null 一个键」的全局互斥（P2-1）。
        if (keyValue == null || keyValue.toString().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "幂等键取值为空，请检查请求参数");
        }
        String redisKey = "idem:" + idempotent.prefix() + ":" + keyValue;
        RBucket<String> bucket = redissonClient.getBucket(redisKey);

        boolean firstTime;
        try {
            firstTime = bucket.setIfAbsent(INFLIGHT, Duration.ofSeconds(idempotent.ttlSeconds()));
        } catch (RuntimeException e) {
            // O6：幂等键 Redis 不可用计数（fail-closed 语义由异常向上传播保证）。
            if (bizMetrics != null) {
                bizMetrics.redisFailure("idempotent");
            }
            throw e;
        }
        if (!firstTime) {
            // 重复提交：等待首请求落结果并原样回放（并发双发返回同一订单号）
            return awaitAndReplay(bucket, method, idempotent, redisKey);
        }
        try {
            Object result = pjp.proceed();
            // 成功后用返回值 JSON 覆写占位（TTL 重新计时）；返回 null 也缓存空结果标记
            bucket.set(toStoredJson(result), Duration.ofSeconds(idempotent.ttlSeconds()));
            return result;
        } catch (Throwable t) {
            bucket.delete();
            throw t;
        }
    }

    private Object awaitAndReplay(RBucket<String> bucket, Method method, Idempotent idempotent,
                                  String redisKey) throws Throwable {
        long deadline = System.currentTimeMillis() + MAX_WAIT_SECONDS * 1000L;
        String cached;
        while (true) {
            cached = bucket.get();
            if (cached != null && !INFLIGHT.equals(cached)) {
                return fromStoredJson(cached, method);
            }
            if (cached == null) {
                // 首请求失败已删标记（允许重试），或 TTL 刚过期：按重复提交处理
                throw new BizException(ErrorCode.REPEAT_SUBMIT, idempotent.message());
            }
            if (System.currentTimeMillis() >= deadline) {
                log.warn("幂等等待首请求完成超时 key={}（{}s）", redisKey, MAX_WAIT_SECONDS);
                throw new BizException(ErrorCode.REPEAT_SUBMIT, idempotent.message());
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    private String toStoredJson(Object result) {
        try {
            // null 包装成显式 null 字面量；读到它时按方法返回类型反序列化为 null
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            // 缓存失败不影响业务结果：退化为占位长期存在，重复请求会命中等待超时；
            // 记录错误便于定位（正常 DTO 均可被框架 ObjectMapper 序列化）
            log.error("幂等返回值序列化失败，重复请求将无法回放", e);
            return INFLIGHT;
        }
    }

    private Object fromStoredJson(String json, Method method) {
        try {
            if ("null".equals(json)) {
                return null;
            }
            JavaType javaType = objectMapper.getTypeFactory()
                    .constructType(method.getGenericReturnType());
            return objectMapper.readValue(json, javaType);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "幂等结果回放失败", e);
        }
    }
}
