package com.shop.framework.feign;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Feign 统一韧性配置（C13）。键前缀 {@code shop.feign}，业务零代码接入：
 * <ul>
 *   <li>{@code shop.feign.connect-timeout / read-timeout}：默认 2s/3s</li>
 *   <li>{@code shop.feign.clients.<name>.read-timeout}：按 client 覆盖读超时</li>
 *   <li>{@code shop.feign.retry.*}：默认零重试，仅显式声明的幂等方法（GET）可重试</li>
 *   <li>{@code shop.feign.pool.*}：HttpClient5 连接池 200/50/TTL30s</li>
 *   <li>{@code shop.feign.circuit.*}：Resilience4j 熔断+舱壁，{@code enabled=false} 可关</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "shop.feign")
public class FeignResilienceProperties {

    /** 全局连接超时，默认 2s（C13）。 */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** 全局读超时，默认 3s（C13）。 */
    private Duration readTimeout = Duration.ofSeconds(3);

    private Pool pool = new Pool();

    private Retry retry = new Retry();

    private Circuit circuit = new Circuit();

    /** 按 clientName（@FeignClient contextId/name）覆盖的配置。 */
    private Map<String, Client> clients = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class Pool {

        /** shop.feign.pool.max-connections，连接池最大连接数，默认 200。 */
        private int maxConnections = 200;

        /** shop.feign.pool.max-connections-per-route，单路由最大连接数，默认 50。 */
        private int maxConnectionsPerRoute = 50;

        /** shop.feign.pool.connection-time-to-live，连接 TTL，默认 30s。 */
        private Duration connectionTimeToLive = Duration.ofSeconds(30);

        /** shop.feign.pool.time-to-live-unit，TTL 时间单位，默认 SECONDS。 */
        private TimeUnit timeToLiveUnit = TimeUnit.SECONDS;
    }

    @Getter
    @Setter
    public static class Client {

        /** shop.feign.clients.<name>.read-timeout：仅放宽指定 client 的读超时。 */
        private Duration readTimeout;
    }

    @Getter
    @Setter
    public static class Retry {

        /**
         * shop.feign.retry.max-attempts：总尝试次数（含首次），默认 0 = 永不重试。
         * POST/PUT/DELETE 永不重试；仅 {@link #methods} 中声明的幂等方法允许重试。
         */
        private int maxAttempts = 0;

        /** shop.feign.retry.methods：允许重试的 HTTP 方法白名单，默认空（如 GET）。 */
        private List<String> methods = new ArrayList<>();

        /** shop.feign.retry.backoff：重试退避间隔，默认 200ms。 */
        private Duration backoff = Duration.ofMillis(200);
    }

    @Getter
    @Setter
    public static class Circuit {

        /** shop.feign.circuit.enabled：熔断+舱壁开关，默认开启，单测/本地可关。 */
        private boolean enabled = true;

        /** shop.feign.circuit.sliding-window-size：计数型滑动窗口，默认 100。 */
        private int slidingWindowSize = 100;

        /** shop.feign.circuit.failure-rate-threshold：失败率百分比阈值，默认 50。 */
        private float failureRateThreshold = 50f;

        /** shop.feign.circuit.wait-duration-in-open-state：open 态停留时长，默认 10s。 */
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);

        /** shop.feign.circuit.slow-call-rate-threshold：慢调用率百分比阈值，默认 60。 */
        private float slowCallRateThreshold = 60f;

        /** shop.feign.circuit.slow-call-duration-threshold：慢调用判定时长，默认 2s。 */
        private Duration slowCallDurationThreshold = Duration.ofSeconds(2);

        /** shop.feign.circuit.minimum-number-of-calls：窗口内最小样本数，低 QPS client 防误判，默认 10。 */
        private int minimumNumberOfCalls = 10;

        /** shop.feign.circuit.max-concurrent-calls：按 client 的信号量舱壁并发上限，默认 30。 */
        private int maxConcurrentCalls = 30;
    }
}
