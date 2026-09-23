package com.shop.pay.support;

import com.shop.framework.id.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 支付域单号生成器（CONTRACTS.md §6）。
 *
 * <ul>
 *   <li>支付单号：{@code P}+17 位（yyMMdd + 11 位日内序列），共 18 位；</li>
 *   <li>退款单号：{@code R}+17 位；</li>
 *   <li>对账批次：{@code RC}+yyMMdd+6 位序列。</li>
 * </ul>
 * 序列取 Redis INCR，TTL 48h；极端情况下用雪花 ID 兜底（全局唯一即可）。
 */
@Component
@RequiredArgsConstructor
public class PayNoGenerator {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final long SEQ_TTL_HOURS = 48L;

    private final RedissonClient redissonClient;
    private final IdGenerator idGenerator;

    public String payNo() {
        return "P" + date() + seq11("pay");
    }

    public String refundNo() {
        return "R" + date() + seq11("refund");
    }

    public String batchNo() {
        long seq = incr("recon", 1_000_000L);
        return "RC" + date() + String.format("%06d", seq % 1_000_000L);
    }

    private String date() {
        return LocalDate.now().format(YYMMDD);
    }

    private String seq11(String biz) {
        long seq = incr(biz, 100_000_000_000L);
        if (seq <= 0 || seq >= 100_000_000_000L) {
            // 极端兜底：雪花尾 11 位，全局唯一优先
            return String.format("%011d", Math.floorMod(idGenerator.nextId(), 100_000_000_000L));
        }
        return String.format("%011d", seq);
    }

    private long incr(String biz, long max) {
        try {
            String key = "shop:pay:seq:" + biz + ":" + date();
            RAtomicLong counter = redissonClient.getAtomicLong(key);
            long v = counter.incrementAndGet();
            if (v == 1L) {
                counter.expire(SEQ_TTL_HOURS, TimeUnit.HOURS);
            }
            return v;
        } catch (Exception e) {
            // Redis 不可用时雪花兜底，绝不阻塞下单
            return Math.floorMod(idGenerator.nextId(), max) + max;
        }
    }
}
