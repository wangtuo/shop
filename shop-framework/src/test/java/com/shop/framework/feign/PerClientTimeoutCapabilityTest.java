package com.shop.framework.feign;

import feign.Client;
import feign.Feign;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * PerClientTimeoutCapability 回归测试。
 *
 * <p>W7 HOSTAPPS 实证：该类原为包私有，Feign BaseBuilder 构建客户端时通过
 * {@code Capability.enrich} 反射 Method.invoke 调用其 enrich，JDK 访问检查抛
 * IllegalAccessException，导致首个带 @FeignClient 的服务（shop-product）启动失败。
 * 这里直接走真实 Feign 构建路径守住「public 可见性」红线。</p>
 */
class PerClientTimeoutCapabilityTest {

    @FeignClient(name = "demo-svc")
    interface DemoApi {
        @feign.RequestLine("GET /ping")
        String ping();
    }

    private static class RecordingClient implements Client {
        volatile Request.Options lastOptions;

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            this.lastOptions = options;
            return Response.builder()
                    .status(200)
                    .reason("OK")
                    .headers(Collections.emptyMap())
                    .body("pong", java.nio.charset.StandardCharsets.UTF_8)
                    .request(request)
                    .build();
        }
    }

    @Test
    void feign构建期反射enrich_包私有可见性回归不抛异常() {
        FeignResilienceProperties props = new FeignResilienceProperties();
        assertThatCode(() -> Feign.builder()
                .addCapability(new PerClientTimeoutCapability(props))
                .target(DemoApi.class, "http://127.0.0.1:1"))
                .doesNotThrowAnyException();
    }

    @Test
    void 配置覆盖的client读超时_实际执行时生效() {
        FeignResilienceProperties.Client override = new FeignResilienceProperties.Client();
        override.setReadTimeout(Duration.ofSeconds(9));
        FeignResilienceProperties props = new FeignResilienceProperties();
        props.setClients(Map.of("demo-svc", override));

        RecordingClient client = new RecordingClient();
        DemoApi api = Feign.builder()
                .client(client)
                .options(new Request.Options(Duration.ofSeconds(1), Duration.ofSeconds(2),
                        true))
                .addCapability(new PerClientTimeoutCapability(props))
                .target(DemoApi.class, "http://127.0.0.1:1");

        assertThat(api.ping()).isEqualTo("pong");
        // 名称来自 FeignClientNames.fromType 对接口全限定名的小写化
        assertThat(client.lastOptions.readTimeoutMillis()).isEqualTo(9000);
    }

    @Test
    void 未配置覆盖的client_沿用全局读超时() {
        FeignResilienceProperties props = new FeignResilienceProperties();
        RecordingClient client = new RecordingClient();
        DemoApi api = Feign.builder()
                .client(client)
                .options(new Request.Options(Duration.ofSeconds(1), Duration.ofSeconds(2),
                        true))
                .addCapability(new PerClientTimeoutCapability(props))
                .target(DemoApi.class, "http://127.0.0.1:1");

        api.ping();
        assertThat(client.lastOptions.readTimeoutMillis()).isEqualTo(2000);
    }
}
