package com.shop.framework.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * DruidMetricsBinder：无实例/无注册表环境下采样必须安全（C58 健壮性）。
 */
class DruidMetricsBinderTest {

    @Test
    void sampleIsSafeWithoutDataSourceOrRegistry() {
        DruidMetricsBinder binder = new DruidMetricsBinder(new BizMetrics(null));
        assertDoesNotThrow(binder::sample);

        DruidMetricsBinder withRegistry = new DruidMetricsBinder(new BizMetrics(new SimpleMeterRegistry()));
        assertDoesNotThrow(withRegistry::sample);
    }
}
