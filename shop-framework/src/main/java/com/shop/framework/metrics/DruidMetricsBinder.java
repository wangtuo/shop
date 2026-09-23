package com.shop.framework.metrics;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.stat.DataSourceMonitorable;
import com.alibaba.druid.stat.DruidDataSourceStatManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Druid 连接池 → Micrometer 指标导出（O6/C58）。
 *
 * <p>不引第三方包装：每 30s（{@code shop.druid.metrics-interval-ms}，可配）从
 * {@link DruidDataSourceStatManager} 静态实例表采样一次，写入首次出现时注册的 Gauge：
 * {@code shop_druid_active/idle/wait/max{datasource=}}。数据源名为 Druid 内部低基数名
 * （DataSource-1 等），不带 JDBC URL，避免高基数与凭据泄露。</p>
 *
 * <p>Gauge 持有者 Map 强引用，避免 Micrometer 弱引用被 GC；采样异常（实例瞬态注销等）
 * 只 debug 不影响业务。</p>
 */
@Component
public class DruidMetricsBinder {

    private static final Logger log = LoggerFactory.getLogger(DruidMetricsBinder.class);

    private final BizMetrics bizMetrics;
    private final ConcurrentHashMap<String, AtomicLong> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> idle = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> wait = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> max = new ConcurrentHashMap<>();

    @Autowired(required = false)
    public DruidMetricsBinder(BizMetrics bizMetrics) {
        this.bizMetrics = bizMetrics == null ? new BizMetrics(null) : bizMetrics;
    }

    /**
     * 定期采样；initialDelay 15s 等连接池初始化。采样只读 Druid 统计值，不触发连接创建。
     */
    @Scheduled(fixedDelayString = "${shop.druid.metrics-interval-ms:30000}", initialDelayString = "15000")
    public void sample() {
        try {
            Set<DataSourceMonitorable> instances = DruidDataSourceStatManager.getDruidDataSourceInstances();
            if (instances == null) {
                return;
            }
            for (DataSourceMonitorable monitorable : instances) {
                // 1.2.22 静态表元素类型为 DataSourceMonitorable；池统计读数在 DruidDataSource 实现上。
                if (!(monitorable instanceof DruidDataSource ds)) {
                    continue;
                }
                String name = ds.getName() == null ? "default" : ds.getName();
                holder(active, BizMetrics.DRUID_ACTIVE, name).set(ds.getActiveCount());
                holder(idle, BizMetrics.DRUID_IDLE, name).set(ds.getPoolingCount());
                holder(wait, BizMetrics.DRUID_WAIT, name).set(ds.getWaitThreadCount());
                holder(max, BizMetrics.DRUID_MAX, name).set(ds.getMaxActive());
            }
        } catch (Throwable t) {
            log.debug("Druid 指标采样失败（忽略）: {}", t.toString());
        }
    }

    private AtomicLong holder(ConcurrentHashMap<String, AtomicLong> map, String metric, String name) {
        return map.computeIfAbsent(name,
                k -> bizMetrics.gauge(metric, "datasource", k));
    }
}
