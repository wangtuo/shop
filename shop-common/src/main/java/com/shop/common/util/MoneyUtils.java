package com.shop.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 全系统金额工具：库表、DTO、计算一律使用 Long 分，禁止在业务代码中使用 double/float 表示金额。
 *
 * <p>分摊算法使用“最大余数法”，保证各明细分摊之和与总优惠完全相等，不丢 1 分钱。
 */
public final class MoneyUtils {

    /** 百分比/折扣计算精度（分 -> 内部放大 100 倍计算） */
    public static final BigDecimal CENT = new BigDecimal(100);

    private MoneyUtils() {
    }

    public static long yuanToFen(BigDecimal yuan) {
        if (yuan == null) {
            return 0L;
        }
        return yuan.multiply(CENT).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static BigDecimal fenToYuan(long fen) {
        return BigDecimal.valueOf(fen).divide(CENT, 2, RoundingMode.HALF_UP);
    }

    public static String fenToYuanString(long fen) {
        return fenToYuan(fen).toPlainString();
    }

    /**
     * 按权重把 total 分摊为多份，最后一份承接尾差，合计严格等于 total。
     *
     * @param total  待分摊总额（分），必须 ≥ 0
     * @param weights 各项权重（如原价金额），调用方保证非空且权重和 > 0
     * @return 与 weights 等长的分摊结果，每项 ≥ 0
     */
    public static List<Long> allocate(long total, List<Long> weights) {
        if (total < 0) {
            throw new IllegalArgumentException("待分摊金额不能为负");
        }
        if (weights == null || weights.isEmpty()) {
            return List.of();
        }
        long weightSum = weights.stream().mapToLong(Long::longValue).sum();
        if (weightSum <= 0) {
            throw new IllegalArgumentException("权重和必须大于 0");
        }
        List<Long> result = new ArrayList<>(weights.size());
        long allocated = 0;
        long[] remainders = new long[weights.size()];
        for (int i = 0; i < weights.size(); i++) {
            long share = total * weights.get(i) / weightSum;
            remainders[i] = total * weights.get(i) % weightSum;
            result.add(share);
            allocated += share;
        }
        // 最大余数法修正尾差
        long left = total - allocated;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < remainders.length; i++) {
            order.add(i);
        }
        order.sort((a, b) -> Long.compare(remainders[b], remainders[a]));
        for (int k = 0; k < left; k++) {
            int idx = order.get(k % order.size());
            result.set(idx, result.get(idx) + 1);
        }
        return result;
    }

    public static long sum(Collection<Long> values) {
        return values == null ? 0L : values.stream().mapToLong(Long::longValue).sum();
    }

    public static long nonNegative(long v) {
        return Math.max(v, 0L);
    }
}
