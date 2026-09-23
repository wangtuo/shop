package com.shop.framework.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Capability;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RetryableException;
import feign.Retryer;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Feign 统一韧性自动配置（C13 / R-B2）。位于 com.shop 包扫描路径下，
 * 业务服务经 {@code @ShopService} 的 scanBasePackages 自动生效，零代码接入。
 *
 * <p>提供：HttpClient5 连接池、统一超时与按 client 覆盖、幂等 GET 有限重试、
 * {@link ShopErrorDecoder}、{@link ShopResultDecoder}、Resilience4j 熔断+舱壁。
 * 全部组件为普通 Bean，无 AOP 代理依赖；{@code shop.feign.circuit.enabled=false}
 * 可在单测/本地关闭熔断与舱壁。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeignResilienceProperties.class)
public class FeignResilienceConfiguration {

    /**
     * 只暴露 HC5 {@link HttpClient}（连接池 200 / 单路由 50 / TTL 30s，shop.feign.pool.*）。
     *
     * <p><b>禁止在此声明 {@code feign.Client} Bean</b>（W7 E2E 实证根因）：Spring Cloud OpenFeign
     * 的 {@code HttpClient5FeignLoadBalancerConfiguration} 在每个 {@code @FeignClient} 子上下文里以
     * {@code @ConditionalOnMissingBean(feign.Client)} 注册「HC5 + 负载均衡」组合客户端
     * {@code new FeignBlockingLoadBalancerClient(new ApacheHttp5Client(httpClient), ...)}。
     * 主上下文一旦存在裸 {@code ApacheHttp5Client} 类型的 {@code feign.Client}，子上下文的 LB
     * 包装 Bean 整体退避，Feign 拿到的是不经负载均衡的裸客户端，运行期直接把服务名当主机名做
     * DNS，报 {@code UnknownHostException: shop-product-service}，经熔断层归一为 10008。
     * 暴露 HC5 {@link HttpClient} 才是官方支持的扩展点：LB 配置会消费它并自动包上负载均衡。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(HttpClient.class)
    public CloseableHttpClient shopFeignHttpClient(FeignResilienceProperties properties) {
        FeignResilienceProperties.Pool pool = properties.getPool();
        long ttlValue = pool.getTimeToLiveUnit()
                .convert(pool.getConnectionTimeToLive().toMillis(), TimeUnit.MILLISECONDS);
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(pool.getMaxConnections())
                        .setMaxConnPerRoute(pool.getMaxConnectionsPerRoute())
                        .setConnectionTimeToLive(TimeValue.of(ttlValue, pool.getTimeToLiveUnit()))
                        .build())
                .evictExpiredConnections()
                .build();
    }

    /** 默认 connect 2s / read 3s（shop.feign.connect-timeout / read-timeout）。 */
    @Bean
    @ConditionalOnMissingBean
    public Request.Options shopFeignOptions(FeignResilienceProperties properties) {
        return new Request.Options(properties.getConnectTimeout(), properties.getReadTimeout(), true);
    }

    /** 默认零重试；仅 shop.feign.retry.methods 白名单（GET）+ max-attempts=2 时重试一次。 */
    @Bean
    @ConditionalOnMissingBean
    public Retryer shopFeignRetryer(FeignResilienceProperties properties) {
        FeignResilienceProperties.Retry retry = properties.getRetry();
        Set<HttpMethod> retryableMethods = retry.getMethods().stream()
                .filter(method -> !method.isBlank())
                .map(method -> HttpMethod.valueOf(method.trim().toUpperCase()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(HttpMethod.class)));
        return new ConfigurableRetryer(
                Math.max(0, retry.getMaxAttempts()), retryableMethods, retry.getBackoff().toMillis());
    }

    @Bean
    @ConditionalOnMissingBean
    public ErrorDecoder shopErrorDecoder(ObjectMapper objectMapper) {
        return new ShopErrorDecoder(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public Decoder shopResultDecoder(ObjectMapper objectMapper) {
        return new ShopResultDecoder(objectMapper);
    }

    /** 按 client 读超时覆盖，始终生效（不依赖熔断开关）。 */
    @Bean
    public Capability shopPerClientTimeoutCapability(FeignResilienceProperties properties) {
        return new PerClientTimeoutCapability(properties);
    }

    /** Resilience4j 熔断+舱壁 Capability，默认开启，可经 shop.feign.circuit.enabled=false 关闭。 */
    @Bean
    @ConditionalOnProperty(prefix = "shop.feign.circuit", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public Capability shopResilience4jCapability(FeignResilienceProperties properties) {
        FeignResilienceProperties.Circuit circuit = properties.getCircuit();

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(circuit.getSlidingWindowSize())
                .failureRateThreshold(circuit.getFailureRateThreshold())
                .waitDurationInOpenState(circuit.getWaitDurationInOpenState())
                .slowCallRateThreshold(circuit.getSlowCallRateThreshold())
                .slowCallDurationThreshold(circuit.getSlowCallDurationThreshold())
                .minimumNumberOfCalls(circuit.getMinimumNumberOfCalls())
                // 业务码异常不打熔断；DEPENDENCY_FAIL/TIMEOUT 计入失败率
                .ignoreException(t -> !Resilience4jCapability.countsAsDependencyFailure(t))
                .build();

        BulkheadConfig bulkheadConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(circuit.getMaxConcurrentCalls())
                .build();

        return new Resilience4jCapability(
                CircuitBreakerRegistry.of(circuitBreakerConfig),
                BulkheadRegistry.of(bulkheadConfig));
    }

    /**
     * 可配置 Retryer：默认 maxAttempts=0 永不重试；白名单方法（建议仅 GET）在 attempts&gt;1 时重试，
     * 固定退避。每次调用 clone 独立计数。POST 等非白名单方法首次异常即抛出。
     */
    static final class ConfigurableRetryer implements Retryer {

        private final int maxAttempts;
        private final Set<HttpMethod> retryableMethods;
        private final long backoffMillis;
        private int attempt = 1;

        ConfigurableRetryer(int maxAttempts, Set<HttpMethod> retryableMethods, long backoffMillis) {
            this.maxAttempts = maxAttempts;
            this.retryableMethods = retryableMethods;
            this.backoffMillis = backoffMillis;
        }

        @Override
        public void continueOrPropagate(RetryableException e) {
            HttpMethod method = e.method();
            if (maxAttempts <= 1 || method == null || !retryableMethods.contains(method)
                    || attempt >= maxAttempts) {
                throw e;
            }
            attempt++;
            try {
                Thread.sleep(backoffMillis);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        @Override
        public Retryer clone() {
            return new ConfigurableRetryer(maxAttempts, retryableMethods, backoffMillis);
        }
    }
}
