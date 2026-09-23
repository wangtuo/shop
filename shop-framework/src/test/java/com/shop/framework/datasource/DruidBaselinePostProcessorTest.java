package com.shop.framework.datasource;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C12 Druid 有界基线分档单测：不启动 Spring 容器，直接驱动 {@link DruidBaselinePostProcessor}，
 * 断言三个代表服务名的分档结果与全部基线键，并校验"业务显式配置优先"与 strict fail-fast。
 */
class DruidBaselinePostProcessorTest {

    private Environment environment;

    @BeforeEach
    void setUp() {
        environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[0]);
    }

    private DruidBaselinePostProcessor processor(String appName, DataSourceTuningProperties props) {
        when(environment.getProperty("spring.application.name")).thenReturn(appName);
        return new DruidBaselinePostProcessor(environment, props);
    }

    private DruidBaselinePostProcessor processor(String appName) {
        return processor(appName, new DataSourceTuningProperties());
    }

    @Test
    void core_services_get_max_active_30() {
        for (String appName : new String[]{"shop-pay-service", "shop-order-service", "shop-settlement-service"}) {
            DruidDataSource ds = new DruidDataSource();
            processor(appName).postProcessBeforeInitialization(ds, "dataSource");
            assertThat(ds.getMaxActive())
                    .as("%s maxActive 应为核心档 30", appName)
                    .isEqualTo(30);
        }
    }

    @Test
    void non_core_service_gets_max_active_20() {
        DruidDataSource ds = new DruidDataSource();
        processor("shop-user-service").postProcessBeforeInitialization(ds, "dataSource");
        assertThat(ds.getMaxActive()).isEqualTo(20);
    }

    @Test
    void kind_override_max_active_10() {
        DataSourceTuningProperties props = new DataSourceTuningProperties();
        props.setMaxActive(10);
        DruidDataSource ds = new DruidDataSource();
        processor("shop-pay-service", props).postProcessBeforeInitialization(ds, "dataSource");
        assertThat(ds.getMaxActive()).isEqualTo(10);
    }

    @Test
    void baseline_keys_are_applied_to_bare_druid_datasource() {
        DruidDataSource ds = new DruidDataSource();
        processor("shop-order-service").postProcessBeforeInitialization(ds, "dataSource");

        assertThat(ds.getMaxWait()).isEqualTo(3000L);
        assertThat(ds.getInitialSize()).isEqualTo(2);
        assertThat(ds.getMinIdle()).isEqualTo(5);
        assertThat(ds.getValidationQuery()).isEqualTo("SELECT 1");
        assertThat(ds.isTestWhileIdle()).isTrue();
        assertThat(ds.isTestOnBorrow()).isFalse();
        assertThat(ds.isKeepAlive()).isTrue();
        assertThat(ds.getTimeBetweenEvictionRunsMillis()).isEqualTo(60_000L);
        assertThat(ds.getMinEvictableIdleTimeMillis()).isEqualTo(300_000L);
        assertThat(ds.getPhyTimeoutMillis()).isEqualTo(1_800_000L);
        assertThat(ds.isRemoveAbandoned()).isTrue();
        assertThat(ds.getRemoveAbandonedTimeout()).isEqualTo(300);
        assertThat(ds.isLogAbandoned()).isTrue();

        assertThat(ds.getProxyFilters()).hasSize(2);
        StatFilter statFilter = (StatFilter) ds.getProxyFilters().stream()
                .filter(f -> f instanceof StatFilter).findFirst().orElseThrow();
        assertThat(statFilter.getSlowSqlMillis()).isEqualTo(1000L);
        assertThat(statFilter.isLogSlowSql()).isTrue();
        assertThat(ds.getProxyFilters()).anyMatch(f -> f instanceof WallFilter);
    }

    @Test
    void explicit_business_settings_win() {
        DruidDataSource ds = new DruidDataSource();
        ds.setMaxActive(50);
        ds.setInitialSize(7);
        processor("shop-user-service").postProcessBeforeInitialization(ds, "dataSource");
        assertThat(ds.getMaxActive()).isEqualTo(50);
        assertThat(ds.getInitialSize()).isEqualTo(7);
    }

    @Test
    void negative_max_wait_passes_when_non_strict_but_is_bounded_by_default() {
        // 默认未设置 maxWait（Druid 出厂 -1）会被基线填成 3000
        DruidDataSource ds = new DruidDataSource();
        processor("shop-user-service").postProcessBeforeInitialization(ds, "dataSource");
        assertThat(ds.getMaxWait()).isEqualTo(3000L);

        // 非严格模式下显式私设负值：不阻断（仅 ERROR 日志）
        DruidDataSource rogue = new DruidDataSource();
        rogue.setMaxWait(-100);
        processor("shop-user-service").postProcessBeforeInitialization(rogue, "dataSource");
        assertThat(rogue.getMaxWait()).isEqualTo(-100);
    }

    @Test
    void negative_max_wait_fails_fast_when_strict() {
        DataSourceTuningProperties props = new DataSourceTuningProperties();
        props.setStrict(true);
        DruidDataSource ds = new DruidDataSource();
        ds.setMaxWait(-100);
        assertThatThrownBy(() -> processor("shop-pay-service", props)
                .postProcessBeforeInitialization(ds, "dataSource"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxWait");
    }

    @Test
    void strict_defaults_to_true_in_prod_profile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(environment.getProperty("spring.application.name")).thenReturn("shop-pay-service");
        DruidDataSource ds = new DruidDataSource();
        ds.setMaxWait(-1);
        DruidBaselinePostProcessor p = new DruidBaselinePostProcessor(environment, new DataSourceTuningProperties());
        // 显式 -1 与出厂默认同值会被基线填为 3000，不触发异常；改 -2 验证 prod 默认 strict
        DruidDataSource rogue = new DruidDataSource();
        rogue.setMaxWait(-2);
        assertThatThrownBy(() -> p.postProcessBeforeInitialization(rogue, "dataSource"))
                .isInstanceOf(IllegalStateException.class);
    }
}
