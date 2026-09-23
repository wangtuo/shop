package com.shop.marketing.activity.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.api.marketing.enums.SeckillOpType;
import com.shop.api.marketing.event.SeckillEvent;
import com.shop.common.constant.MqTopics;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.id.IdGenerator;
import com.shop.framework.outbox.OutboxPublisher;
import com.shop.framework.ratelimit.RateLimit;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.entity.SeckillOrder;
import com.shop.marketing.activity.entity.SeckillSku;
import com.shop.marketing.activity.entity.SeckillUserBuy;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.mapper.SeckillOrderMapper;
import com.shop.marketing.activity.mapper.SeckillSkuMapper;
import com.shop.marketing.activity.mapper.SeckillUserBuyMapper;
import com.shop.marketing.activity.support.SeckillStockClient;
import com.shop.api.marketing.dto.CalcItem;
import com.shop.common.util.JsonUtils;
import com.shop.marketing.activity.support.ActivityRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 秒杀服务（design.md 4.1.3 / 4.2.3）：Redis Lua 原子预占 → DB 条件更新双保险，
 * 支付扣减、取消回补，事件走事务内 outbox（P1-1），DB/Redis 可对账。
 *
 * <p>P1-5：多 SKU 单条 Lua 批量预占，全部够才扣；DB 条件更新或落单失败时按已扣明细逐笔回补 Redis。
 * P2-4：回补遇 Redis 键缺失不凭空造库存，按 DB 可售量重建。
 * P2-7：每用户每场限参与的并发强约束见 {@link SeckillUserBuyMapper} 的行锁条件占件。</p>
 *
 * <p><b>R4-25 多 SKU 修复：</b>原持久层每订单仅一行（uk_order_no）且每用户每场仅一行
 * （V3 uk_activity_user），但 P1-5 链路按多 SKU 设计——多 SKU 单必在第二行/第二个 LOCK
 * outbox 事件处撞唯一键 100% 回滚落不了单；uk_activity_user 还使可配置 perUserBuyLimit&gt;1
 * 的多单累计形同虚设。现模型：t_seckill_order 每 (order_no, sku_id) 一行，确认/释放逐 SKU
 * 扣减与回补；限购改由 t_seckill_user_buy 计数行原子占件；逐 SKU 事件 outbox 键带
 * {@code #sku{skuId}} 维度（消息体 orderNo 不变，消费侧按 eventId 幂等）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    /** outbox bizKey 的 SKU 维度后缀：同单多 SKU 的 LOCK/DEDUCT/RELEASE 各自唯一。 */
    private static final String SKU_KEY_PREFIX = "#sku";

    private final ActivityMapper activityMapper;
    private final SeckillSkuMapper seckillSkuMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final SeckillUserBuyMapper seckillUserBuyMapper;
    private final SeckillStockClient stockClient;
    private final OutboxPublisher outboxPublisher;
    private final IdGenerator idGenerator;

    /** 一个 SKU 的预占上下文（Redis 扣减与 DB/回补共用）。 */
    private record Acquire(Long skuId, Long skuPkId, int qty, int dbAvailable) {
    }

    /**
     * 下单锁定：售罄快速失败；Redis 多 key 原子预占，DB/落单任一失败按全部已扣明细回补 Redis。
     * 本方法运行在 MarketingAppService 的 orderNo 分布式锁 + 独立事务内（P1-6：提交后才释放锁）。
     */
    @Transactional(rollbackFor = Exception.class)
    @RateLimit(prefix = "seckill:lock", key = "#userId + ':' + #activityId", permits = 1, windowSeconds = 3,
            message = "操作过于频繁，请稍后再试")
    public void lock(Long userId, Long activityId, String orderNo, List<CalcItem> items) {
        Activity activity = activityMapper.selectById(activityId);
        validateOngoing(activity);
        // 按 SKU 汇总数量
        Map<Long, Integer> qtyBySku = new LinkedHashMap<>();
        int requestQty = 0;
        for (CalcItem item : items) {
            int q = item.getQty() == null ? 0 : item.getQty();
            requestQty += q;
            qtyBySku.merge(item.getSkuId(), q, Integer::sum);
        }
        if (requestQty <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "秒杀下单数量非法");
        }
        // W4-4/B4 C24：每用户累计可购件数（缺省 1，存量活动不回退）
        int limit = resolvePerUserBuyLimit(activity);
        // 单笔即超上限：触碰 Redis/DB 前快速失败（累计超限由后续计数行原子占件判定）
        if (requestQty > limit) {
            log.info("秒杀超限购拒绝（单笔件数超限）activityId={} userId={} request={} limit={}",
                    activityId, userId, requestQty, limit);
            throw new BizException(ErrorCode.LIMIT_PURCHASE,
                    "超出本秒杀活动每用户限购数量：" + limit + " 件");
        }

        // 先把所有 SKU 配置查齐：任一不存在直接失败，此时尚未触碰 Redis（P1-5）
        List<Acquire> acquires = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : qtyBySku.entrySet()) {
            SeckillSku sku = loadSku(activityId, e.getKey());
            acquires.add(new Acquire(e.getKey(), sku.getId(), e.getValue(), available(sku)));
        }

        // 多 key 单条 Lua 原子预占：任一不足/未初始化，所有 key 余量不变
        long ret = batchAcquireWithFallback(activityId, acquires);
        if (ret == SeckillStockClient.SOLD_OUT) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH, "秒杀商品已售罄");
        }

        try {
            // Redis 预占成功后逐 SKU 做 DB 条件更新
            for (Acquire ac : acquires) {
                if (seckillSkuMapper.lockStock(ac.skuPkId(), ac.qty()) == 0) {
                    throw new BizException(ErrorCode.STOCK_NOT_ENOUGH, "秒杀商品已售罄");
                }
            }
            // 每用户每场次原子占件（行锁条件更新）：超限在落库前拒绝；DB 锁随事务回滚，Redis 显式回补
            claimUserBuy(userId, activityId, requestQty, limit);
            // 逐 SKU 落单行 + 登记 LOCK 事件（R4-25：每 SKU 一行，事件键带 SKU 维度）
            for (Acquire ac : acquires) {
                SeckillOrder order = new SeckillOrder();
                order.setActivityId(activityId);
                order.setSkuId(ac.skuId());
                order.setUserId(userId);
                order.setOrderNo(orderNo);
                order.setQty(ac.qty());
                order.setStatus(0);
                try {
                    seckillOrderMapper.insert(order);
                } catch (DuplicateKeyException e) {
                    // uk_order_sku(order_no, sku_id) 冲突只可能是同单重复提交
                    log.info("秒杀订单重复提交，按幂等拒绝 orderNo={} skuId={} userId={}", orderNo, ac.skuId(), userId);
                    throw new BizException(ErrorCode.REPEAT_SUBMIT, "秒杀订单处理中，请勿重复提交");
                }
                publishEvent(activityId, ac.skuId(), userId, orderNo,
                        SeckillOpType.LOCK.getCode(), (long) ac.qty());
            }
        } catch (RuntimeException ex) {
            // DB 判定不足 / 占件超限 / 落单 UK 冲突：本事务回滚 DB，Redis 必须逐笔回补，否则库存泄漏（P1-5）
            compensateRedis(activityId, acquires);
            throw ex;
        }
    }

    /**
     * 每用户每场次原子占件（R4-25，替代原 uk_activity_user 硬 1 单）。
     * 条件更新命中即占件成功；计数行不存在时插入首行（并发首单在 UK 处互斥，负方重试条件更新）；
     * 条件更新最终仍 0 行即累计超限购。
     */
    private void claimUserBuy(Long userId, Long activityId, int qty, int limit) {
        if (qty > limit) {
            throw new BizException(ErrorCode.LIMIT_PURCHASE,
                    "超出本秒杀活动每用户限购数量：" + limit + " 件");
        }
        if (seckillUserBuyMapper.claim(activityId, userId, qty, limit) == 1) {
            return;
        }
        SeckillUserBuy existing = seckillUserBuyMapper.selectActive(activityId, userId);
        if (existing == null) {
            SeckillUserBuy row = new SeckillUserBuy();
            row.setId(idGenerator.nextId());
            row.setActivityId(activityId);
            row.setUserId(userId);
            row.setTotalQty(qty);
            try {
                seckillUserBuyMapper.insert(row);
                return;
            } catch (DuplicateKeyException e) {
                // 并发首单：对手已建行，落回条件占件
                log.info("秒杀占件计数行并发已建，重试条件占件 activityId={} userId={}", activityId, userId);
            }
        }
        if (seckillUserBuyMapper.claim(activityId, userId, qty, limit) != 1) {
            log.info("秒杀超限购拒绝 activityId={} userId={} request={} limit={}", activityId, userId, qty, limit);
            throw new BizException(ErrorCode.LIMIT_PURCHASE,
                    "超出本秒杀活动每用户限购数量：" + limit + " 件");
        }
    }

    private long batchAcquireWithFallback(Long activityId, List<Acquire> acquires) {
        List<Long> skuIds = acquires.stream().map(Acquire::skuId).toList();
        List<Integer> qtys = acquires.stream().map(Acquire::qty).toList();
        long ret = stockClient.tryAcquireBatch(activityId, skuIds, qtys);
        if (ret == SeckillStockClient.NOT_INITIALIZED) {
            // 任一键缺失：按 DB 可售批量初始化（setIfAbsent 不覆盖已有键），整批重试一次
            for (Acquire ac : acquires) {
                stockClient.initStock(activityId, ac.skuId(), ac.dbAvailable());
            }
            ret = stockClient.tryAcquireBatch(activityId, skuIds, qtys);
        }
        return ret;
    }

    /** 失败回补：对本单已预占的每个 key 逐笔回补；键缺失（flush）只告警不造库存。 */
    private void compensateRedis(Long activityId, List<Acquire> acquires) {
        for (Acquire ac : acquires) {
            stockClient.release(activityId, ac.skuId(), ac.qty());
        }
    }

    private SeckillSku loadSku(Long activityId, Long skuId) {
        SeckillSku sku = seckillSkuMapper.selectOne(new LambdaQueryWrapper<SeckillSku>()
                .eq(SeckillSku::getActivityId, activityId)
                .eq(SeckillSku::getSkuId, skuId));
        if (sku == null) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "秒杀商品不存在：" + skuId);
        }
        return sku;
    }

    private int available(SeckillSku sku) {
        return sku.getTotalStock() - sku.getLockedStock() - sku.getSoldStock();
    }

    /** 支付成功：锁定 → 扣减（幂等；R4-25：逐 SKU 行全部翻转并逐 SKU 扣减）。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String orderNo) {
        List<SeckillOrder> rows = seckillOrderMapper.selectListByOrderNo(orderNo);
        if (rows.isEmpty()) {
            return;
        }
        List<SeckillOrder> lockedRows = rows.stream().filter(r -> r.getStatus() != null && r.getStatus() == 0).toList();
        if (lockedRows.isEmpty()) {
            return;
        }
        // CAS：该单全部行 0→1，0 行说明已被并发确认
        if (seckillOrderMapper.updateStatus(orderNo, 0, 1) == 0) {
            return;
        }
        for (SeckillOrder row : lockedRows) {
            seckillSkuMapper.deductStock(skuPkId(row.getActivityId(), row.getSkuId()), row.getQty());
            publishEvent(row.getActivityId(), row.getSkuId(), row.getUserId(), orderNo,
                    SeckillOpType.DEDUCT.getCode(), (long) row.getQty());
        }
    }

    /** 取消/超时：锁定回可售（幂等；R4-25：逐 SKU DB 回补 + Redis 释放，未找到锁定记录按成功返回）。 */
    @Transactional(rollbackFor = Exception.class)
    public void release(String orderNo) {
        List<SeckillOrder> rows = seckillOrderMapper.selectListByOrderNo(orderNo);
        if (rows.isEmpty()) {
            return;
        }
        List<SeckillOrder> lockedRows = rows.stream().filter(r -> r.getStatus() != null && r.getStatus() == 0).toList();
        if (lockedRows.isEmpty()) {
            return;
        }
        // CAS：该单全部行 0→2，0 行说明已被并发释放/确认
        if (seckillOrderMapper.updateStatus(orderNo, 0, 2) == 0) {
            return;
        }
        Long activityId = lockedRows.get(0).getActivityId();
        Long userId = lockedRows.get(0).getUserId();
        int releasedQty = 0;
        for (SeckillOrder row : lockedRows) {
            seckillSkuMapper.releaseStock(skuPkId(row.getActivityId(), row.getSkuId()), row.getQty());
            long redisRet = stockClient.release(row.getActivityId(), row.getSkuId(), row.getQty());
            if (redisRet == SeckillStockClient.RELEASE_KEY_MISSING) {
                // P2-4：键缺失未凭空 INCR，按刚提交的 DB 可售量重建（setIfAbsent 双保险）
                SeckillSku sku = loadSku(row.getActivityId(), row.getSkuId());
                stockClient.initStock(row.getActivityId(), row.getSkuId(), available(sku));
            }
            publishEvent(row.getActivityId(), row.getSkuId(), row.getUserId(), orderNo,
                    SeckillOpType.RELEASE.getCode(), (long) row.getQty());
            releasedQty += row.getQty();
        }
        // 释放的件数回减限购计数，口径等于原 SUM(status IN 0,1)，用户超时未支付后可再次参与
        seckillUserBuyMapper.releaseQty(activityId, userId, releasedQty);
    }

    private Long skuPkId(Long activityId, Long skuId) {
        SeckillSku sku = seckillSkuMapper.selectOne(new LambdaQueryWrapper<SeckillSku>()
                .eq(SeckillSku::getActivityId, activityId)
                .eq(SeckillSku::getSkuId, skuId));
        return sku == null ? -1L : sku.getId();
    }

    private void validateOngoing(Activity activity) {
        if (activity == null || activity.getStatus() != 1) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "秒杀活动不存在或未开始");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new BizException(ErrorCode.ACTIVITY_NOT_AVAILABLE, "不在秒杀活动时间内");
        }
    }

    /** W4-4/B4 C24：解析每用户可购件数；rule_json 缺字段/解析失败均回退默认 1（存量活动兼容）。 */
    private int resolvePerUserBuyLimit(Activity activity) {
        if (activity.getRuleJson() == null || activity.getRuleJson().isBlank()) {
            return 1;
        }
        try {
            ActivityRule rule = JsonUtils.fromJson(activity.getRuleJson(), ActivityRule.class);
            if (rule != null && rule.getPerUserBuyLimit() != null && rule.getPerUserBuyLimit() > 0) {
                return rule.getPerUserBuyLimit();
            }
        } catch (RuntimeException ex) {
            log.warn("秒杀活动 rule_json 解析限购失败，按默认 1 activityId={}", activity.getId(), ex);
        }
        return 1;
    }

    private void publishEvent(Long activityId, Long skuId, Long userId, String orderNo, int type, Long qty) {
        SeckillEvent event = SeckillEvent.builder()
                .activityId(activityId).skuId(skuId).userId(userId).orderNo(orderNo)
                .type(type).qty(qty).build();
        event.setBizNo(orderNo);
        // P1-1：事件与 DB 库存/订单状态同事务提交，由 outbox relay 至少一次投递，消费端按 eventId 幂等。
        // R4-25：同单多 SKU 同 tag 事件以 orderNo#sku{skuId} 去重，避免撞 uk_topic_tag_bizkey。
        outboxPublisher.publish(MqTopics.SECKILL_EVENT, String.valueOf(type), event,
                orderNo + SKU_KEY_PREFIX + skuId);
    }
}
