package com.shop.marketing.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.lock.DistributedLockTemplate;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillSku;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.activity.support.SeckillStockClient;
import com.shop.marketing.common.entity.StockReconcileLog;
import com.shop.marketing.common.mapper.StockReconcileLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀库存对账 + P1-2 三级自愈（W4-4 重写；ShedLock 单实例）。
 *
 * <p>权威：DB {@code total - locked - sold}。仅对在架秒杀场次（t_activity.status=1）逐 SKU 核对：
 * <ol>
 *   <li><b>缺键重建</b>（无阈值）：Redis 余量键缺失即以 DB {@code forceRebuild} 覆盖重建，
 *       不再 {@code continue}；落 scope=1 action=1。</li>
 *   <li><b>偏差两周期覆盖</b>：Redis != DB 连续 2 个对账周期（计数器
 *       mk:seckill:recon:deviation），以 DB 为准 Lua 原子 SET 覆盖并打 rebuilt 标，
 *       action=1；首周期仅 action=0 告警。</li>
 *   <li><b>持续漂移停售</b>：覆盖重建后下一周期仍偏（或 DB 可售为负即已超卖），
 *       CAS 场次 1→3 已取消（复用审核状态机「进行中→已取消」合法边，仅秒杀），
 *       全 SKU 置 SOLD_OUT 哨兵，P1 告警，action=2，等待人工介入。</li>
 * </ol>
 * 每次处置落 t_stock_reconcile_log；逐行短事务（每行独立插入，无类级大事务）。
 * 每行核对包 Redisson 锁 {@code mk:lock:seckill:reconcile:{activityId}:{skuId}}，
 * 与实时锁定/释放互斥；锁被占用则跳过本行下周期再议。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillReconcileJob {

    /** 连续偏差阈值：第 2 个周期仍不一致即 DB 覆盖重建 */
    private static final long DEVIATION_OVERRIDE_THRESHOLD = 2L;

    private final SeckillSkuMapper seckillSkuMapper;
    private final ActivityMapper activityMapper;
    private final SeckillStockClient stockClient;
    private final DistributedLockTemplate lockTemplate;
    private final StockReconcileLogMapper reconcileLogMapper;
    private final IdGenerator idGenerator;

    @Scheduled(cron = "0 */15 * * * ?")
    @SchedulerLock(name = "marketing:seckillReconcile", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void reconcile() {
        List<SeckillSku> all = seckillSkuMapper.selectList(new LambdaQueryWrapper<>());
        for (SeckillSku sku : all) {
            try {
                reconcileOne(sku);
            } catch (BizException busy) {
                // 自愈锁与业务锁竞争失败：本行跳过，下周期再议，不影响其他场次
                if (busy.getCode() == ErrorCode.TOO_MANY_REQUESTS.getCode()) {
                    log.info("秒杀对账行锁占用，跳过本周期 activityId={} skuId={}",
                            sku.getActivityId(), sku.getSkuId());
                } else {
                    throw busy;
                }
            }
        }
    }

    private void reconcileOne(SeckillSku sku) {
        Long activityId = sku.getActivityId();
        Long skuId = sku.getSkuId();
        Activity activity = activityMapper.selectById(activityId);
        if (activity == null || activity.getStatus() == null || activity.getStatus() != 1) {
            // 非在架（已结束/已取消/下架）：SOLD_OUT/缺键均为收口预期态，清掉残留计数，不做自愈
            stockClient.clearDeviation(activityId, skuId);
            return;
        }
        lockTemplate.execute("mk:lock:seckill:reconcile:" + activityId + ":" + skuId,
                (Runnable) () -> doReconcile(activityId, skuId, sku));
    }

    private void doReconcile(Long activityId, Long skuId, SeckillSku sku) {
        int dbAvailable = sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock();
        Long redisStock = stockClient.currentStock(activityId, skuId);

        if (dbAvailable < 0) {
            // 已超卖：任何覆盖都会掩盖事故，直接停售，不走覆盖重建
            log.error("[P1告警] 秒杀 DB 可售为负（疑似超卖），直接停售 activityId={} skuId={} db={}",
                    activityId, skuId, dbAvailable);
            stopSale(activityId, skuId, redisStock, dbAvailable, "DB_NEGATIVE_OVERSELL");
            return;
        }

        if (redisStock == null) {
            // 一级：缺键即重建（无阈值）
            boolean written = stockClient.forceRebuild(activityId, skuId, dbAvailable);
            if (written) {
                log.warn("秒杀库存键缺失，按 DB 重建 activityId={} skuId={} db={}", activityId, skuId, dbAvailable);
                stockClient.clearDeviation(activityId, skuId);
                logAction(activityId, skuId, 0L, 1, metric("KEY_MISSING", null, dbAvailable));
            } else {
                log.warn("秒杀库存键缺失但 Redis 不可用，重建推迟到下个周期 activityId={} skuId={}",
                        activityId, skuId);
            }
            return;
        }

        if (redisStock == dbAvailable) {
            // 一致：清零偏差计数与 rebuilt 标
            stockClient.clearDeviation(activityId, skuId);
            return;
        }

        long deviation = redisStock - dbAvailable;
        long cycles = stockClient.bumpDeviation(activityId, skuId);
        if (cycles < DEVIATION_OVERRIDE_THRESHOLD) {
            // 二级前置：首个偏差周期只告警计数（在途订单可能造成瞬时差），不动 Redis
            log.warn("秒杀库存对账不一致（第 {} 周期，仅告警）activityId={} skuId={} redis={} db={}",
                    cycles, activityId, skuId, redisStock, dbAvailable);
            logAction(activityId, skuId, deviation, 0, metric("DEVIATION_ALERT", redisStock, dbAvailable));
            return;
        }

        if (!stockClient.isRebuilt(activityId, skuId)) {
            // 二级：连续两周期偏差，以 DB 权威覆盖重建
            boolean written = stockClient.forceRebuild(activityId, skuId, dbAvailable);
            if (!written) {
                log.warn("秒杀库存 DB 覆盖重建失败（Redis 不可用），推迟到下个周期 activityId={} skuId={}",
                        activityId, skuId);
                return;
            }
            stockClient.markRebuilt(activityId, skuId);
            log.error("[P1告警] 秒杀库存连续 {} 周期偏差，已按 DB 覆盖重建 activityId={} skuId={} redis={} db={}",
                    cycles, activityId, skuId, redisStock, dbAvailable);
            logAction(activityId, skuId, deviation, 1, metric("DEVIATION_OVERRIDE", redisStock, dbAvailable));
            return;
        }

        // 三级：覆盖重建后下一周期仍偏——真实持续漂移（可能正在超卖），自动停售等待人工介入
        log.error("[P1告警] 秒杀库存覆盖重建后持续漂移，自动停售 activityId={} skuId={} redis={} db={}",
                activityId, skuId, redisStock, dbAvailable);
        stopSale(activityId, skuId, redisStock, dbAvailable, "PERSISTENT_DRIFT");
    }

    /**
     * 停售：CAS 活动 1→3（仅秒杀场次，复用状态机合法边），全 SKU Redis 置 SOLD_OUT。
     * CAS 失败（已被其他路径翻态）不重复处置；偏差计数在后续非在架扫描中清理。
     */
    private void stopSale(Long activityId, Long skuId, Long redisStock, int dbAvailable, String reason) {
        int rows = activityMapper.updateStatus(activityId, 1, 3);
        if (rows == 0) {
            log.warn("秒杀场次停售 CAS 失败（非在架态），跳过收口 activityId={} triggerSkuId={}", activityId, skuId);
            return;
        }
        List<SeckillSku> skus = seckillSkuMapper.selectList(new LambdaQueryWrapper<SeckillSku>()
                .eq(SeckillSku::getActivityId, activityId));
        for (SeckillSku s : skus) {
            stockClient.markSoldOut(activityId, s.getSkuId());
            stockClient.clearDeviation(activityId, s.getSkuId());
        }
        log.error("[P1告警] 秒杀场次已自动停售（置已取消+SOLD_OUT），等待人工介入 activityId={} reason={} skuCount={}",
                activityId, reason, skus.size());
        Map<String, Object> metric = metric(reason, redisStock, dbAvailable);
        metric.put("skuCount", skus.size());
        logAction(activityId, skuId, redisStock == null ? 0L : redisStock - dbAvailable, 2, metric);
    }

    private Map<String, Object> metric(String reason, Long redisStock, int dbAvailable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reason", reason);
        m.put("redis", redisStock);
        m.put("db", dbAvailable);
        return m;
    }

    /** 逐行短事务留痕：作业无类级事务，insert 自动提交，单行失败不阻断其他场次。 */
    private void logAction(Long activityId, Long skuId, long deviation, int action, Map<String, Object> metric) {
        StockReconcileLog row = new StockReconcileLog();
        row.setId(idGenerator.nextId());
        row.setScope(1);
        row.setActivityId(activityId);
        row.setSkuId(skuId);
        row.setDeviation(deviation);
        row.setAction(action);
        row.setMetricBefore(JsonUtils.toJson(metric));
        row.setCreateTime(LocalDateTime.now());
        reconcileLogMapper.insert(row);
    }
}
