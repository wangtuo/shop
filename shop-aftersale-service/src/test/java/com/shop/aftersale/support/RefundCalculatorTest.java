package com.shop.aftersale.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款金额规则（design 8.4）：券不退、积分按比例退、运费三方承担、最大余数守恒。
 */
class RefundCalculatorTest {

    private final RefundCalculator calculator = new RefundCalculator();

    @Test
    void calcItemRefunds_整件全退_等于可退金额() {
        List<Long> r = calculator.calcItemRefunds(List.of(
                new RefundCalculator.ItemRefundInput(1L, 10000L, 2, 2)));
        assertEquals(10000L, r.get(0));
    }

    @Test
    void calcItemRefunds_部分数量按比例且不多退() {
        // 实付 10000（券后），退 1/2 → 5000
        List<Long> r = calculator.calcItemRefunds(List.of(
                new RefundCalculator.ItemRefundInput(1L, 10000L, 2, 1)));
        assertEquals(5000L, r.get(0));
        // 奇数金额向下取整
        List<Long> r2 = calculator.calcItemRefunds(List.of(
                new RefundCalculator.ItemRefundInput(1L, 10001L, 2, 1)));
        assertEquals(5000L, r2.get(0));
    }

    @Test
    void calcItemRefunds_优惠券不退_退款基数剔除券() {
        // 原价 20000，券 5000，实付 15000；全额退款也只退 15000，券不返还
        List<Long> r = calculator.calcItemRefunds(List.of(
                new RefundCalculator.ItemRefundInput(1L, 15000L, 1, 1)));
        assertEquals(15000L, r.get(0));
    }

    @Test
    void calcItemRefunds_数量非法_抛异常() {
        assertThrows(IllegalArgumentException.class, () -> calculator.calcItemRefunds(List.of(
                new RefundCalculator.ItemRefundInput(1L, 10000L, 1, 2))));
        assertThrows(IllegalArgumentException.class, () -> calculator.calcItemRefunds(List.of(
                new RefundCalculator.ItemRefundInput(1L, 10000L, 1, 0))));
    }

    @Test
    void pointsToRefund_按退款比例退还() {
        // 用了 1000 积分（抵扣 1000 分），退一半 → 退 500 积分
        assertEquals(500, calculator.pointsToRefund(1000, 5000, 10000));
        assertEquals(1000, calculator.pointsToRefund(1000, 10000, 10000));
        // 部分退款向下取整
        assertEquals(333, calculator.pointsToRefund(1000, 3333, 10000));
        // 不超额
        assertEquals(1000, calculator.pointsToRefund(1000, 20000, 10000));
        assertEquals(0, calculator.pointsToRefund(1000, 0, 10000));
    }

    @Test
    void freightCompensation_商家责任承担买家责任自担() {
        assertEquals(1200L, calculator.freightCompensation(1, 1200L));
        assertEquals(0L, calculator.freightCompensation(2, 1200L));
        assertEquals(0L, calculator.freightCompensation(3, 1200L));
    }

    @Test
    void allocate_最大余数法_合计守恒() {
        List<Long> r = calculator.allocate(100, List.of(1L, 1L, 1L));
        assertEquals(100L, r.stream().mapToLong(Long::longValue).sum());
        assertTrue(r.stream().allMatch(v -> v == 33 || v == 34));
        List<Long> r2 = calculator.allocate(10001, List.of(3000L, 7000L, 1L));
        assertEquals(10001L, r2.stream().mapToLong(Long::longValue).sum());
    }
}
