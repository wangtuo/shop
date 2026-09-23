package com.shop.framework.datasource;

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * Druid 有界连接池基线（C12 / R-B1）。
 *
 * <p>以 {@link BeanPostProcessor} 在 {@link DruidDataSource} 初始化（{@code init()}）之前填充基线值，
 * <b>仅当业务未显式设置时</b>（值仍等于 Druid 出厂默认）才写入，业务 yml 显式配置永远优先；
 * 因此不需要改动任何业务服务 application.yml。</p>
 *
 * <p>基线内容：maxWait=3000（消除出厂 -1 无限排队）、initialSize=2、minIdle=5、
 * maxActive 按服务名分档（pay/settlement/order=30，其余=20，shop.datasource.tuning.max-active 可覆盖，kind=10）、
 * validationQuery=SELECT 1、testWhileIdle=true、testOnBorrow=false、keepAlive=true、
 * evictor 60s/300s、phyTimeout 30min、removeAbandoned 300s + logAbandoned、
 * filters=stat,wall（slow-sql 1000ms + logSlowSql）。</p>
 */
public class DruidBaselinePostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DruidBaselinePostProcessor.class);

    // Druid 1.2.x 出厂默认（DruidAbstractDataSource 字段初值），用于判定“业务未显式设置”
    private static final int DRUID_DEFAULT_INITIAL_SIZE = 0;
    private static final int DRUID_DEFAULT_MAX_ACTIVE = 8;
    private static final int DRUID_DEFAULT_MIN_IDLE = 0;
    private static final long DRUID_DEFAULT_MAX_WAIT = -1L;
    private static final long DRUID_DEFAULT_PHY_TIMEOUT_MILLIS = -1L;
    private static final long DRUID_DEFAULT_MIN_EVICTABLE_IDLE_MILLIS = 30L * 60L * 1000L;

    // C12 基线
    private static final int BASELINE_INITIAL_SIZE = 2;
    private static final int BASELINE_MIN_IDLE = 5;
    private static final int BASELINE_MAX_WAIT_MILLIS = 3000;
    private static final int CORE_MAX_ACTIVE = 30;
    private static final int DEFAULT_MAX_ACTIVE = 20;
    private static final String BASELINE_VALIDATION_QUERY = "SELECT 1";
    private static final long EVICTION_RUNS_MILLIS = 60L * 1000L;
    private static final long MIN_EVICTABLE_IDLE_MILLIS = 5L * 60L * 1000L;
    private static final long PHY_TIMEOUT_MILLIS = 30L * 60L * 1000L;
    private static final int REMOVE_ABANDONED_TIMEOUT_SECONDS = 300;

    private final Environment environment;
    private final DataSourceTuningProperties properties;

    public DruidBaselinePostProcessor(Environment environment, DataSourceTuningProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof DruidDataSource druidDataSource) {
            applyBaseline(druidDataSource);
        }
        return bean;
    }

    /** 包内可见：供单测直接驱动，不需要起 Spring 容器。 */
    void applyBaseline(DruidDataSource ds) {
        int maxActive = resolveMaxActive();

        if (ds.getInitialSize() == DRUID_DEFAULT_INITIAL_SIZE) {
            ds.setInitialSize(BASELINE_INITIAL_SIZE);
        }
        if (ds.getMinIdle() == DRUID_DEFAULT_MIN_IDLE) {
            ds.setMinIdle(BASELINE_MIN_IDLE);
        }
        if (ds.getMaxActive() == DRUID_DEFAULT_MAX_ACTIVE) {
            ds.setMaxActive(maxActive);
        }
        if (ds.getMaxWait() == DRUID_DEFAULT_MAX_WAIT) {
            ds.setMaxWait(BASELINE_MAX_WAIT_MILLIS);
        }
        if (ds.getValidationQuery() == null || ds.getValidationQuery().isBlank()) {
            ds.setValidationQuery(BASELINE_VALIDATION_QUERY);
        }
        if (!ds.isTestWhileIdle()) {
            ds.setTestWhileIdle(true);
        }
        if (ds.isTestOnBorrow()) {
            ds.setTestOnBorrow(false);
        }
        if (!ds.isKeepAlive()) {
            ds.setKeepAlive(true);
        }
        if (ds.getTimeBetweenEvictionRunsMillis() == EVICTION_RUNS_MILLIS) {
            ds.setTimeBetweenEvictionRunsMillis(EVICTION_RUNS_MILLIS);
        }
        if (ds.getMinEvictableIdleTimeMillis() == DRUID_DEFAULT_MIN_EVICTABLE_IDLE_MILLIS) {
            ds.setMinEvictableIdleTimeMillis(MIN_EVICTABLE_IDLE_MILLIS);
        }
        if (ds.getPhyTimeoutMillis() == DRUID_DEFAULT_PHY_TIMEOUT_MILLIS) {
            ds.setPhyTimeoutMillis(PHY_TIMEOUT_MILLIS);
        }
        if (!ds.isRemoveAbandoned()) {
            ds.setRemoveAbandoned(true);
            ds.setRemoveAbandonedTimeout(REMOVE_ABANDONED_TIMEOUT_SECONDS);
            ds.setLogAbandoned(true);
        }
        addFiltersIfAbsent(ds);
        enforceBoundedMaxWait(ds);

        log.info("Druid 有界基线已生效: maxActive={}, minIdle={}, initialSize={}, maxWait={}ms, "
                        + "validationQuery={}, filters={}",
                ds.getMaxActive(), ds.getMinIdle(), ds.getInitialSize(), ds.getMaxWait(),
                ds.getValidationQuery(), ds.getProxyFilters().stream().map(f -> f.getClass().getSimpleName()).toList());
    }

    /**
     * 任何环境下 maxWait&lt;0 都打 ERROR；严格模式（prod 默认）直接 fail-fast，拒绝无限排队连接池上线。
     */
    private void enforceBoundedMaxWait(DruidDataSource ds) {
        if (ds.getMaxWait() < 0) {
            String message = "Druid maxWait 必须为有界非负值（基线 3000ms），当前 maxWait=" + ds.getMaxWait()
                    + "；请修正 spring.datasource.druid.max-wait 或 shop.datasource.tuning 配置";
            if (isStrict()) {
                log.error(message);
                throw new IllegalStateException(message);
            }
            log.error("{}（非严格模式仅告警，不阻断启动）", message);
        }
    }

    private void addFiltersIfAbsent(DruidDataSource ds) {
        List<Filter> filters = new ArrayList<>(ds.getProxyFilters());
        boolean hasStat = filters.stream().anyMatch(f -> f instanceof StatFilter);
        boolean hasWall = filters.stream().anyMatch(f -> f instanceof WallFilter);
        if (!hasStat) {
            StatFilter statFilter = new StatFilter();
            statFilter.setSlowSqlMillis(properties.getSlowSqlMillis());
            statFilter.setLogSlowSql(true);
            filters.add(statFilter);
        }
        if (!hasWall) {
            filters.add(new WallFilter());
        }
        ds.setProxyFilters(filters);
    }

    private int resolveMaxActive() {
        if (properties.getMaxActive() != null) {
            return properties.getMaxActive();
        }
        String appName = environment.getProperty("spring.application.name");
        if (appName == null) {
            appName = "";
        }
        if (appName.contains("pay") || appName.contains("settlement") || appName.contains("order")) {
            return CORE_MAX_ACTIVE;
        }
        return DEFAULT_MAX_ACTIVE;
    }

    private boolean isStrict() {
        if (properties.getStrict() != null) {
            return properties.getStrict();
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return "prod".equalsIgnoreCase(environment.getProperty("shop.env"));
    }
}
