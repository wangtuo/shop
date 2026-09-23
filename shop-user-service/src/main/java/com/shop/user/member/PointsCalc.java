package com.shop.user.member;

import com.shop.api.user.enums.MemberLevels;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 用户域积分/成长值规则计算器（纯函数，design.md 2.1.2 / 2.1.3 / 2.2.2）。
 *
 * <ul>
 *   <li>签到：第 1 天 5、第 2 天 10 … 第 6 天 30、第 7 天 50；中断重置，之后每天 50（单日上限 50）；</li>
 *   <li>消费返积分：实付金额（元） × 等级积分倍率，四舍五入取整；</li>
 *   <li>消费成长值：实付 1 元 = 1，四舍五入取整；</li>
 *   <li>评价：20/条，带图 +10，每日上限 100；分享 10/次，每日上限 20；</li>
 *   <li>年末成长值按 80% 折算（向下取整），保底当前等级成长下限，不允许降级。</li>
 * </ul>
 */
public final class PointsCalc {

    /** 积分有效期天数 */
    public static final long POINTS_VALID_DAYS = 365L;

    /** 评价基础积分 */
    public static final long COMMENT_BASE_POINTS = 20L;
    /** 评价带图额外积分 */
    public static final long COMMENT_IMAGE_POINTS = 10L;
    /** 评价每日积分上限 */
    public static final long COMMENT_DAILY_CAP = 100L;

    /** 分享单次积分 */
    public static final long SHARE_POINTS = 10L;
    /** 分享每日积分上限 */
    public static final long SHARE_DAILY_CAP = 20L;

    /** 签到每日积分上限（第 7 天起 50） */
    public static final long SIGN_DAILY_CAP = 50L;

    /** 各等级成长值下限（与 {@link MemberLevels} 一致，用于年末折算保底） */
    private static final long[] LEVEL_FLOORS = {0L, 100L, 1000L, 5000L, 20000L};

    private static final BigDecimal DISCOUNT_RATE = new BigDecimal("0.8");

    private PointsCalc() {
    }

    /**
     * 连续签到第 n 天应得积分：5/10/15/20/25/30/50，第 7 天及以后恒为 50。
     *
     * @param continuousDays 连续签到天数（≥1）
     */
    public static long signPoints(int continuousDays) {
        int days = Math.max(continuousDays, 1);
        if (days >= 7) {
            return SIGN_DAILY_CAP;
        }
        return days * 5L;
    }

    /** 连续签到整 7 天里程碑（7/14/21…），每次 +50 成长值 */
    public static boolean isWeeklyMilestone(int continuousDays) {
        return continuousDays > 0 && continuousDays % 7 == 0;
    }

    /** 评价一条应得积分：带图 30，否则 20 */
    public static long commentPoints(boolean withImage) {
        return withImage ? COMMENT_BASE_POINTS + COMMENT_IMAGE_POINTS : COMMENT_BASE_POINTS;
    }

    /**
     * 消费返积分 = 实付金额（元） × 等级积分倍率，四舍五入取整。
     *
     * @param paidFen 实付金额（分）
     * @param rate    等级积分倍率（{@link MemberLevels#pointsRateOf(int)}）
     */
    public static long consumePoints(long paidFen, BigDecimal rate) {
        if (paidFen <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(paidFen)
                .movePointLeft(2)
                .multiply(rate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** 消费成长值：实付 1 元 = 1（四舍五入，150 分 = 2 成长值） */
    public static long consumeGrowth(long paidFen) {
        if (paidFen <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(paidFen)
                .movePointLeft(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * 按每日上限裁剪本次实际可获取积分。
     *
     * @param requested   请求获取数
     * @param earnedToday 当日该场景已获取数
     * @param dailyCap    当日上限（≤0 表示无上限）
     * @return 实际可获取数（被上限截断，超额时为 0）
     */
    public static long clampByDailyCap(long requested, long earnedToday, long dailyCap) {
        if (requested <= 0) {
            return 0L;
        }
        if (dailyCap <= 0) {
            return requested;
        }
        return Math.max(0L, Math.min(requested, dailyCap - Math.max(0L, earnedToday)));
    }

    /** 年末折算：成长值 × 80%，向下取整 */
    public static long discountGrowth(long growth) {
        if (growth <= 0) {
            return 0L;
        }
        return BigDecimal.valueOf(growth)
                .multiply(DISCOUNT_RATE)
                .setScale(0, RoundingMode.FLOOR)
                .longValue();
    }

    /**
     * 年末折算保底：折算后成长值不得低于当前等级的成长下限（保证不降级）。
     */
    public static long guaranteeLevelFloor(long discountedGrowth, int currentLevel) {
        int level = Math.max(MemberLevels.L0, Math.min(MemberLevels.L4, currentLevel));
        return Math.max(discountedGrowth, LEVEL_FLOORS[level]);
    }

    /** 积分批次过期时间：获取时间 + 365 天 */
    public static LocalDateTime expireTime(LocalDateTime grantTime) {
        return grantTime.plusDays(POINTS_VALID_DAYS);
    }
}
