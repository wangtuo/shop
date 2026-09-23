package com.shop.api.user.enums;

import java.math.BigDecimal;

/**
 * 会员等级常量与等级权益工具。
 *
 * <p>成长区间 / 折扣 / 积分倍率：
 * <table>
 *   <tr><th>等级</th><th>成长值区间</th><th>折扣</th><th>积分倍率</th></tr>
 *   <tr><td>L0 新会员</td><td>0 - 99</td><td>1.00</td><td>1</td></tr>
 *   <tr><td>L1 银卡会员</td><td>100 - 999</td><td>0.98</td><td>1.1</td></tr>
 *   <tr><td>L2 金卡会员</td><td>1000 - 4999</td><td>0.95</td><td>1.5</td></tr>
 *   <tr><td>L3 白金会员</td><td>5000 - 19999</td><td>0.92</td><td>2</td></tr>
 *   <tr><td>L4 钻石会员</td><td>20000+</td><td>0.90</td><td>3</td></tr>
 * </table>
 *
 * <p>规则来源：design.md 2.1.2 用户等级体系、CONTRACTS.md §4。
 */
public final class MemberLevels {

    /** L0 新会员 */
    public static final int L0 = 0;

    /** L1 银卡会员 */
    public static final int L1 = 1;

    /** L2 金卡会员 */
    public static final int L2 = 2;

    /** L3 白金会员 */
    public static final int L3 = 3;

    /** L4 钻石会员 */
    public static final int L4 = 4;

    /** 各等级成长值下限（下标即等级） */
    private static final long[] GROWTH_FLOORS = {0L, 100L, 1000L, 5000L, 20000L};

    /** 各等级名称（下标即等级） */
    private static final String[] LEVEL_NAMES = {"新会员", "银卡会员", "金卡会员", "白金会员", "钻石会员"};

    /** 各等级折扣（下标即等级） */
    private static final BigDecimal[] DISCOUNTS = {
            new BigDecimal("1.00"),
            new BigDecimal("0.98"),
            new BigDecimal("0.95"),
            new BigDecimal("0.92"),
            new BigDecimal("0.90")
    };

    /** 各等级积分倍率（下标即等级） */
    private static final BigDecimal[] POINTS_RATES = {
            new BigDecimal("1"),
            new BigDecimal("1.1"),
            new BigDecimal("1.5"),
            new BigDecimal("2"),
            new BigDecimal("3")
    };

    private MemberLevels() {
    }

    /**
     * 按成长值计算会员等级。
     *
     * @param growth 成长值（负数按 0 处理）
     * @return 等级 0-4
     */
    public static int ofGrowth(long growth) {
        int level = L0;
        for (int i = 0; i < GROWTH_FLOORS.length; i++) {
            if (growth >= GROWTH_FLOORS[i]) {
                level = i;
            }
        }
        return level;
    }

    /**
     * 返回等级名称。
     *
     * @param level 等级 0-4
     * @return 等级中文名
     */
    public static String nameOf(int level) {
        validateLevel(level);
        return LEVEL_NAMES[level];
    }

    /**
     * 返回等级折扣，如 L1 返回 0.98。
     *
     * @param level 等级 0-4
     * @return 折扣系数（BigDecimal，精确两位小数）
     */
    public static BigDecimal discountOf(int level) {
        validateLevel(level);
        return DISCOUNTS[level];
    }

    /**
     * 返回积分倍率，如 L1 返回 1.1。
     *
     * @param level 等级 0-4
     * @return 积分倍率（BigDecimal）
     */
    public static BigDecimal pointsRateOf(int level) {
        validateLevel(level);
        return POINTS_RATES[level];
    }

    private static void validateLevel(int level) {
        if (level < L0 || level > L4) {
            throw new IllegalArgumentException("非法会员等级: " + level);
        }
    }
}
