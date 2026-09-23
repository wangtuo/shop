package com.shop.framework.metrics;

import com.shop.framework.outbox.mapper.OutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Outbox 积压指标采样（O6）：每 30s 只读扫一次 t_mq_outbox（低频、只读、不带业务高基数标签）：
 * <ul>
 *   <li>{@code shop_outbox_pending{lane=fast|slow}}：status=0 待投递 / status=2 挂起条数；</li>
 *   <li>{@code shop_outbox_oldest_age_seconds{lane=fast|slow}}：
 *       fast=最老到期未投消息相对 deliver_at 的等待秒数；slow=挂起消息相对 update_time 的停留秒数。</li>
 * </ul>
 * 采样异常（表不存在/从库延迟等）只 debug，不影响应用；无数据时 Gauge 不更新（保留上次值）。
 */
@Component
public class OutboxMetricsSampler {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetricsSampler.class);

    public static final String LANE_FAST = "fast";
    public static final String LANE_SLOW = "slow";

    private final OutboxMapper outboxMapper;
    private final BizMetrics bizMetrics;

    private final AtomicLong pendingFast;
    private final AtomicLong pendingSlow;
    private final AtomicLong oldestFast;
    private final AtomicLong oldestSlow;

    @Autowired(required = false)
    public OutboxMetricsSampler(OutboxMapper outboxMapper, BizMetrics bizMetrics) {
        this.outboxMapper = outboxMapper;
        this.bizMetrics = bizMetrics == null ? new BizMetrics(null) : bizMetrics;
        this.pendingFast = this.bizMetrics.gauge(BizMetrics.OUTBOX_PENDING, "lane", LANE_FAST);
        this.pendingSlow = this.bizMetrics.gauge(BizMetrics.OUTBOX_PENDING, "lane", LANE_SLOW);
        this.oldestFast = this.bizMetrics.gauge(BizMetrics.OUTBOX_OLDEST_AGE_SECONDS, "lane", LANE_FAST);
        this.oldestSlow = this.bizMetrics.gauge(BizMetrics.OUTBOX_OLDEST_AGE_SECONDS, "lane", LANE_SLOW);
    }

    @Scheduled(fixedDelayString = "${shop.outbox.metrics-interval-ms:30000}", initialDelayString = "30000")
    public void sample() {
        try {
            pendingFast.set(outboxMapper.countPendingFast());
            pendingSlow.set(outboxMapper.countPendingSlow());
            Long fastAge = outboxMapper.oldestFastAgeSeconds();
            if (fastAge != null) {
                oldestFast.set(fastAge);
            }
            Long slowAge = outboxMapper.oldestSlowAgeSeconds();
            if (slowAge != null) {
                oldestSlow.set(slowAge);
            }
        } catch (Throwable t) {
            log.debug("outbox 指标采样失败（忽略）: {}", t.toString());
        }
    }
}
