package com.shop.marketing.mq;

import com.shop.api.marketing.enums.SeckillOpType;
import com.shop.api.marketing.event.SeckillEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.util.JsonUtils;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.mq.MqListener;
import com.shop.marketing.activity.entity.SeckillOrder;
import com.shop.marketing.activity.mapper.SeckillOrderMapper;
import com.shop.marketing.common.entity.StockReconcileLog;
import com.shop.marketing.common.mapper.StockReconcileLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SECKILL_EVENT 消费闭环（W4-4/B4，消费组 cg_marketing_seckill，全量订阅 tag 1||2||3）。
 *
 * <p><b>只做监控闭环与修复触发，绝不重复执行业务扣减</b>：锁定/扣减/释放均已在
 * {@code SeckillService} 同步链路（建单 TCC / 支付 / 取消）以 DB CAS 完成，
 * 事件由同事务 outbox 投递。本消费者：
 * <ul>
 *   <li>tag=1 锁定：登记监控心跳（内存指标，不逐单落库），并核对 t_seckill_order 为 0已锁定；</li>
 *   <li>tag=2 扣减：DB 状态应为 1已扣减；</li>
 *   <li>tag=3 释放：DB 状态应为 2已释放；</li>
 * </ul>
 * 状态与事件不一致只落 t_stock_reconcile_log（scope=1，action=0 告警），不反向改状态——
 * 权威以同步 DB CAS 为准，偏差由 SeckillReconcileJob 三级自愈收口。
 * eventId 经 {@link MqConsumeTemplate} 落 t_marketing_mq_consume 幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillEventListener implements MqListener<SeckillEvent> {

    /** 事件类型对应的 t_seckill_order 期望状态：1锁→0、2扣→1、3释→2 */
    private static final Map<Integer, Integer> EXPECTED_STATUS = Map.of(1, 0, 2, 1, 3, 2);

    private final MqConsumeTemplate consumeTemplate;
    private final SeckillOrderMapper seckillOrderMapper;
    private final StockReconcileLogMapper reconcileLogMapper;
    private final IdGenerator idGenerator;

    private final AtomicLong lockHeartbeats = new AtomicLong();

    @Override
    public String topic() {
        return MqTopics.SECKILL_EVENT;
    }

    @Override
    public String tag() {
        return "1||2||3";
    }

    @Override
    public String consumerGroup() {
        return "cg_marketing_seckill";
    }

    @Override
    public Class<SeckillEvent> type() {
        return SeckillEvent.class;
    }

    @Override
    public void onMessage(SeckillEvent e) {
        if (e == null || e.getType() == null || SeckillOpType.of(e.getType()) == null) {
            log.warn("秒杀事件类型非法，ACK 丢弃 event={}", e);
            return;
        }
        consumeTemplate.runOnce(e.getEventId(), topic(), e.getOrderNo(), () -> monitor(e));
    }

    /** 仅监控核对，不做任何库存/状态写操作。 */
    private void monitor(SeckillEvent e) {
        if (e.getType() == SeckillOpType.LOCK.getCode()) {
            // 监控点心跳，不逐单落库；悬挂预占由 SeckillAutoEndJob/对账扫描发现后落 action=3
            lockHeartbeats.incrementAndGet();
        }
        Integer expected = EXPECTED_STATUS.get(e.getType());
        // R4-25：一订单可含多 SKU，t_seckill_order 每 (orderNo,skuId) 一行，必须按 SKU 精确定位，
        // 否则多 SKU 单 selectOne 会抛 TooManyResultsException
        SeckillOrder order = seckillOrderMapper.selectListByOrderNo(e.getOrderNo()).stream()
                .filter(r -> Objects.equals(r.getSkuId(), e.getSkuId()))
                .findFirst().orElse(null);
        if (order == null) {
            alert(e, null, "t_seckill_order 缺失秒杀订单行");
            return;
        }
        if (order.getStatus() == null || order.getStatus() != expected) {
            alert(e, order.getStatus(), "事件与 DB 状态不一致（不反向改状态，等待对账自愈）");
        }
    }

    private void alert(SeckillEvent e, Integer dbStatus, String reason) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("reason", reason);
        metric.put("eventType", e.getType());
        metric.put("orderNo", e.getOrderNo());
        metric.put("activityId", e.getActivityId());
        metric.put("skuId", e.getSkuId());
        metric.put("dbStatus", dbStatus);
        metric.put("qty", e.getQty());
        log.error("[P1告警] 秒杀事件监控偏差 type={} orderNo={} activityId={} skuId={} dbStatus={} expected={} {}",
                e.getType(), e.getOrderNo(), e.getActivityId(), e.getSkuId(), dbStatus,
                EXPECTED_STATUS.get(e.getType()), reason);
        StockReconcileLog row = new StockReconcileLog();
        row.setId(idGenerator.nextId());
        row.setScope(1);
        row.setActivityId(e.getActivityId());
        row.setSkuId(e.getSkuId());
        row.setDeviation(0L);
        row.setAction(0);
        row.setMetricBefore(JsonUtils.toJson(metric));
        row.setCreateTime(LocalDateTime.now());
        reconcileLogMapper.insert(row);
    }

    public long getLockHeartbeats() {
        return lockHeartbeats.get();
    }
}
