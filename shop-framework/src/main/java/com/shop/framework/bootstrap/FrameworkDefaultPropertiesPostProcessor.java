package com.shop.framework.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * 框架级默认属性（最低优先级，业务 application.yml / 环境变量均可覆盖；C15 / C18 / C19）：
 * <ul>
 *   <li>{@code shop.env=dev-local}：环境标识缺失时的安全默认（ShedLock 前缀/限流键/密钥校验统一引用）；</li>
 *   <li>{@code management.endpoint.health.group.readiness.include=readinessState,db,redis}：
 *       readiness 探针<b>显式排除 mqConsumers</b>——broker 不可用时 HTTP 服务仍应存活，
 *       不应因 MQ 抖动摘流（MQ 故障靠消费堆积告警发现，见 R-MQ 残留说明）；</li>
 *   <li>{@code management.endpoint.health.group.liveness.include=livenessState}：
 *       liveness 只反映进程内部存活状态，不依赖任何外部组件。</li>
 * </ul>
 *
 * <p>注册于 {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}，
 * 在配置文件导入前以 addLast 挂入，业务 yml 同名键优先生效。</p>
 */
public class FrameworkDefaultPropertiesPostProcessor implements EnvironmentPostProcessor {

    public static final String PROPERTY_SOURCE_NAME = "shopFrameworkDefaults";

    static final String SHOP_ENV = "shop.env";
    static final String READINESS_GROUP = "management.endpoint.health.group.readiness.include";
    static final String LIVENESS_GROUP = "management.endpoint.health.group.liveness.include";
    static final String DEFAULT_ENV = "dev-local";
    static final String READINESS_INCLUDE = "readinessState,db,redis";
    static final String LIVENESS_INCLUDE = "livenessState";

    // ---- O7/C55：可观测默认值（业务 application.yml / 环境变量均可覆盖） ----
    /** 所有指标自动带 application 标签（取服务名），与 Prometheus relabel 的 app 双保险。 */
    static final String METRICS_TAG_APPLICATION = "management.metrics.tags.application";
    /** HTTP 服务端指标导出 Prometheus 原生 histogram bucket（p95/p99 面板依赖）。 */
    static final String HISTOGRAM_HTTP_SERVER =
            "management.metrics.distribution.percentiles-histogram.http.server.requests";
    /** histogram 上界 5s，p99 在 ≤5s 范围可靠。 */
    static final String HISTOGRAM_MAX_EXPECTED =
            "management.metrics.distribution.maximum-expected-value.http.server.requests";
    /** O1：OTel bridge 采样率，默认 100%（仅日志关联、无 exporter，开销可控）。 */
    static final String TRACING_SAMPLE_RATE = "management.tracing.sampling.probability";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(SHOP_ENV, DEFAULT_ENV);
        defaults.put(READINESS_GROUP, READINESS_INCLUDE);
        defaults.put(LIVENESS_GROUP, LIVENESS_INCLUDE);
        // 占位符在属性解析期展开为各服务自身 spring.application.name。
        defaults.put(METRICS_TAG_APPLICATION, "${spring.application.name}");
        defaults.put(HISTOGRAM_HTTP_SERVER, "true");
        defaults.put(HISTOGRAM_MAX_EXPECTED, "5s");
        defaults.put(TRACING_SAMPLE_RATE, "${TRACING_SAMPLE_RATE:1.0}");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }
}
