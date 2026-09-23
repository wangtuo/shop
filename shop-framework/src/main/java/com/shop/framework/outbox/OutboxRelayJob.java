package com.shop.framework.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.framework.outbox.entity.OutboxMessage;
import com.shop.framework.outbox.mapper.OutboxMapper;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox 投递任务：扫描到期待投事件，逐条经独立事务 Bean {@link OutboxRelayDispatcher}
 * 同步投 MQ；成功 CAS 置位，失败指数退避重试，超上限置 status=2 挂起。
 *
 * <p><b>挂起消息的慢车道：</b>status=2 不再意味着只能等人工——{@link #requeueSuspended()}
 * 每分钟扫描冷却期已满的挂起消息，在 {@code shop.outbox.max-suspend}（默认 3 次）上限内
 * 自动重放回待投递，覆盖 broker 较长时间故障后的自愈场景；超过上限的视为真正死信，
 * 保持挂起并打 ERROR 告警，交对账/人工处理。运维仍可通过 {@link #requeue(long)} 手动放行。
 *
 * <p>多实例由 ShedLock 保证集群内单实例执行；节点宕机后锁到期自动漂移。
 * 投递语义：至少一次（消费端必须按业务单号幂等）。
 */
@Component
public class OutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);

    private final OutboxMapper outboxMapper;
    private final OutboxRelayDispatcher dispatcher;
    private final com.shop.framework.mq.MqMetrics mqMetrics;
    private final int batchSize;
    private final long suspendCooldownSeconds;
    private final int maxSuspend;

    /** O6：relay 轮次耗时指标；字段注入可空（3 参构造的单测空转）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shop.framework.metrics.BizMetrics bizMetrics;

    public OutboxRelayJob(OutboxMapper outboxMapper,
                          OutboxRelayDispatcher dispatcher,
                          @Value("${shop.outbox.batch-size:100}") int batchSize,
                          @Value("${shop.outbox.suspend-cooldown-seconds:300}") long suspendCooldownSeconds,
                          @Value("${shop.outbox.max-suspend:3}") int maxSuspend) {
        this(outboxMapper, dispatcher, null, batchSize, suspendCooldownSeconds, maxSuspend);
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public OutboxRelayJob(OutboxMapper outboxMapper,
                          OutboxRelayDispatcher dispatcher,
                          com.shop.framework.mq.MqMetrics mqMetrics,
                          @Value("${shop.outbox.batch-size:100}") int batchSize,
                          @Value("${shop.outbox.suspend-cooldown-seconds:300}") long suspendCooldownSeconds,
                          @Value("${shop.outbox.max-suspend:3}") int maxSuspend) {
        this.outboxMapper = outboxMapper;
        this.dispatcher = dispatcher;
        this.mqMetrics = mqMetrics == null ? new com.shop.framework.mq.MqMetrics(null) : mqMetrics;
        this.batchSize = batchSize;
        this.suspendCooldownSeconds = suspendCooldownSeconds;
        this.maxSuspend = maxSuspend;
    }

    /**
     * 每 2 秒一轮（可配 shop.outbox.relay-interval-ms）。lockAtMostFor 兜底节点宕机漂移；
     * 不设 lockAtLeastFor，避免无谓占用其他实例的扫描机会。
     */
    @Scheduled(fixedDelayString = "${shop.outbox.relay-interval-ms:2000}")
    @SchedulerLock(name = "mqOutboxRelay", lockAtMostFor = "PT2M")
    public void relay() {
        long start = System.nanoTime();
        String result = "success";
        try {
            List<OutboxMessage> due = outboxMapper.selectList(new LambdaQueryWrapper<OutboxMessage>()
                    .eq(OutboxMessage::getStatus, 0)
                    .le(OutboxMessage::getDeliverAt, LocalDateTime.now())
                    .orderByAsc(OutboxMessage::getId)
                    .last("LIMIT " + batchSize));
            for (OutboxMessage msg : due) {
                // 跨 Bean 调用保证 @Transactional(REQUIRES_NEW) 代理生效（禁止改为本类自调用）
                dispatcher.dispatch(msg);
            }
        } catch (RuntimeException e) {
            result = "error";
            throw e;
        } finally {
            if (bizMetrics != null) {
                bizMetrics.outboxRelay(result, System.nanoTime() - start);
            }
        }
    }

    /**
     * 慢车道：冷却期后自动重排挂起消息（有次数上限），并对超限死信告警。
     * 默认每 60s 一轮（shop.outbox.suspend-scan-ms），与快车道分离避免互相拖累。
     */
    @Scheduled(fixedDelayString = "${shop.outbox.suspend-scan-ms:60000}")
    @SchedulerLock(name = "mqOutboxSuspendRequeue", lockAtMostFor = "PT2M")
    public void requeueSuspended() {
        int requeued = outboxMapper.requeueSuspended(suspendCooldownSeconds, maxSuspend);
        if (requeued > 0) {
            log.warn("outbox 慢车道自动重放挂起事件 {} 条（冷却 {}s，挂起上限 {} 次），下一轮快车道续投",
                    requeued, suspendCooldownSeconds, maxSuspend);
        }
        long dead = outboxMapper.countDead(maxSuspend);
        if (dead > 0) {
            // 真正死信：自动重排次数耗尽，事件仍未投递成功，必须人工/对账介入，禁止静默
            log.error("outbox 存在 {} 条挂起事件已耗尽自动重放次数（{} 次），请对账后通过 requeue 人工放行",
                    dead, maxSuspend);
            mqMetrics.outboxSuspended(dead);
        }
    }

    /** 挂起消息重新放行（运维/对账入口）。 */
    public int requeue(long id) {
        return outboxMapper.requeue(id);
    }
}
