package com.shop.marketing.engine;

import com.shop.api.marketing.dto.CalcItem;
import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.common.exception.BizException;
import com.shop.common.util.JsonUtils;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.mapper.ActivityMapper;
import com.shop.marketing.activity.support.ActivityRule;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.mapper.CouponTargetMapper;
import com.shop.marketing.coupon.mapper.UserCouponMapper;
import com.shop.marketing.promo.service.PromoQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * B1 拼团团长价 + B3 砍价快照价通用 + B5 预售尾款膨胀抵扣计价快照。
 */
@ExtendWith(MockitoExtension.class)
class PriceEngineGroupbuyLeaderTest {

    @Mock private PromoQueryService promoQueryService;
    @Mock private CouponMapper couponMapper;
    @Mock private CouponTargetMapper couponTargetMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private ActivityMapper activityMapper;

    private PriceEngine engine;

    private static final Long SHOP = 100L;

    @BeforeEach
    void setUp() {
        engine = new PriceEngine(promoQueryService, couponMapper, couponTargetMapper, userCouponMapper,
                activityMapper);
        lenient().when(promoQueryService.activePromos(anyCollection(), any())).thenReturn(List.of());
        lenient().when(promoQueryService.levels(anyCollection())).thenReturn(Map.of());
        lenient().when(promoQueryService.targets(anyCollection())).thenReturn(Map.of());
    }

    private CalcItem item(long price, int qty) {
        return CalcItem.builder().skuId(1L).spuId(1001L).merchantId(1L).shopId(SHOP)
                .category3Id(200L).qty(qty).salePriceFen(price).build();
    }

    private PriceCalcCommand.PriceCalcCommandBuilder groupCmd() {
        return PriceCalcCommand.builder()
                .userId(9L).userLevel(4).orderType(3).freightFen(0L)
                .groupbuyActivityId(11L).items(new ArrayList<>(List.of(item(1000, 1))));
    }

    private void stubRule(Long leaderDiscount, Long inflate) {
        ActivityRule rule = new ActivityRule();
        rule.setRequiredPeople(2);
        rule.setLeaderDiscountFen(leaderDiscount);
        rule.setInflateDeductFen(inflate);
        Activity activity = new Activity();
        activity.setId(11L);
        activity.setStatus(1);
        activity.setRuleJson(JsonUtils.toJson(rule));
        lenient().when(activityMapper.selectById(11L)).thenReturn(activity);
    }

    @Test
    @DisplayName("团长价 = 拼团价 - leaderDiscountFen，回填 leaderPriceFen/leaderFlag")
    void leaderPrice() {
        stubRule(200L, null);
        PriceCalcResult r = engine.calculate(groupCmd().leaderFlag(1).build());
        assertEquals(1000L, r.getOriginalProductFen());
        assertEquals(200L, r.getProductPromoFen());
        assertEquals(800L, r.getPayFen());
        assertEquals(800L, r.getLeaderPriceFen());
        assertEquals(Boolean.TRUE, r.getLeaderFlag());
        assertEquals(800L, r.getItemDetails().get(0).getPaidFen());
    }

    @Test
    @DisplayName("团员价不变，leaderFlag=false 且不回填团长价")
    void memberPrice() {
        stubRule(200L, null);
        PriceCalcResult r = engine.calculate(groupCmd().leaderFlag(0).build());
        assertEquals(1000L, r.getPayFen());
        assertEquals(0L, r.getProductPromoFen());
        assertEquals(Boolean.FALSE, r.getLeaderFlag());
        assertNull(r.getLeaderPriceFen());
    }

    @Test
    @DisplayName("历史调用 leaderFlag=null 按团员计价（leaderFlag=false），不回退现有行为")
    void nullLeaderFlag() {
        stubRule(200L, null);
        PriceCalcResult r = engine.calculate(groupCmd().build());
        assertEquals(1000L, r.getPayFen());
        assertEquals(Boolean.FALSE, r.getLeaderFlag());
    }

    @Test
    @DisplayName("团长优惠大于拼团价时底价为 0 不出负")
    void leaderPriceFloorZero() {
        stubRule(1500L, null);
        PriceCalcResult r = engine.calculate(groupCmd().leaderFlag(1).build());
        assertEquals(0L, r.getPayFen());
        assertEquals(0L, r.getLeaderPriceFen());
        assertEquals(1000L, r.getProductPromoFen());
    }

    @Test
    @DisplayName("团长单仍拒绝优惠券与积分（互斥开关语义不变）")
    void leaderRejectsCouponAndPoints() {
        stubRule(200L, null);
        assertThrows(BizException.class,
                () -> engine.calculate(groupCmd().leaderFlag(1).platformCouponId(77L).build()));
        assertThrows(BizException.class,
                () -> engine.calculate(groupCmd().leaderFlag(1).usePointsFen(100L).build()));
    }

    @Test
    @DisplayName("团长价快照 activityPriceFen 已含优惠时不再扣减 leaderDiscountFen（禁止重复抵扣）")
    void leaderSnapshotNoDoubleDeduct() {
        stubRule(200L, null);
        CalcItem snap = item(1000, 1);
        snap.setActivityPriceFen(800L);
        PriceCalcCommand cmd = PriceCalcCommand.builder()
                .userId(9L).userLevel(4).orderType(3).freightFen(0L)
                .groupbuyActivityId(11L).leaderFlag(1)
                .items(new ArrayList<>(List.of(snap))).build();
        PriceCalcResult r = engine.calculate(cmd);
        assertEquals(800L, r.getPayFen());
        assertEquals(200L, r.getProductPromoFen());
    }

