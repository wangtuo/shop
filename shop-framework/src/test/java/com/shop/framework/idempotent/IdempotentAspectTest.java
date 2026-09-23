package com.shop.framework.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.core.Ordered;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等切面单测：占位/回放/失败删除语义，以及 H3——切面必须在事务拦截器外侧，
 * 保证 proceed 返回即已提交、提交失败一定能删掉占位。
 */
class IdempotentAspectTest {

    /** 测试目标：方法上的注解与参数名供 SpEL/反射读取（Maven 默认 -g 含局部变量表）。 */
    static class Target {
        @Idempotent(prefix = "order", key = "#userId + ':' + #clientToken", ttlSeconds = 60)
        public String create(String userId, String clientToken) {
            return "O1";
        }

        @Idempotent(prefix = "order", key = "#clientToken", ttlSeconds = 60)
        public String createBlankKey(String userId, String clientToken) {
            return "X";
        }
    }

    private RedissonClient client;
    private RBucket<String> bucket;
    private IdempotentAspect aspect;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String KEY = "idem:order:u1:ct1";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(RedissonClient.class);
        bucket = mock(RBucket.class);
        when(client.<String>getBucket(KEY)).thenReturn(bucket);
        aspect = new IdempotentAspect(client, objectMapper);
    }

    private ProceedingJoinPoint pjp(String methodName, Object... args) throws Throwable {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = String.class;
        }
        Method method = Target.class.getMethod(methodName, types);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    @Test
    void 首次成功_占位后写回结果JSON() throws Throwable {
        when(bucket.setIfAbsent(IdempotentAspect.INFLIGHT, Duration.ofSeconds(60))).thenReturn(true);
        ProceedingJoinPoint pjp = pjp("create", "u1", "ct1");
        when(pjp.proceed()).thenReturn("O1");

        Object result = aspect.around(pjp);

        assertEquals("O1", result);
        verify(bucket).set("\"O1\"", Duration.ofSeconds(60));
    }

    @Test
    void 业务抛异常_删除占位并原样抛出() throws Throwable {
        when(bucket.setIfAbsent(IdempotentAspect.INFLIGHT, Duration.ofSeconds(60))).thenReturn(true);
        ProceedingJoinPoint pjp = pjp("create", "u1", "ct1");
        when(pjp.proceed()).thenThrow(new IllegalStateException("commit boom"));

        assertThrows(IllegalStateException.class, () -> aspect.around(pjp));
        verify(bucket).delete();
        verify(bucket, never()).set(any(String.class), any(Duration.class));
    }

    @Test
    void 重复请求_缓存已落结果_直接回放不再进业务() throws Throwable {
        when(bucket.setIfAbsent(IdempotentAspect.INFLIGHT, Duration.ofSeconds(60))).thenReturn(false);
        when(bucket.get()).thenReturn("\"O9\"");
        ProceedingJoinPoint pjp = pjp("create", "u1", "ct1");

        Object result = aspect.around(pjp);

        assertEquals("O9", result);
        verify(pjp, never()).proceed();
    }

    @Test
    void 重复请求_首请求已失败删标记_报重复提交允许重试() throws Throwable {
        when(bucket.setIfAbsent(IdempotentAspect.INFLIGHT, Duration.ofSeconds(60))).thenReturn(false);
        when(bucket.get()).thenReturn(null);
        ProceedingJoinPoint pjp = pjp("create", "u1", "ct1");

        assertThrows(BizException.class, () -> aspect.around(pjp));
        verify(pjp, never()).proceed();
    }

    @Test
    void 幂等键SpEL取值为空_快速失败且不写Redis() throws Throwable {
        RedissonClient fresh = mock(RedissonClient.class);
        IdempotentAspect a = new IdempotentAspect(fresh, objectMapper);

        assertThrows(BizException.class, () -> a.around(pjp("createBlankKey", "u1", null)));
        verify(fresh, never()).getBucket(any(String.class));
    }

    @Test
    void 切面顺序_必须在事务拦截器外侧_先于LOWEST_PRECEDENCE() {
        // H3：order 数值越小优先级越高、越在外层。默认 @Transactional 顾问为 LOWEST_PRECEDENCE；
        // 本切面必须严格更早，否则会在事务提交前缓存「成功」结果（提交失败即脏回放）。
        assertTrue(aspect.getOrder() < Ordered.LOWEST_PRECEDENCE,
                "幂等切面必须位于事务拦截器外侧");
        assertEquals(Ordered.HIGHEST_PRECEDENCE, aspect.getOrder());
    }
}
