package com.shop.settlement.engine;

import com.shop.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分账引擎单测（design 7.2.2）：公式、资金平衡恒等式、HALF_UP 舍入、非法输入。
 */
class SplitEngineTest {

    private final SplitEngine engine = new SplitEngine();

    /**
     * design 7.1.2 资金流向示例（85%/10%/5% 量级）：
     * 商品 10000 分、佣金率 10%(1000bps)、平台券 500 分、无店铺优惠、无运费。
     */
    @Test
    @DisplayName("split_85_10_5场景_分账金额与恒等式正确")
    void split_designSample_allFieldsMatch() {
        SplitResult r = engine.split(SplitRequest.builder()
                .productAmountFen(10_000)
                .freightFen(0)
                .merchantBearDiscountFen(0)
                .platformBearDiscountFen(500)
                .commissionRateBps(1000)
                .build());

        assertEquals(10_000, r.getProductAmountFen());
        assertEquals(1000, r.getPlatformCommissionFen());      // 10000 × 10%
        assertEquals(60, r.getChannelFeeFen());               // 10000 × 0.6%
        assertEquals(50, r.getTechFeeFen());                  // 0.5 元/笔
        assertEquals(500, r.getMarketingSubsidyFen());        // 营销补贴=平台承担优惠
        assertEquals(8890, r.getMerchantReceivableFen());     // 10000-1000-60-50
        assertEquals(9500, r.getUserPayFen());                // 10000-500

        // 核心货款池守恒：商户应收 + 佣金 + 通道费 + 技服费 = (商品额-商户承担优惠) + 运费
        assertEquals(10_000,
                r.getMerchantReceivableFen() + r.getPlatformCommissionFen()
                        + r.getChannelFeeFen() + r.getTechFeeFen());
        // 资金来源 = 去向：用户实付 + 营销出资 = 商户应收 + 佣金 + 通道费 + 技服费
        assertEquals(r.getUserPayFen() + r.getMarketingSubsidyFen(),
                r.getMerchantReceivableFen() + r.getPlatformCommissionFen()
                        + r.getChannelFeeFen() + r.getTechFeeFen());
    }

    @Test
    @DisplayName("split_含店铺优惠平台券积分运费_恒等式仍守恒")
    void split_fullBasket_identityHolds() {
        SplitResult r = engine.split(SplitRequest.builder()
                .productAmountFen(20_000)
                .freightFen(1_200)
                .merchantBearDiscountFen(2_000)   // 店铺券+店铺满减
                .platformBearDiscountFen(1_500)   // 平台券1000 + 积分抵现500
                .commissionRateBps(500)           // 5%
                .build());

        long base = 20_000 - 2_000;
        assertEquals(900, r.getPlatformCommissionFen());       // 18000 × 5%
        assertEquals(120, r.getChannelFeeFen());               // 20000 × 60bps
        assertEquals(1_500, r.getMarketingSubsidyFen());
        // 18000 - 900 - 120 - 50 + 1200
        assertEquals(18_130, r.getMerchantReceivableFen());
        // 20000 + 1200 - 2000 - 1500
        assertEquals(17_700, r.getUserPayFen());

        assertEquals(base + 1_200,
                r.getMerchantReceivableFen() + r.getPlatformCommissionFen()
                        + r.getChannelFeeFen() + r.getTechFeeFen());
        // 资金来源 = 去向（含运费）
        assertEquals(r.getUserPayFen() + r.getMarketingSubsidyFen(),
                r.getMerchantReceivableFen() + r.getPlatformCommissionFen()
                        + r.getChannelFeeFen() + r.getTechFeeFen());
    }

    @Test
    @DisplayName("multiplyBps_HALF_UP边界_四舍五入正确")
    void multiplyBps_halfUpBoundaries() {
        // 通道费 60bps：10083 × 60 /10000 = 60.498 → 60；10084 → 60.504 → 61
        assertEquals(60, SplitEngine.multiplyBps(10_083, 60));
        assertEquals(61, SplitEngine.multiplyBps(10_084, 60));
        // 佣金 1000bps：10005 × 1000 / 10000 = 1000.5 → 1001；10004 → 1000
        assertEquals(1001, SplitEngine.multiplyBps(10_005, 1000));
        assertEquals(1000, SplitEngine.multiplyBps(10_004, 1000));
        // 0 与不产生分以下费用的极小金额
        assertEquals(0, SplitEngine.multiplyBps(0, 60));
        assertEquals(0, SplitEngine.multiplyBps(83, 60));
    }

    @Test
    @DisplayName("split_非法输入_抛结算金额异常")
    void split_invalidInputs_throw() {
        assertThrows(BizException.class, () -> engine.split(SplitRequest.builder()
                .productAmountFen(-1).freightFen(0).commissionRateBps(1000).build()));
        assertThrows(BizException.class, () -> engine.split(SplitRequest.builder()
                .productAmountFen(100).merchantBearDiscountFen(101).commissionRateBps(1000).build()));
        assertThrows(BizException.class, () -> engine.split(SplitRequest.builder()
                .productAmountFen(100).commissionRateBps(10_001).build()));
        // 佣金率100% + 通道费导致商户应收为负
        BizException ex = assertThrows(BizException.class, () -> engine.split(SplitRequest.builder()
                .productAmountFen(100).freightFen(0).commissionRateBps(10_000).build()));
        assertEquals(70001, ex.getCode());
    }

