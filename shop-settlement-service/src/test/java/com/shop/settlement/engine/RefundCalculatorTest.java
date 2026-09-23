package com.shop.settlement.engine;

import com.shop.api.pay.enums.RefundTypes;
import com.shop.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款冲正计算单测（design 7.5）：按比例回退、技费/通道费不退、全额判定、舍入、超额校验。
 */
class RefundCalculatorTest {

    private final RefundCalculator calculator = new RefundCalculator();

    @Test
    @DisplayName("refund_全额退款_各分项全额冲正且stage应转40")
    void refund_full_allItemsReversed() {
        RefundReverseResult r = calculator.calculate(
                10_000, 10_000, 8_000, 1_000, 500,
                RefundTypes.FULL.getCode(), 0);

        assertTrue(r.isFullRefund());
        assertEquals(10_000, r.getRefundRatioBps());
        assertEquals(8_000, r.getMerchantPartFen());
        assertEquals(1_000, r.getCommissionReverseFen());
        assertEquals(500, r.getSubsidyReverseFen());
        assertEquals(0, r.getTechFeeReverseFen());   // 技服费不退
        assertEquals(0, r.getChannelFeeReverseFen()); // 通道费不退
    }

    @Test
    @DisplayName("refund_部分退款50%_佣金补贴按比例回退")
    void refund_half_proportional() {
        RefundReverseResult r = calculator.calculate(
                5_000, 10_000, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 0);

        assertFalse(r.isFullRefund());
        assertEquals(5_000, r.getRefundRatioBps());
        assertEquals(4_000, r.getMerchantPartFen());
        assertEquals(500, r.getCommissionReverseFen());
        assertEquals(250, r.getSubsidyReverseFen());
        assertEquals(0, r.getTechFeeReverseFen());
        assertEquals(0, r.getChannelFeeReverseFen());
    }

    @Test
    @DisplayName("refund_部分退款_HALF_UP比例舍入")
    void refund_partial_halfUpRatio() {
        // 实付 30000 退 10000 → 3333.33bps → 3333
        RefundReverseResult r = calculator.calculate(
                10_000, 30_000, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 0);
        assertEquals(3_333, r.getRefundRatioBps());
        // 8000 × 3333 / 10000 = 2666.4 → 2666
        assertEquals(2_666, r.getMerchantPartFen());
        // 1000 × 3333 / 10000 = 333.3 → 333
        assertEquals(333, r.getCommissionReverseFen());
    }

    @Test
    @DisplayName("refund_累计恰好等于实付_判定为全额")
    void refund_accumulatedFull_treatedAsFull() {
        RefundReverseResult r = calculator.calculate(
                3_000, 10_000, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 7_000);
        assertTrue(r.isFullRefund());
        assertEquals(10_000, r.getRefundRatioBps());
        assertEquals(8_000, r.getMerchantPartFen());
    }

    @Test
    @DisplayName("refund_累计超出实付_抛退款金额异常")
    void refund_exceedPay_throw() {
        assertThrows(BizException.class, () -> calculator.calculate(
                3_000, 10_000, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 8_000));
    }

    @Test
    @DisplayName("refund_非法实付或退款额_抛异常")
    void refund_illegalAmount_throw() {
        assertThrows(BizException.class, () -> calculator.calculate(
                100, 0, 80, 10, 5, RefundTypes.FULL.getCode(), 0));
        assertThrows(BizException.class, () -> calculator.calculate(
                0, 10_000, 8_000, 1_000, 500, RefundTypes.FULL.getCode(), 0));
    }

    @Test
    @DisplayName("B11_refund_含保费订单部分退款_比例分母与累计上限剔除保费(保费不退)")
    void refund_withPremium_partial_ratioExcludesPremium() {
        // pay=10_100 = 商品实付 10_000 + 运费险保费 100；退款 5_000（不含保费）
        RefundReverseResult r = calculator.calculate(
                5_000, 10_100, 100, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 0);

        assertFalse(r.isFullRefund());
        // 比例 = 5000/10000 = 5000bps（若错误地用含保费的 10_100 做分母会得到 4950bps）
        assertEquals(5_000, r.getRefundRatioBps());
        assertEquals(4_000, r.getMerchantPartFen());
        assertEquals(500, r.getCommissionReverseFen());
        assertEquals(250, r.getSubsidyReverseFen());
        // 保费不出现在任何冲正分项
        assertEquals(0, r.getTechFeeReverseFen());
        assertEquals(0, r.getChannelFeeReverseFen());
    }

    @Test
    @DisplayName("B11_refund_含保费订单退至商品实付上限_全额判定不把保费算作可退额度")
    void refund_withPremium_fullGoodsPay_treatedFull() {
        // 商品侧已退 5_000，本次再退 5_000：累计=商品实付 10_000（< 含保费的 10_100），应判全额
        RefundReverseResult r = calculator.calculate(
                5_000, 10_100, 100, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 5_000);

        assertTrue(r.isFullRefund());
        assertEquals(10_000, r.getRefundRatioBps());
        assertEquals(8_000, r.getMerchantPartFen());
        assertEquals(1_000, r.getCommissionReverseFen());
        assertEquals(500, r.getSubsidyReverseFen());
    }

    @Test
    @DisplayName("B11_refund_退款超过商品实付(误含保费)_抛退款金额异常")
    void refund_withPremium_exceedGoodsPay_throw() {
        // 累计 10_001：对含保费 pay=10_100 不超限，但对商品实付 10_000 已超限
        assertThrows(BizException.class, () -> calculator.calculate(
                5_001, 10_100, 100, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 5_000));
    }

    @Test
    @DisplayName("B11_refund_保费口径非法_抛结算金额异常")
    void refund_withPremium_illegalPremium_throw() {
        assertThrows(BizException.class, () -> calculator.calculate(
                100, 10_000, 10_001, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 0));
        assertThrows(BizException.class, () -> calculator.calculate(
                100, 100, -1, 80, 10, 5,
                RefundTypes.PART.getCode(), 0));
    }

    @Test
    @DisplayName("B11_refund_旧七参重载_保费按0处理与历史行为一致")
    void refund_legacyOverload_premiumZero() {
        RefundReverseResult r = calculator.calculate(
                5_000, 10_000, 8_000, 1_000, 500,
                RefundTypes.PART.getCode(), 0);
        assertEquals(5_000, r.getRefundRatioBps());
    }
}
