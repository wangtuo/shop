package com.shop.aftersale.idgen;

import com.shop.framework.id.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 售后单号 / 退款单号生成。
 * <ul>
 *     <li>售后单号：AS + yyyyMMdd + 10 位日内序列（CONTRACTS.md §6），Redis INCR + TTL 48h，超界雪花兜底；</li>
 *     <li>退款单号：R + 17 位。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AftersaleNoGenerator {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long MAX_SEQ = 9_999_999_999L;

    private final ObjectProvider<RedissonClient> redissonProvider;
    private final IdGenerator idGenerator;

    public String nextAftersaleNo() {
        String day = LocalDate.now().format(DAY);
        long seq = nextSeq("shop:aftersale:seq:" + day);
        if (seq > MAX_SEQ) {
            return "AS" + day + String.format("%010d", idGenerator.nextId() % (MAX_SEQ + 1));
        }
        return "AS" + day + String.format("%010d", seq);
    }

    public String nextRefundNo() {
        return "R" + String.format("%017d", idGenerator.nextId() % 100_000_000_000_000_000L);
    }

    private long nextSeq(String key) {
        try {
            RedissonClient client = redissonProvider.getIfAvailable();
            if (client != null) {
                RAtomicLong counter = client.getAtomicLong(key);
                long seq = counter.incrementAndGet();
                if (seq == 1L) {
                    counter.expire(java.time.Duration.ofHours(48));
                }
                return seq;
            }
        } catch (Exception ignored) {
            // Redis 不可用时退化雪花
        }
        return idGenerator.nextId() % (MAX_SEQ + 1);
    }
}