    @Test
    @DisplayName("split_零优惠零费率_商户应收等于商品额加运费减通道费")
    void split_zeroRate_matchesFormula() {
        SplitResult r = engine.split(SplitRequest.builder()
                .productAmountFen(50_000).freightFen(0).commissionRateBps(0).build());
        assertEquals(0, r.getPlatformCommissionFen());
        assertEquals(300, r.getChannelFeeFen());
        assertEquals(49_650, r.getMerchantReceivableFen()); // 50000-300-50
        assertEquals(50_000, r.getUserPayFen());
        assertTrue(r.getTechFeeFen() > 0);
    }

    @Test
    @DisplayName("B11_split_带运费险保费_基数不含保费且扩展恒等式成立")
    void split_withInsurancePremium_baseExcludesPremium_identityHolds() {
        SplitRequest baseReq = SplitRequest.builder()
                .productAmountFen(20_000)
                .freightFen(1_200)
                .merchantBearDiscountFen(2_000)
                .platformBearDiscountFen(1_500)
                .commissionRateBps(500)
                .build();
        SplitResult noPremium = engine.split(baseReq);

        SplitResult r = engine.split(SplitRequest.builder()
                .productAmountFen(20_000)
                .freightFen(1_200)
                .merchantBearDiscountFen(2_000)
                .platformBearDiscountFen(1_500)
                .commissionRateBps(500)
                .insurancePremiumFen(100)
                .build());

        // 保费不进佣金/通道费基数：商品侧分账逐字段与无保单一致
        assertEquals(noPremium.getPlatformCommissionFen(), r.getPlatformCommissionFen());
        assertEquals(noPremium.getChannelFeeFen(), r.getChannelFeeFen());
        assertEquals(noPremium.getTechFeeFen(), r.getTechFeeFen());
        assertEquals(noPremium.getMerchantReceivableFen(), r.getMerchantReceivableFen());
        assertEquals(noPremium.getMarketingSubsidyFen(), r.getMarketingSubsidyFen());
        assertEquals(100, r.getInsurancePremiumFen());
        // 用户实付恰好多一笔保费，其余分项不变
        assertEquals(noPremium.getUserPayFen() + 100, r.getUserPayFen());

        // 扩展恒等式：用户实付(含保费) + 营销补贴
        //          = 商户应收 + 佣金 + 通道费 + 技服费 + 运费险保费(平台收入16)
        assertEquals(r.getUserPayFen() + r.getMarketingSubsidyFen(),
                r.getMerchantReceivableFen() + r.getPlatformCommissionFen()
                        + r.getChannelFeeFen() + r.getTechFeeFen()
                        + r.getInsurancePremiumFen());
    }

    @Test
    @DisplayName("B11_split_保费为0_结果与旧实现逐字段一致(恒等式退化为旧四项)")
    void split_zeroPremium_fieldwiseEqualToLegacy() {
        SplitResult omitted = engine.split(SplitRequest.builder()
                .productAmountFen(20_000)
                .freightFen(1_200)
                .merchantBearDiscountFen(2_000)
                .platformBearDiscountFen(1_500)
                .commissionRateBps(500)
                .build());
        SplitResult explicitZero = engine.split(SplitRequest.builder()
                .productAmountFen(20_000)
                .freightFen(1_200)
                .merchantBearDiscountFen(2_000)
                .platformBearDiscountFen(1_500)
                .commissionRateBps(500)
                .insurancePremiumFen(0)
                .build());

        assertEquals(0, omitted.getInsurancePremiumFen());
        assertEquals(omitted.getProductAmountFen(), explicitZero.getProductAmountFen());
        assertEquals(omitted.getFreightFen(), explicitZero.getFreightFen());
        assertEquals(omitted.getMerchantBearDiscountFen(), explicitZero.getMerchantBearDiscountFen());
        assertEquals(omitted.getPlatformBearDiscountFen(), explicitZero.getPlatformBearDiscountFen());
        assertEquals(omitted.getUserPayFen(), explicitZero.getUserPayFen());
        assertEquals(omitted.getMerchantReceivableFen(), explicitZero.getMerchantReceivableFen());
        assertEquals(omitted.getPlatformCommissionFen(), explicitZero.getPlatformCommissionFen());
        assertEquals(omitted.getTechFeeFen(), explicitZero.getTechFeeFen());
        assertEquals(omitted.getChannelFeeFen(), explicitZero.getChannelFeeFen());
        assertEquals(omitted.getMarketingSubsidyFen(), explicitZero.getMarketingSubsidyFen());
        assertEquals(omitted.getInsurancePremiumFen(), explicitZero.getInsurancePremiumFen());
        assertEquals(omitted.getCommissionRateBps(), explicitZero.getCommissionRateBps());
        // 旧恒等式依旧成立
        assertEquals(explicitZero.getUserPayFen() + explicitZero.getMarketingSubsidyFen(),
                explicitZero.getMerchantReceivableFen() + explicitZero.getPlatformCommissionFen()
                        + explicitZero.getChannelFeeFen() + explicitZero.getTechFeeFen());
    }

    @Test
    @DisplayName("B11_split_保费为负_抛金额异常")
    void split_negativePremium_throw() {
        assertThrows(BizException.class, () -> engine.split(SplitRequest.builder()
                .productAmountFen(10_000).commissionRateBps(500)
                .insurancePremiumFen(-1).build()));
    }
}
