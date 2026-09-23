package com.shop.order.idgen;

import com.shop.api.order.enums.OrderTypes;
import com.shop.framework.id.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 订单号生成器（CONTRACTS.md §6、design.md 5.1.2）。
 *
 * <pre>
 * YYMMDD + 业务类型2位 + 用户ID后4位 + 6位日内序列，共 18 位
 * 序列：Redis INCR shop:order:seq:{yyMMdd}:{bizType}，TTL 48h
 * 日内序列超过 999999 时用雪花 ID 兜底（长度不保证 18 位，全局唯一）
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    /** 6 位序列上限 */
    public static final long MAX_DAILY_SEQ = 999_999L;
    /** 序列 key 存活 48 小时 */
    public static final long SEQ_TTL_HOURS = 48L;

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private final RedissonClient redissonClient;
    private final IdGenerator idGenerator;

    /**
     * 生成订单号。
     *
     * @param orderType 订单类型 1-5（{@link OrderTypes}）
     * @param userId    下单用户 ID
     * @return 18 位订单号；序列溢出时返回雪花 ID 字符串
     */
    public String next(Integer orderType, Long userId) {
        return next(LocalDate.now(), orderType, userId);
    }

    /**
     * 基于指定日期生成（测试与跨天边界使用）。
     */
    public String next(LocalDate date, Integer orderType, Long userId) {
        String yyMmDd = date.format(YYMMDD);
        String bizCode = OrderTypes.bizTypeCodeOf(orderType);
        String userSuffix = userSuffix(userId);
        long seq = nextSequence(yyMmDd, bizCode);
        if (seq > MAX_DAILY_SEQ) {
            // 日内序列耗尽：雪花兜底，保证全局唯一不阻塞下单
            return Long.toString(idGenerator.nextId());
        }
        return yyMmDd + bizCode + userSuffix + String.format("%06d", seq);
    }

    /**
     * Redis INCR 取日内序列，首次写入时设置 48h TTL。
     */
    private long nextSequence(String yyMmDd, String bizCode) {
        String key = "shop:order:seq:" + yyMmDd + ":" + bizCode;
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long seq = counter.incrementAndGet();
        if (seq == 1L) {
            counter.expire(java.time.Duration.ofHours(SEQ_TTL_HOURS));
        }
        return seq;
    }

    /** 用户 ID 后 4 位，不足左侧补 0。 */
    static String userSuffix(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        String s = Long.toString(Math.abs(userId));
        return s.length() <= 4 ? String.format("%4s", s).replace(' ', '0') : s.substring(s.length() - 4);
    }
}
