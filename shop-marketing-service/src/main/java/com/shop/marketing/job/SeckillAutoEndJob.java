package com.shop.marketing.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.util.JsonUtils;
import com.shop.framework.id.IdGenerator;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillOrder;
import com.shop.marketing.activity.entity.SeckillSku;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.SeckillOrderMapper;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.activity.service.SeckillService;
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
 * 秒杀到点自动结束（W4-4 / B2 交付项，分钟级，ShedLock 单实例）。
 *
 * <p>扫描 type=10 / status=1 / auto_end=1 / end_time 已到的秒杀场次，逐场：
 * <ol>
 *   <li>CAS {@code updateStatus(id,1,2)}——影响行数 0（已被其他实例/路径翻态）即整体跳过，
 *       这是「双跑只释放一次」的幂等门槛；</li>
 *   <li>未支付预占统一释放：对全部 status=0 的 t_seckill_order 调
 *       {@link SeckillService#release(String)}（0→2 + DB 回补 + Redis 释放，本身幂等，
 *       每单独立短事务），每单落 t_stock_reconcile_log scope=2 action=3；</li>
 *   <li>Redis 收口（释放回补完成后执行，避免 INCR 破坏哨兵）：场次全部 SKU 余量键置
 *       SOLD_OUT（复用 {@link SeckillStockClient} 既有原语，不新写 Lua）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillAutoEndJob {

    private final ActivityMapper activityMapper;
    private final SeckillSkuMapper seckillSkuMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final SeckillStockClient stockClient;
    private final SeckillService seckillService;
    private final StockReconcileLogMapper reconcileLogMapper;
    private final IdGenerator idGenerator;

    @Scheduled(cron = "0 */1 * * * ?")
    @SchedulerLock(name = "marketing:seckillAutoEnd", lockAtMostFor = "PT3M", lockAtLeastFor = "PT10S")
    public void autoEnd() {
        List<Activity> due = activityMapper.selectSeckillDue();
        for (Activity activity : due) {
            endOne(activity);
        }
    }

    private void endOne(Activity activity) {
        Long activityId = activity.getId();
        // CAS 门槛：只有 1→2 成功的执行者做收口与释放，重复扫描/多实例并发全部幂等跳过
        if (activityMapper.updateStatus(activityId, 1, 2) == 0) {
            log.info("秒杀到点结束 CAS 未命中，跳过（已结束或已取消）activityId={}", activityId);
            return;
        }
        // 先释放未支付预占：release 原语会把 Redis 余量正常 INCR 回补（含缺键按 DB 重建），
        // 之后再统一置 SOLD_OUT——否则对哨兵键 INCR 会破坏 -1 收口值（E2E：再查恒为 SOLD_OUT）
        List<SeckillOrder> pending = seckillOrderMapper.selectList(new LambdaQueryWrapper<SeckillOrder>()
                .eq(SeckillOrder::getActivityId, activityId)
                .eq(SeckillOrder::getStatus, 0));
        int released = 0;
        for (SeckillOrder order : pending) {
            // release 幂等：0→2 CAS 失败即 no-op；每单走 Spring 代理独立短事务
            seckillService.release(order.getOrderNo());
            logReclaim(activityId, order.getSkuId(), order.getOrderNo(), order.getQty());
            released++;
        }

        List<SeckillSku> skus = seckillSkuMapper.selectList(new LambdaQueryWrapper<SeckillSku>()
                .eq(SeckillSku::getActivityId, activityId));
        for (SeckillSku sku : skus) {
            stockClient.markSoldOut(activityId, sku.getSkuId());
        }
        log.info("秒杀场次到点自动结束 activityId={} skuCount={} 释放未支付预占 {} 单",
                activityId, skus.size(), released);
    }

    private void logReclaim(Long activityId, Long skuId, String orderNo, Integer qty) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("reason", "AUTO_END_RELEASE");
        metric.put("orderNo", orderNo);
        metric.put("qty", qty);
        StockReconcileLog row = new StockReconcileLog();
        row.setId(idGenerator.nextId());
        row.setScope(2);
        row.setActivityId(activityId);
        row.setSkuId(skuId);
        row.setDeviation(0L);
        row.setAction(3);
        row.setMetricBefore(JsonUtils.toJson(metric));
        row.setCreateTime(LocalDateTime.now());
        reconcileLogMapper.insert(row);
    }
}
