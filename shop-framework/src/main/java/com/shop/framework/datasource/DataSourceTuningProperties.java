package com.shop.framework.datasource;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据源调优开关（C12）。
 *
 * <p>基线键（maxWait/initialSize/minIdle/eviction/removeAbandoned/filters 等）由
 * {@link DruidBaselinePostProcessor} 以代码方式注入，业务服务 application.yml 零改动即可获得有界基线；
 * 本类只暴露少量允许按环境覆盖的分档值。</p>
 *
 * <ul>
 *   <li>{@code shop.datasource.tuning.max-active}：覆盖按服务名分档的 maxActive
 *       （pay/settlement/order=30，其余=20；kind/压测环境可覆盖为 10）。</li>
 *   <li>{@code shop.datasource.tuning.strict}：true 时业务私设 maxWait&lt;0 直接 fail-fast；
 *       缺省由环境推导——prod profile 或 shop.env=prod 时为 true，其余 false（仅 ERROR 日志，dev 可过）。</li>
 *   <li>{@code shop.datasource.tuning.slow-sql-millis}：stat filter 慢 SQL 阈值，默认 1000ms。</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "shop.datasource.tuning")
public class DataSourceTuningProperties {

    /** 分档覆盖；为 null 时按 spring.application.name 分档（核心 30 / 其余 20）。 */
    private Integer maxActive;

    /** 严格模式开关；null 时按环境推导（prod=true）。 */
    private Boolean strict;

    /** stat filter 慢 SQL 阈值（毫秒）。 */
    private long slowSqlMillis = 1000L;
}
