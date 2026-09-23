package com.shop.user.member;

import com.shop.api.user.enums.MemberLevels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 成长值 -> 会员等级边界（0/99/100/999/1000/4999/5000/19999/20000）与折扣倍率。
 */
class MemberLevelsBoundaryTest {

    @ParameterizedTest(name = "growth={0} -> level={1}")
    @CsvSource({
            "0,0",
            "99,0",
            "100,1",
            "999,1",
            "1000,2",
            "4999,2",
            "5000,3",
            "19999,3",
            "20000,4",
            "999999,4",
            "-1,0"
    })
    void ofGrowth_等级边界(long growth, int expectedLevel) {
        assertEquals(expectedLevel, MemberLevels.ofGrowth(growth));
    }

    @Test
    void discountAndRate_各等级权益正确() {
        assertEquals(new BigDecimal("1.00"), MemberLevels.discountOf(0));
        assertEquals(new BigDecimal("0.98"), MemberLevels.discountOf(1));
        assertEquals(new BigDecimal("0.95"), MemberLevels.discountOf(2));
        assertEquals(new BigDecimal("0.92"), MemberLevels.discountOf(3));
        assertEquals(new BigDecimal("0.90"), MemberLevels.discountOf(4));

        assertEquals(new BigDecimal("1"), MemberLevels.pointsRateOf(0));
        assertEquals(new BigDecimal("1.1"), MemberLevels.pointsRateOf(1));
        assertEquals(new BigDecimal("1.5"), MemberLevels.pointsRateOf(2));
        assertEquals(new BigDecimal("2"), MemberLevels.pointsRateOf(3));
        assertEquals(new BigDecimal("3"), MemberLevels.pointsRateOf(4));

        assertEquals("新会员", MemberLevels.nameOf(0));
        assertEquals("银卡会员", MemberLevels.nameOf(1));
        assertEquals("金卡会员", MemberLevels.nameOf(2));
        assertEquals("白金会员", MemberLevels.nameOf(3));
        assertEquals("钻石会员", MemberLevels.nameOf(4));
    }

    @Test
    void illegalLevel_抛异常() {
        assertThrows(IllegalArgumentException.class, () -> MemberLevels.nameOf(5));
        assertThrows(IllegalArgumentException.class, () -> MemberLevels.discountOf(-1));
        assertThrows(IllegalArgumentException.class, () -> MemberLevels.pointsRateOf(9));
    }
}
