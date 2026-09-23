package com.shop.settlement.engine;

import com.shop.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提现规则单测（design 7.4）：100 起、单日 50 万、月前 3 笔免费、其后 0.1% 最低 2 元。
 */
class WithdrawCalculatorTest {

    private final WithdrawCalculator calculator = new WithdrawCalculator();

    @Test
    @DisplayName("validate_最低100元边界")
    void validate_minAmount() {
        calculator.validateAmount(10_000, 0);          // 恰好 100 元通过
        assertThrows(BizException.class, () -> calculator.validateAmount(9_999, 0));
    }

    @Test
    @DisplayName("validate_单日50万上限边界")
    void validate_dailyLimit() {
        calculator.validateAmount(10_000, 49_990_000);             // 合计恰好 50 万通过
        assertThrows(BizException.class,
                () -> calculator.validateAmount(20_000, 49_990_000)); // 50.01 万拒绝
        assertThrows(BizException.class,
                () -> calculator.validateAmount(50_000_001, 0));
    }

    @Test
    @DisplayName("fee_每月前3笔免费_第4笔起收费")
    void fee_freeThreeThenCharge() {
        assertEquals(0, calculator.fee(10_000, 0));
        assertEquals(0, calculator.fee(10_000, 1));
        assertEquals(0, calculator.fee(10_000, 2));
        assertTrue(calculator.freeOfCharge(2));
        assertFalse(calculator.freeOfCharge(3));
    }

    @Test
    @DisplayName("fee_第4笔小额_最低2元")
    void fee_fourthSmallAmount_min2Yuan() {
        // 100 元 × 0.1% = 10 分，最低 200 分
        assertEquals(200, calculator.fee(10_000, 3));
        // 200 元 × 0.1% = 20 分，仍取最低 200
        assertEquals(200, calculator.fee(20_000, 5));
    }

    @Test
    @DisplayName("fee_大额_按0.1%计算")
    void fee_largeAmount_exactRate() {
        // 5,000,000 分(5万元) × 10bps = 5000 分
        assertEquals(5_000, calculator.fee(5_000_000, 3));
        // 2000 元 = 200000 分 × 10bps = 200 分，恰好等于最低手续费
        assertEquals(200, calculator.fee(200_000, 9));
    }

    @Test
    @DisplayName("fee_金额低于100元_抛异常")
    void fee_belowMin_throw() {
        assertThrows(BizException.class, () -> calculator.fee(9_999, 3));
    }
}
