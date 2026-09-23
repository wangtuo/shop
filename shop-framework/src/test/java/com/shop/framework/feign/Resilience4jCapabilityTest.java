package com.shop.framework.feign;

import com.shop.common.exception.BizException;
import com.shop.common.result.Result;
import feign.Capability;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Response;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * {@link Resilience4jCapability} 回归测试。
 *
 * <p>W7 HOSTAPPS 实证：运行期跨服务 Feign 故障统一暴露为
 * {@code 10008 下游服务不可用: <client>}，而 catch 分支曾丢弃 cause，
 * 导致无法区分 SCL「No servers available」、Nacos 订阅空、NIO 死通道与熔断 open。
 * 这里守住两条根因保真红线：RetryableException（IO 重试耗尽）与
 * CallNotPermittedException（熔断 open）归一为 BizException 时 <b>必须携带原始 cause</b>。</p>
 */
class Resilience4jCapabilityTest {

    @FeignClient(name = "demo-dep-svc")
    interface DemoApi {
        @feign.RequestLine("GET /ping")
        String ping();
    }

    @FeignClient(name = "demo-dep-svc")
    interface DemoResultApi {
        @feign.RequestLine("GET /order")
        Result<String> getOrder();
    }

    /** 2xx 但 Result 包裹业务失败（订单不存在 50001）：ShopResultDecoder 抛 BizException，
     *  Feign SynchronousMethodHandler 会包成 DecodeException。 */
    private static final Client BIZ_ERROR_2XX_CLIENT = new Client() {
        @Override
        public Response execute(Request request, Request.Options options) {
            return Response.builder()
                    .status(200).reason("OK").request(request)
                    .body("{\"code\":50001,\"message\":\"订单不存在\",\"data\":null}",
                            StandardCharsets.UTF_8)
                    .build();
        }
    };

    /** 任何请求都抛 IOException 的 Feign Client：SynchronousMethodHandler 会包成 RetryableException。 */
    private static final Client IO_FAILURE_CLIENT = new Client() {
        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            throw new IOException("No servers available: demo-dep-svc");
        }
    };

    private Feign.Builder feignWith(CircuitBreakerRegistry circuitBreakerRegistry) {
        Capability capability = new Resilience4jCapability(
                circuitBreakerRegistry, BulkheadRegistry.of(BulkheadConfig.ofDefaults()));
        return Feign.builder()
                .client(IO_FAILURE_CLIENT)
                .addCapability(capability);
    }

    @Test
    void io重试耗尽归一10008时_必须保留RetryableException根因链() {
        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        DemoApi api = feignWith(registry).target(DemoApi.class, "http://demo-dep-svc");

        Throwable thrown = catchThrowable(api::ping);

        assertThat(thrown).isInstanceOf(BizException.class);
        BizException biz = (BizException) thrown;
        assertThat(biz.getCode()).isEqualTo(10008);
        assertThat(biz.getMessage()).isEqualTo("下游服务不可用: demo-dep-svc");
        // cause 链必须能一路看到底层 IO 根因（旧实现此处为 null，线上只剩余客户端名）
        assertThat(biz.getCause()).isNotNull();
        assertThat(hasIoRootCause(biz, "No servers available")).isTrue();
    }

    @Test
    void 熔断open拒绝时_必须保留CallNotPermittedException作为cause() {
        CircuitBreakerRegistry registry =
                CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        // 客户端名来自 FeignClientNames 对 @FeignClient 注解名的解析
        registry.circuitBreaker("demo-dep-svc").transitionToForcedOpenState();
        DemoApi api = feignWith(registry).target(DemoApi.class, "http://demo-dep-svc");

        Throwable thrown = catchThrowable(api::ping);

        assertThat(thrown).isInstanceOf(BizException.class);
        BizException biz = (BizException) thrown;
        assertThat(biz.getCode()).isEqualTo(10008);
        assertThat(biz.getCause()).isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    void 下游2xx携带业务码_DecodeException拆包为BizException且不计熔断失败率() {
        // 与 FeignResilienceConfiguration 生产配置同一条 ignore 谓词
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .ignoreException(t -> !Resilience4jCapability.countsAsDependencyFailure(t))
                        .build());
        DemoResultApi api = Feign.builder()
                .client(BIZ_ERROR_2XX_CLIENT)
                .decoder(new ShopResultDecoder(
                        new com.fasterxml.jackson.databind.ObjectMapper()))
                .addCapability(new Resilience4jCapability(
                        registry, BulkheadRegistry.of(BulkheadConfig.ofDefaults())))
                .target(DemoResultApi.class, "http://demo-dep-svc");

        // 打满默认滑窗 100 次：业务失败必须被 ignoreException 忽略，熔断器保持 CLOSED
        for (int i = 0; i < 100; i++) {
            Throwable thrown = catchThrowable(api::getOrder);
            assertThat(thrown).isInstanceOf(BizException.class);
            assertThat(((BizException) thrown).getCode()).isEqualTo(50001);
            assertThat(thrown.getMessage()).contains("订单不存在");
        }
        CircuitBreaker breaker = registry.circuitBreaker("demo-dep-svc");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void 业务码之外的异常都计入依赖失败() {
        assertThat(Resilience4jCapability.countsAsDependencyFailure(
                new IOException("boom"))).isTrue();
        assertThat(Resilience4jCapability.countsAsDependencyFailure(
                new BizException(com.shop.common.exception.ErrorCode.DEPENDENCY_FAIL, "x")))
                .isTrue();
        assertThat(Resilience4jCapability.countsAsDependencyFailure(
                new BizException(com.shop.common.exception.ErrorCode.PARAM_INVALID, "x")))
                .isFalse();
    }

    private static boolean hasIoRootCause(Throwable thrown, String messageFragment) {
        Throwable cur = thrown;
        while (cur != null) {
            if (cur instanceof IOException && cur.getMessage() != null
                    && cur.getMessage().contains(messageFragment)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