    @Test
    @DisplayName("砍价成交价快照（普通单）：按快照价计价且不叠加会员折扣/限时折扣")
    void bargainSnapshotGeneric() {
        // 无活动规则加载（普通单不读 rule_json）；L4 会员 9 折也不得再叠加
        CalcItem snap = item(1000, 1);
        snap.setActivityPriceFen(700L);
        PriceCalcCommand cmd = PriceCalcCommand.builder()
                .userId(9L).userLevel(4).orderType(1).freightFen(0L)
                .items(new ArrayList<>(List.of(snap))).build();
        PriceCalcResult r = engine.calculate(cmd);
        assertEquals(700L, r.getPayFen());
        assertEquals(300L, r.getProductPromoFen());
        assertNull(r.getLeaderFlag());
    }

    @Test
    @DisplayName("尾款膨胀：尾款应付 = 尾款原价 - inflateDeductFen（50 抵 100），只抵扣一次")
    void presaleFinalInflate() {
        stubRule(null, 100L);
        PriceCalcCommand cmd = PriceCalcCommand.builder()
                .userId(9L).userLevel(0).orderType(4).presaleFinalStage(true).freightFen(0L)
                .presaleActivityId(11L)
                .items(new ArrayList<>(List.of(item(1000, 1)))).build();
        PriceCalcResult r = engine.calculate(cmd);
        assertEquals(1000L, r.getOriginalProductFen());
        assertEquals(100L, r.getProductPromoFen());
        assertEquals(900L, r.getPayFen());
        // 定金 + 尾款实付 + 膨胀 = 商品原价（本用例仅尾款单：尾款实付 + 膨胀 = 尾款原价）
        assertEquals(r.getOriginalProductFen(), r.getPayFen() + r.getProductPromoFen() + r.getShopPromoFen()
                + r.getCategoryCouponFen() + r.getShopCouponFen() + r.getPlatformCouponFen()
                + r.getPointsDeductFen() - r.getFreightFen());
    }

    @Test
    @DisplayName("尾款膨胀多行按占比最大余数分摊，守恒不差 1 分")
    void presaleFinalInflateAllocate() {
        stubRule(null, 100L);
        PriceCalcCommand cmd = PriceCalcCommand.builder()
                .userId(9L).userLevel(0).orderType(4).presaleFinalStage(true).freightFen(0L)
                .presaleActivityId(11L)
                .items(new ArrayList<>(List.of(item(600, 1), item(400, 1)))).build();
        PriceCalcResult r = engine.calculate(cmd);
        assertEquals(900L, r.getPayFen());
        assertEquals(60L, r.getItemDetails().get(0).getProductPromoFen());
        assertEquals(40L, r.getItemDetails().get(1).getProductPromoFen());
        assertEquals(540L + 360L, r.getItemDetails().get(0).getPaidFen() + r.getItemDetails().get(1).getPaidFen());
    }

    @Test
    @DisplayName("膨胀大于尾款原价时底价为 0，不出负、不重复抵扣")
    void presaleInflateFloorZero() {
        stubRule(null, 5000L);
        PriceCalcCommand cmd = PriceCalcCommand.builder()
                .userId(9L).userLevel(0).orderType(4).presaleFinalStage(true).freightFen(0L)
                .presaleActivityId(11L)
                .items(new ArrayList<>(List.of(item(1000, 1)))).build();
        PriceCalcResult r = engine.calculate(cmd);
        assertEquals(0L, r.getPayFen());
        assertEquals(1000L, r.getProductPromoFen());
    }

    @Test
    @DisplayName("定金阶段不应用膨胀抵扣且不可用券（定金不退口径不变）")
    void presaleDepositStageNoInflate() {
        stubRule(null, 100L);
        PriceCalcCommand cmd = PriceCalcCommand.builder()
                .userId(9L).userLevel(0).orderType(4).presaleFinalStage(false).freightFen(0L)
                .presaleActivityId(11L)
                .items(new ArrayList<>(List.of(item(1000, 1)))).build();
        PriceCalcResult r = engine.calculate(cmd);
        assertEquals(1000L, r.getPayFen());
        assertEquals(0L, r.getProductPromoFen());
        assertTrue(r.getCategoryCouponFen() + r.getShopCouponFen() + r.getPlatformCouponFen() == 0L);
    }

    @Test
    @DisplayName("定金阶段带券硬失败（互斥开关不变）")
    void presaleDepositRejectsCoupon() {
        stubRule(null, 100L);
        PriceCalcCommand cmd = PriceCalcCommand.builder()
                .userId(9L).userLevel(0).orderType(4).presaleFinalStage(false).freightFen(0L)
                .presaleActivityId(11L).categoryCouponId(7L)
                .items(new ArrayList<>(List.of(item(1000, 1)))).build();
        assertThrows(BizException.class, () -> engine.calculate(cmd));
    }
}
