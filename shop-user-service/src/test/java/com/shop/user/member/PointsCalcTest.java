package com.shop.user.member;

import com.shop.api.user.enums.MemberLevels;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分/成长值规则计算器边界测试（design.md 2.1.3 / 2.2.2）。
 */
class PointsCalcTest {

    @Test
    void signPoints_连续签到序列_第1至6天线性第7天50之后恒50() {
        assertEquals(5, PointsCalc.signPoints(1));
        assertEquals(10, PointsCalc.signPoints(2));
        assertEquals(15, PointsCalc.signPoints(3));
        assertEquals(20, PointsCalc.signPoints(4));
        assertEquals(25, PointsCalc.signPoints(5));
        assertEquals(30, PointsCalc.signPoints(6));
        assertEquals(50, PointsCalc.signPoints(7));
        assertEquals(50, PointsCalc.signPoints(8));
        assertEquals(50, PointsCalc.signPoints(30));
        // 非法入参兜底为第 1 天
        assertEquals(5, PointsCalc.signPoints(0));
    }

    @Test
    void weeklyMilestone_每7天触发一次() {
        assertFalse(PointsCalc.isWeeklyMilestone(1));
        assertFalse(PointsCalc.isWeeklyMilestone(6));
        assertTrue(PointsCalc.isWeeklyMilestone(7));
        assertFalse(PointsCalc.isWeeklyMilestone(8));
        assertTrue(PointsCalc.isWeeklyMilestone(14));
        assertTrue(PointsCalc.isWeeklyMilestone(21));
        assertFalse(PointsCalc.isWeeklyMilestone(0));
    }

    @Test
    void commentPoints_纯文字20带图30() {
        assertEquals(20, PointsCalc.commentPoints(false));
        assertEquals(30, PointsCalc.commentPoints(true));
    }

    @Test
    void consumePoints_实付乘等级倍率_四舍五入() {
        // 1 元 L1 ×1.1 = 1.1 -> 1
        assertEquals(1, PointsCalc.consumePoints(100, MemberLevels.pointsRateOf(MemberLevels.L1)));
        // 1.5 元 L1 ×1.1 = 1.65 -> 2
        assertEquals(2, PointsCalc.consumePoints(150, MemberLevels.pointsRateOf(MemberLevels.L1)));
        // 10 元 L1 ×1.1 = 11
        assertEquals(11, PointsCalc.consumePoints(1000, MemberLevels.pointsRateOf(MemberLevels.L1)));
        // 10 元 L2 ×1.5 = 15
        assertEquals(15, PointsCalc.consumePoints(1000, MemberLevels.pointsRateOf(MemberLevels.L2)));
        // 10 元 L3 ×2 = 20
        assertEquals(20, PointsCalc.consumePoints(1000, MemberLevels.pointsRateOf(MemberLevels.L3)));
        // 1 元 L4 ×3 = 3
        assertEquals(3, PointsCalc.consumePoints(100, MemberLevels.pointsRateOf(MemberLevels.L4)));
        // 非正金额 0 积分
        assertEquals(0, PointsCalc.consumePoints(0, BigDecimal.ONE));
    }

    @Test
    void consumeGrowth_实付1元1成长_四舍五入() {
        assertEquals(10, PointsCalc.consumeGrowth(1000));
        assertEquals(2, PointsCalc.consumeGrowth(150));
        assertEquals(0, PointsCalc.consumeGrowth(40));
        assertEquals(1, PointsCalc.consumeGrowth(50));
        assertEquals(0, PointsCalc.consumeGrowth(0));
    }

    @Test
    void clampByDailyCap_评价分享签到每日上限() {
        // 评价当日已得 80，再评带图 30 -> 只能再得 20
        assertEquals(20, PointsCalc.clampByDailyCap(30, 80, PointsCalc.COMMENT_DAILY_CAP));
        // 评价当日已满 100 -> 0
        assertEquals(0, PointsCalc.clampByDailyCap(20, 100, PointsCalc.COMMENT_DAILY_CAP));
        // 分享首次 10，当日上限 20
        assertEquals(10, PointsCalc.clampByDailyCap(10, 0, PointsCalc.SHARE_DAILY_CAP));
        // 签到封顶 50
        assertEquals(0, PointsCalc.clampByDailyCap(50, 50, PointsCalc.SIGN_DAILY_CAP));
        // 无上限场景（消费）全额
        assertEquals(1000, PointsCalc.clampByDailyCap(1000, 5000, 0));
        assertEquals(0, PointsCalc.clampByDailyCap(-1, 0, 100));
    }

    @Test
    void discountGrowth_按80百分比向下取整() {
        assertEquals(0, PointsCalc.discountGrowth(0));
        assertEquals(40, PointsCalc.discountGrowth(50));
        assertEquals(79, PointsCalc.discountGrowth(99));
        assertEquals(80, PointsCalc.discountGrowth(100));
        assertEquals(987, PointsCalc.discountGrowth(1234));
        assertEquals(16000, PointsCalc.discountGrowth(20000));
    }

    @Test
    void guaranteeLevelFloor_折算保底当前等级() {
        assertEquals(40, PointsCalc.guaranteeLevelFloor(40, MemberLevels.L0));
        // L1 下限 100：折算 80 -> 保底 100
        assertEquals(100, PointsCalc.guaranteeLevelFloor(80, MemberLevels.L1));
        // L2 下限 1000
        assertEquals(1000, PointsCalc.guaranteeLevelFloor(800, MemberLevels.L2));
        // L3 下限 5000
        assertEquals(5000, PointsCalc.guaranteeLevelFloor(4000, MemberLevels.L3));
        // L4 下限 20000
        assertEquals(20000, PointsCalc.guaranteeLevelFloor(16000, MemberLevels.L4));
        // 高于下限不抬升
        assertEquals(15999, PointsCalc.guaranteeLevelFloor(15999, MemberLevels.L3));
    }

    @Test
    void expireTime_获取时间加365天() {
        LocalDateTime grant = LocalDateTime.of(2026, 1, 1, 10, 0);
        assertEquals(LocalDateTime.of(2027, 1, 1, 10, 0), PointsCalc.expireTime(grant));
    }
}
