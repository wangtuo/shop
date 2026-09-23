package com.shop.framework.idempotent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下单防重复提交幂等切面单测（mock Redisson，无中间件）：
 * SET NX 首次放行并缓存返回值 JSON；窗口内重复（含并发在途）回放同一返回值；
 * 首请求失败删标记允许重试；空键 fail-fast。
 */
@ExtendWith(MockitoExtension.class)
class IdempotentAspectTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> bucket;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private MethodSignature signature;

    private IdempotentAspect aspect;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 与 OrderCreateServiceImpl#create 同形：SpEL 取 #request.clientToken */
    static class CreateRequest {
        private final String clientToken;

        CreateRequest(String clientToken) {
            this.clientToken = clientToken;
        }

        public String getClientToken() {
            return clientToken;
        }
    }

    static class DemoOrderService {
        @Idempotent(prefix = "order:create", key = "#request.clientToken")
        public String create(CreateRequest request) {
            return "ORDER-NO";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        aspect = new IdempotentAspect(redissonClient, objectMapper);
        Method method = DemoOrderService.class.getMethod("create", CreateRequest.class);
        when(signature.getMethod()).thenReturn(method);
        when(pjp.getSignature()).thenReturn(signature);
        // 空键用例不会触达 redis，统一 lenient 避免无用桩报错
        org.mockito.Mockito.lenient().when(redissonClient.<String>getBucket("idem:order:create:token-abc"))
                .thenReturn(bucket);
    }

    @Test
    void around_firstRequest_setsInflightProceedsAndCachesResultJson() throws Throwable {
        CreateRequest request = new CreateRequest("token-abc");
        when(pjp.getArgs()).thenReturn(new Object[]{request});
        when(bucket.setIfAbsent(eq(IdempotentAspect.INFLIGHT), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenReturn("ORDER-NO");

        Object result = aspect.around(pjp);

        assertThat(result).isEqualTo("ORDER-NO");
        verify(bucket).setIfAbsent(eq(IdempotentAspect.INFLIGHT), eq(Duration.ofSeconds(24 * 3600L)));
        // 返回值 JSON 覆写占位（String "ORDER-NO" 序列化为带引号 JSON）
        verify(bucket).set(eq("\"ORDER-NO\""), eq(Duration.ofSeconds(24 * 3600L)));
        verify(bucket, never()).delete();
    }

    @Test
    void around_duplicateAfterCompletion_replaysSameResult() throws Throwable {
        CreateRequest request = new CreateRequest("token-abc");
        when(pjp.getArgs()).thenReturn(new Object[]{request});
        when(bucket.setIfAbsent(eq(IdempotentAspect.INFLIGHT), any(Duration.class))).thenReturn(false);
        // 首请求已完成：缓存中为返回值 JSON
        when(bucket.get()).thenReturn("\"ORDER-NO\"");

        Object result = aspect.around(pjp);

        assertThat(result).isEqualTo("ORDER-NO");
        verify(pjp, never()).proceed();
        verify(bucket, never()).delete();
    }

    @Test
    void around_concurrentDuplicate_waitsInflightThenReplaysSameResult() throws Throwable {
        CreateRequest request = new CreateRequest("token-abc");
        when(pjp.getArgs()).thenReturn(new Object[]{request});
        when(bucket.setIfAbsent(eq(IdempotentAspect.INFLIGHT), any(Duration.class))).thenReturn(false);
        // 第一次轮询首请求仍在途，第二次轮询结果已就绪
        when(bucket.get()).thenReturn(IdempotentAspect.INFLIGHT, "\"ORDER-NO\"");

        Object result = aspect.around(pjp);

        assertThat(result).isEqualTo("ORDER-NO");
        verify(pjp, never()).proceed();
    }

    @Test
    void around_duplicateAfterFirstFailed_deletesMarkerThrowsRepeatSubmit() throws Throwable {
        CreateRequest request = new CreateRequest("token-abc");
        when(pjp.getArgs()).thenReturn(new Object[]{request});
        when(bucket.setIfAbsent(eq(IdempotentAspect.INFLIGHT), any(Duration.class))).thenReturn(false);
        // 首请求失败已删标记（允许重试），但本请求拿到的是窗口期旧 NX 失败：无缓存可回放
        when(bucket.get()).thenReturn(null);

        assertThatThrownBy(() -> aspect.around(pjp))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.REPEAT_SUBMIT.getCode());
        verify(pjp, never()).proceed();
    }

    @Test
    void around_nullKey_throwsParamInvalidAndNeverTouchesRedis() throws Throwable {
        // P2-1：SpEL 解析为 null 时禁止退化成全局共享键 idem:prefix:null
        CreateRequest request = new CreateRequest(null);
        when(pjp.getArgs()).thenReturn(new Object[]{request});

        assertThatThrownBy(() -> aspect.around(pjp))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.PARAM_INVALID.getCode());
        verify(pjp, never()).proceed();
        verify(redissonClient, never()).getBucket(any(String.class));
    }

    @Test
    void around_businessFailure_deletesMarkerAndRethrows() throws Throwable {
        CreateRequest request = new CreateRequest("token-abc");
        when(pjp.getArgs()).thenReturn(new Object[]{request});
        when(bucket.setIfAbsent(eq(IdempotentAspect.INFLIGHT), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenThrow(new BizException(ErrorCode.STOCK_NOT_ENOUGH, "库存不足"));

        assertThatThrownBy(() -> aspect.around(pjp))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.STOCK_NOT_ENOUGH.getCode());
        // 业务失败立即释放幂等标记，允许用户修正后快速重试
        verify(bucket).delete();
        verify(bucket, never()).set(any(String.class), any(Duration.class));
    }
}
