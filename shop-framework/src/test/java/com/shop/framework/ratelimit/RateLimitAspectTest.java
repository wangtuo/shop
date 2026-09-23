package com.shop.framework.ratelimit;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RateLimitAspect} 维度解析、拒绝/放行语义（M-2）与 R-B4 Redis 故障策略。
 */
class RateLimitAspectTest {

    @SuppressWarnings("unused")
    static class Target {
        @RateLimit(prefix = "coupon:claim", permits = 3, windowSeconds = 60)
        public void claim(Long couponId) {
        }

        @RateLimit(prefix = "seckill", key = "#userId + ':' + #activityId", permits = 1, windowSeconds = 10)
        public void rush(Long activityId) {
        }

        @RateLimit(prefix = "bad", key = "#nullable", permits = 1, windowSeconds = 10)
        public void blankKey(String nullable) {
        }

        // 资金类：Redis 故障 fail-closed 且错误码语义化为 DEPENDENCY_FAIL（10008）
        @RateLimit(prefix = "funds:withdraw", permits = 1, windowSeconds = 10,
                redisDownCode = ErrorCode.DEPENDENCY_FAIL, redisDownMessage = "资金服务暂不可用")
        public void withdraw(Long accountId) {
        }

        // 读缓存类：Redis 故障显式 fail-open 降级回源
        @RateLimit(prefix = "goods:detail", permits = 10, windowSeconds = 60,
                failurePolicy = RedisFailurePolicy.FAIL_OPEN)
        public void browse(Long goodsId) {
        }
    }

    private RateLimiter rateLimiter;
    private RateLimitAspect aspect;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        // 空 MockEnvironment：getProperty 恒为 null，注解默认值生效
        aspect = new RateLimitAspect(rateLimiter, new org.springframework.mock.env.MockEnvironment());
        httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("10.0.0.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private ProceedingJoinPoint pjp(String methodName, Class<?> argType, Object arg) throws Exception {
        Method method = Target.class.getMethod(methodName, argType);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getArgs()).thenReturn(new Object[]{arg});
        return pjp;
    }

    @Test
    void 已登录_默认键按用户维度_放行时执行业务() throws Throwable {
        LoginUser user = new LoginUser();
        user.setUserId(777L);
        UserContext.set(user);
        when(rateLimiter.tryAcquireWithPolicy(eq("rl:coupon:claim:u:777"), eq(3L), any(),
                eq(RedisFailurePolicy.FAIL_CLOSE))).thenReturn(RateLimiter.Outcome.ALLOWED);

        aspect.around(pjp("claim", Long.class, 9L));

        verify(rateLimiter).tryAcquireWithPolicy(eq("rl:coupon:claim:u:777"), eq(3L), any(),
                eq(RedisFailurePolicy.FAIL_CLOSE));
    }

    @Test
    void 匿名_默认键回退客户端IP() throws Throwable {
        when(rateLimiter.tryAcquireWithPolicy(eq("rl:coupon:claim:ip:10.0.0.9"), eq(3L), any(), any()))
                .thenReturn(RateLimiter.Outcome.ALLOWED);

        aspect.around(pjp("claim", Long.class, 9L));

        verify(rateLimiter).tryAcquireWithPolicy(eq("rl:coupon:claim:ip:10.0.0.9"), eq(3L), any(), any());
    }

    @Test
    void xff首个网段作为客户端IP() throws Throwable {
        httpRequest.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        when(rateLimiter.tryAcquireWithPolicy(contains("203.0.113.7"), eq(3L), any(), any()))
                .thenReturn(RateLimiter.Outcome.ALLOWED);

        aspect.around(pjp("claim", Long.class, 9L));

        verify(rateLimiter).tryAcquireWithPolicy(contains("203.0.113.7"), eq(3L), any(), any());
    }

    @Test
    void 显式SpEL键_含登录用户与参数() throws Throwable {
        LoginUser user = new LoginUser();
        user.setUserId(42L);
        UserContext.set(user);
        when(rateLimiter.tryAcquireWithPolicy(eq("rl:seckill:42:55"), eq(1L), any(), any()))
                .thenReturn(RateLimiter.Outcome.ALLOWED);

        aspect.around(pjp("rush", Long.class, 55L));

        verify(rateLimiter).tryAcquireWithPolicy(eq("rl:seckill:42:55"), eq(1L), any(Duration.class), any());
    }

    @Test
    void 达到上限_抛频繁异常且不进入业务() throws Throwable {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        UserContext.set(user);
        when(rateLimiter.tryAcquireWithPolicy(any(), eq(3L), any(), any()))
                .thenReturn(RateLimiter.Outcome.REJECTED_LIMIT);
        ProceedingJoinPoint pjp = pjp("claim", Long.class, 1L);

        BizException ex = assertThrows(BizException.class,
                () -> aspect.around(pjp("claim", Long.class, 1L)));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    void SpEL取值为空_抛参数异常_禁止退化为全局键() throws Throwable {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        UserContext.set(user);
        ProceedingJoinPoint pjp = pjp("blankKey", String.class, null);

        assertThrows(BizException.class,
                () -> aspect.around(pjp("blankKey", String.class, null)));
        verify(rateLimiter, never()).tryAcquireWithPolicy(any(), org.mockito.ArgumentMatchers.anyLong(),
                any(), any());
    }

    @Test
    void redis故障_failClosed默认码10007_且不进入业务() throws Throwable {
        when(rateLimiter.tryAcquireWithPolicy(eq("rl:coupon:claim:ip:10.0.0.9"), eq(3L), any(), any()))
                .thenReturn(RateLimiter.Outcome.REDIS_DOWN_DENY);
        ProceedingJoinPoint pjp = pjp("claim", Long.class, 1L);

        BizException ex = assertThrows(BizException.class,
                () -> aspect.around(pjp("claim", Long.class, 1L)));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS.getCode(), ex.getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    void redis故障_资金类按注解码10008与自定义文案拒绝() throws Throwable {
        when(rateLimiter.tryAcquireWithPolicy(eq("rl:funds:withdraw:ip:10.0.0.9"), eq(1L), any(),
                eq(RedisFailurePolicy.FAIL_CLOSE)))
                .thenReturn(RateLimiter.Outcome.REDIS_DOWN_DENY);
        ProceedingJoinPoint pjp = pjp("withdraw", Long.class, 7L);

        BizException ex = assertThrows(BizException.class,
                () -> aspect.around(pjp("withdraw", Long.class, 7L)));
        assertEquals(ErrorCode.DEPENDENCY_FAIL.getCode(), ex.getCode());
        assertEquals("资金服务暂不可用", ex.getMessage());
        verify(pjp, never()).proceed();
    }

    @Test
    void redis故障_failOpen降级_执行业务回源() throws Throwable {
        when(rateLimiter.tryAcquireWithPolicy(eq("rl:goods:detail:ip:10.0.0.9"), eq(10L), any(),
                eq(RedisFailurePolicy.FAIL_OPEN)))
                .thenReturn(RateLimiter.Outcome.REDIS_DOWN_ALLOW);
        ProceedingJoinPoint pjp = pjp("browse", Long.class, 1L);

        aspect.around(pjp);

        verify(pjp).proceed();
    }
}
