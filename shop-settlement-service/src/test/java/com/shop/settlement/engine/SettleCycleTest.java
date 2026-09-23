package com.shop.settlement.engine;

import com.shop.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 结算周期 due_date 边界单测（design 7.3.2：S T+1 / A T+7 / B T+15 / C T+30）。
 */
class SettleCycleTest {

    private final SettleCycle cycle = new SettleCycle();
    private final LocalDate t0 = LocalDate.of(2026, 9, 16);

    @ParameterizedTest(name = "等级 {0}（{2}）：settleDays={1}，dueDate=确认日+{1}天")
    @CsvSource(textBlock = """
            0, 1,  S
            1, 7,  A
            2, 15, B
            3, 30, C
            """)
    @DisplayName("dueDate_S/A/B/C四档分级账期_与design 7.3.2一致")
    void dueDate_levelTiers(int level, int expectedDays, String tier) {
        assertEquals(expectedDays, cycle.settleDays(level), tier + " 级周期天数");
        assertEquals(t0.plusDays(expectedDays), cycle.dueDate(level, t0),
                tier + " 级到期日=确认收货日+" + expectedDays + "天");
    }

    @Test
    @DisplayName("dueDate_四级等级_周期天数正确")
    void dueDate_allLevels() {
        assertEquals(LocalDate.of(2026, 9, 17), cycle.dueDate(0, t0));  // S T+1
        assertEquals(LocalDate.of(2026, 9, 23), cycle.dueDate(1, t0));  // A T+7
        assertEquals(LocalDate.of(2026, 10, 1), cycle.dueDate(2, t0));  // B T+15（跨月）
        assertEquals(LocalDate.of(2026, 10, 16), cycle.dueDate(3, t0)); // C T+30
    }

    @Test
    @DisplayName("settleDays_等级映射_1_7_15_30")
    void settleDays_mapping() {
        assertEquals(1, cycle.settleDays(0));
        assertEquals(7, cycle.settleDays(1));
        assertEquals(15, cycle.settleDays(2));
        assertEquals(30, cycle.settleDays(3));
        assertThrows(BizException.class, () -> cycle.settleDays(9));
    }

    @Test
    @DisplayName("dueDate_月末跨年_C级T30落到次年")
    void dueDate_crossYear() {
        assertEquals(LocalDate.of(2027, 1, 1),
                cycle.dueDate(3, LocalDate.of(2026, 12, 2)));
        assertThrows(BizException.class, () -> cycle.dueDate(0, null));
    }
}
