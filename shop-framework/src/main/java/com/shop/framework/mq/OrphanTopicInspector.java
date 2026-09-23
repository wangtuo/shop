package com.shop.framework.mq;

import com.shop.framework.outbox.mapper.OutboxMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 孤儿 topic 巡检（C15 / C17）：经 ShedLock 集群单实例，在启动后及每日执行一次
 * 「本服务发出 topic（outbox 实际发出）vs 本服务订阅 topic」核对。
 *
 * <p><b>能力边界（不假实现）：</b>RocketMQ 5.x gRPC 客户端（rocketmq-client-java 5.0.7）
 * 不提供「查询某 topic 全部订阅组/在线消费实例」的管理 API，本实例无法判定<b>仓库外</b>
 * 消费方是否存在。因此：
 * <ul>
 *   <li>「本服务发出但本服务未订阅」的 topic（跨服务事件本就如此）只记日志，标记为
 *       <i>订阅状态不可在本实例判定</i>，<b>不</b>打 {@code shop_mq_topic_no_subscriber}；</li>
 *   <li>开放事件的最终去向以 ACCEPTANCE.md「开放事件去向声明表」为准（C17 发布门禁），
 *       框架<b>不做删 topic、不加假消费者</b>；</li>
 *   <li>{@link #querySubscriberCounts()} 预留给未来 broker 管理客户端：只有它返回非 null
 *       且某 topic 订阅组数确为 0 时，才递增 {@code shop_mq_topic_no_subscriber{topic}}。</li>
 * </ul>
 */
@Component
public class OrphanTopicInspector {

    private static final Logger log = LoggerFactory.getLogger(OrphanTopicInspector.class);

    private final MqProperties properties;
    private final List<MqListener<?>> listeners;
    /** ObjectFactory：无 MQ/无 outbox 表的极简环境也允许本 Bean 存在（延时取 Bean）。 */
    private final ObjectFactory<OutboxMapper> outboxMapperFactory;
    private final MqMetrics metrics;

    private volatile boolean capabilityWarned;

    public OrphanTopicInspector(MqProperties properties,
                                List<MqListener<?>> listeners,
                                ObjectFactory<OutboxMapper> outboxMapperFactory,
                                MqMetrics metrics) {
        this.properties = properties;
        this.listeners = listeners;
        this.outboxMapperFactory = outboxMapperFactory;
        this.metrics = metrics;
    }

    /** 启动 45s 后首轮（避开注册高峰），之后每日一轮；集群内 ShedLock 保证单实例执行。 */
    @Scheduled(initialDelayString = "${shop.mq.orphan-check-initial-delay-ms:45000}",
            fixedDelayString = "${shop.mq.orphan-check-interval-ms:86400000}")
    @SchedulerLock(name = "mqOrphanTopicInspect", lockAtMostFor = "PT5M")
    public void inspect() {
        if (!properties.isEnabled()) {
            return;
        }
        Set<String> subscribed = new TreeSet<>();
        for (MqListener<?> listener : listeners) {
            subscribed.add(listener.topic());
        }
        Set<String> published = publishedTopics();

        Map<String, Integer> subscriberCounts = querySubscriberCounts(published);
        if (subscriberCounts == null) {
            // 能力不足降级：只记录不可判定清单，绝不用本地视角冒充全局结论
            Set<String> unverifiable = new TreeSet<>(published);
            unverifiable.removeAll(subscribed);
            if (!capabilityWarned) {
                log.warn("MQ 孤儿 topic 巡检降级：当前 RocketMQ 5.x gRPC 客户端无订阅组查询 API，"
                        + "无法判定仓库外消费方；以下 {} 个本服务发出 topic 的订阅状态不可在本实例判定，"
                        + "以 ACCEPTANCE.md 开放事件去向声明表为准，不做删 topic：{}",
                        unverifiable.size(), unverifiable);
                capabilityWarned = true;
            } else {
                log.info("MQ 孤儿 topic 巡检（降级模式）：不可判定 topic {} 个：{}",
                        unverifiable.size(), unverifiable);
            }
            return;
        }

        for (Map.Entry<String, Integer> e : subscriberCounts.entrySet()) {
            if (e.getValue() != null && e.getValue() == 0) {
                log.error("MQ topic {} 无任何订阅组，打 shop_mq_topic_no_subscriber 指标（不自动删 topic）",
                        e.getKey());
                metrics.topicNoSubscriber(e.getKey());
            }
        }
    }

    private Set<String> publishedTopics() {
        Set<String> topics = new LinkedHashSet<>();
        try {
            List<String> rows = outboxMapperFactory.getObject().selectDistinctTopics();
            if (rows != null) {
                topics.addAll(rows);
            }
        } catch (Exception e) {
            // outbox 表缺失/库不可达不阻断巡检：本轮无发出侧数据即可
            log.debug("读取 outbox 发出 topic 清单失败，本轮跳过发出侧核对", e);
        }
        return topics;
    }

    /**
     * 查询给定 topic 各自的订阅组数量。
     *
     * @return topic → 订阅组数；返回 null 表示当前客户端不具备该查询能力（5.0.7 即如此），
     *         调用方据此降级为日志，不产生 no_subscriber 指标
     */
    protected Map<String, Integer> querySubscriberCounts(Set<String> topics) {
        return null;
    }
}
