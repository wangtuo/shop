package com.shop.marketing.engine;

import com.shop.api.marketing.dto.CalcItem;
import com.shop.api.marketing.dto.ItemPriceDetail;
import com.shop.api.marketing.dto.PriceCalcCommand;
import com.shop.api.marketing.dto.PriceCalcResult;
import com.shop.common.exception.BizException;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.entity.UserCoupon;
import com.shop.marketing.coupon.mapper.CouponMapper;
import com.shop.marketing.coupon.mapper.CouponTargetMapper;
import com.shop.marketing.coupon.mapper.UserCouponMapper;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.entity.PromoLevel;
import com.shop.marketing.promo.entity.PromoTarget;
import com.shop.marketing.promo.service.PromoQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 试算引擎规则全量测试：六层顺序、四类互斥、门槛边界、最大余数分摊守恒。
 */
@ExtendWith(MockitoExtension.class)
class PriceEngineTest {

    @Mock
    private PromoQueryService promoQueryService;
    @Mock
    private CouponMapper couponMapper;
    @Mock
    private CouponTargetMapper couponTargetMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private com.shop.marketing.activity.mapper.ActivityMapper activityMapper;

    private PriceEngine engine;

    private static final Long SHOP = 100L;
    private static final Long CATEGORY = 200L;

    @BeforeEach
    void setUp() {
        engine = new PriceEngine(promoQueryService, couponMapper, couponTargetMapper, userCouponMapper,
                activityMapper);
        noPromos();
    }

    private void noPromos() {
        lenient().when(promoQueryService.activePromos(anyCollection(), any())).thenReturn(List.of());
        lenient().when(promoQueryService.levels(anyCollection())).thenReturn(Map.of());
        lenient().when(promoQueryService.targets(anyCollection())).thenReturn(Map.of());
    }

    private CalcItem item(long skuId, long price, int qty) {
        return CalcItem.builder().skuId(skuId).spuId(skuId + 1000).merchantId(1L).shopId(SHOP)
                .category3Id(CATEGORY).qty(qty).salePriceFen(price).build();
    }

    private PriceCalcCommand cmd(int orderType, int level, long freight, Long points, List<CalcItem> items) {
        return PriceCalcCommand.builder()
                .userId(999L).userLevel(level).orderType(orderType).items(new ArrayList<>(items))
                .freightFen(freight).usePointsFen(points).build();
    }

    private Promo promo(long id, int type, Long shopId, int scope) {
        Promo p = new Promo();
        p.setId(id);
        p.setType(type);
        p.setShopId(shopId);
        p.setScopeType(scope);
        p.setStatus(1);
        p.setStartTime(LocalDateTime.now().minusHours(1));
        p.setEndTime(LocalDateTime.now().plusHours(1));
        return p;
    }

    private PromoLevel level(long id, long threshold, long reduce, Integer bp, Integer nth, Long giftSku, Integer giftQty) {
        PromoLevel lv = new PromoLevel();
        lv.setId(id);
        lv.setThresholdFen(threshold);
        lv.setReduceFen(reduce);
        lv.setDiscountBp(bp == null ? 1000 : bp);
        lv.setNthIndex(nth == null ? 0 : nth);
        lv.setGiftSkuId(giftSku);
        lv.setGiftQty(giftQty);
        return lv;
    }

    private PromoTarget target(long promoId, int type, long targetId) {
        PromoTarget t = new PromoTarget();
        t.setPromoId(promoId);
        t.setTargetType(type);
        t.setTargetId(targetId);
        return t;
    }

    private void usePromos(List<Promo> promos, Map<Long, List<PromoLevel>> levels,
                           Map<Long, List<PromoTarget>> targets) {
        when(promoQueryService.activePromos(anyCollection(), any())).thenReturn(promos);
        when(promoQueryService.levels(anyCollection())).thenReturn(levels);
        when(promoQueryService.targets(anyCollection())).thenReturn(targets);
    }

    private UserCoupon userCoupon(long id, long couponId) {
        UserCoupon uc = new UserCoupon();
        uc.setId(id);
        uc.setUserId(999L);
        uc.setCouponId(couponId);
        uc.setStatus(0);
        uc.setValidStartTime(LocalDateTime.now().minusDays(1));
        uc.setValidEndTime(LocalDateTime.now().plusDays(1));
        return uc;
    }

    private Coupon coupon(long id, int type, int scope, long face, long threshold, Integer bp) {
        Coupon c = new Coupon();
        c.setId(id);
        c.setStatus(1);
        c.setType(type);
        c.setScopeType(scope);
        c.setShopId(SHOP);
        c.setFaceValueFen(face);
        c.setThresholdFen(threshold);
        c.setDiscountBp(bp == null ? 1000 : bp);
        return c;
    }

    private void assertIdentity(PriceCalcResult r) {
        long product = r.getOriginalProductFen();
        long expectPay = product - r.getProductPromoFen() - r.getShopPromoFen()
                - r.getCategoryCouponFen() - r.getShopCouponFen() - r.getPlatformCouponFen()
                - r.getPointsDeductFen() + r.getFreightFen();
        assertEquals(Math.max(0, expectPay), r.getPayFen(), "整单金额恒等式不成立");
        long sumPaid = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getPaidFen).sum();
        assertEquals(r.getPayFen(), sumPaid, "明细分摊合计与应付不一致（差超过 0 分）");
    }

    // ------------------------------------------------------------------
    // 第 1 层：会员折扣 + 限时折扣
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("商品层：会员折扣与限时折扣")
    class ProductLayer {

        @Test
        void 会员等级折扣_L2九五折_整单九五折() {
            PriceCalcResult r = engine.calculate(cmd(1, 2, 0, 0L, List.of(item(1, 10000, 1))));
            assertEquals(500, r.getProductPromoFen());
            assertEquals(9500, r.getPayFen());
            assertIdentity(r);
        }

        @Test
        void 会员等级折扣_L4九折_多行合计() {
            PriceCalcResult r = engine.calculate(cmd(1, 4, 0, 0L,
                    List.of(item(1, 10000, 1), item(2, 20001, 1))));
            // 1000 + 2000(20001*0.9=18000.9 HALF_UP=18001，优惠 2000)
            assertEquals(3000, r.getProductPromoFen());
            assertIdentity(r);
        }

        @Test
        void 限时折扣_九折_会员折上折() {
            Promo p = promo(1, 5, null, 1);
            usePromos(List.of(p),
                    Map.of(1L, List.of(level(1, 0, 0, 900, 0, null, null))),
                    Map.of());
            PriceCalcResult r = engine.calculate(cmd(1, 2, 0, 0L, List.of(item(1, 10000, 1))));
            // 会员：10000→9500；限时九折：950
            assertEquals(1450, r.getProductPromoFen());
            assertEquals(8550, r.getPayFen());
            assertIdentity(r);
        }

        @Test
        void 限时折扣_不在活动时间_不生效() {
            Promo p = promo(1, 5, null, 1);
            p.setStartTime(LocalDateTime.now().plusHours(2));
            usePromos(List.of(p),
                    Map.of(1L, List.of(level(1, 0, 0, 900, 0, null, null))),
                    Map.of());
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L, List.of(item(1, 10000, 1))));
            assertEquals(0, r.getProductPromoFen());
        }
    }

    // ------------------------------------------------------------------
    // 第 2 层：满减/满折互斥、第N件、满赠
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("店铺层：满减满折/第N件/满赠")
    class ShopLayer {

        @Test
        void 满减多级_命中最高档() {
            Promo p = promo(1, 1, SHOP, 1);
            usePromos(List.of(p), Map.of(1L, List.of(
                    level(1, 10000, 1000, 1000, 0, null, null),
                    level(2, 20000, 3000, 1000, 0, null, null))), Map.of());
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L,
                    List.of(item(1, 10000, 1), item(2, 20000, 1))));
            assertEquals(3000, r.getShopPromoFen());
            assertIdentity(r);
        }

        @Test
        void 满减满折互斥_取优惠金额最大者() {
            Promo reduce = promo(1, 1, SHOP, 1);
            Promo discount = promo(2, 2, SHOP, 1);
            usePromos(List.of(reduce, discount), Map.of(
                    1L, List.of(level(1, 0, 3000, 1000, 0, null, null)),
                    2L, List.of(level(2, 0, 0, 800, 0, null, null))), Map.of());
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L,
                    List.of(item(1, 10000, 1), item(2, 20000, 1))));
            // 满减 3000 vs 八折优惠 6000 → 取 6000
            assertEquals(6000, r.getShopPromoFen());
            assertIdentity(r);
        }

        @Test
        void 第N件_第二件半价_买三件减一件半价() {
            Promo p = promo(1, 4, SHOP, 1);
            usePromos(List.of(p),
                    Map.of(1L, List.of(level(1, 0, 0, 500, 2, null, null))),
                    Map.of());
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L, List.of(item(1, 10000, 3))));
            // 3/2=1 个第二件，减 5000
            assertEquals(5000, r.getShopPromoFen());
            assertIdentity(r);
        }

        @Test
        void 满减可与第N件叠加() {
            Promo reduce = promo(1, 1, SHOP, 1);
            Promo nth = promo(2, 4, SHOP, 1);
            usePromos(List.of(reduce, nth), Map.of(
                    1L, List.of(level(1, 10000, 1000, 1000, 0, null, null)),
                    2L, List.of(level(2, 0, 0, 500, 2, null, null))), Map.of());
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L, List.of(item(1, 10000, 2))));
            // 逐层基数：满减 1000（两行各 500）→ 第N件以折后单价 9500 计半价 4750，合计 5750
            assertEquals(5750, r.getShopPromoFen());
            assertIdentity(r);
        }

        @Test
        void 满赠_达门槛_产出赠品明细() {
            Promo p = promo(1, 3, SHOP, 1);
            usePromos(List.of(p),
                    Map.of(1L, List.of(level(1, 5000, 0, 1000, 0, 8888L, 1))),
                    Map.of());
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L, List.of(item(1, 10000, 1))));
            assertTrue(r.getGiftSkuIds().contains(8888L));
            ItemPriceDetail gift = r.getItemDetails().stream()
                    .filter(d -> d.getGiftFlag() == 1).findFirst().orElseThrow();
            assertEquals(0, gift.getPaidFen());
            assertIdentity(r);
        }

        @Test
        void 指定SKU促销_不匹配商品不生效() {
            Promo p = promo(1, 1, SHOP, 2);
            usePromos(List.of(p),
                    Map.of(1L, List.of(level(1, 0, 1000, 1000, 0, null, null))),
                    Map.of(1L, List.of(target(1, 1, 999L))));
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L, List.of(item(1, 10000, 1))));
            assertEquals(0, r.getShopPromoFen());
        }
    }

    // ------------------------------------------------------------------
    // 第 3-5 层：品类/店铺/平台券 + 免邮券
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("券三层与免邮券")
    class CouponLayers {

        @Test
        void 三层券可同单叠加_逐层以折后金额为基数() {
            when(userCouponMapper.selectById(anyLong())).thenAnswer(inv -> {
                long id = inv.getArgument(0);
                return userCoupon(id, id + 1000);
            });
            when(couponMapper.selectById(anyLong())).thenAnswer(inv -> {
                long id = inv.getArgument(0);
                if (id == 2011L) return coupon(2011, 5, 1, 500, 0, null);
                if (id == 2012L) return coupon(2012, 6, 1, 2000, 0, null);
                return coupon(2013, 1, 1, 3000, 0, null);
            });

            PriceCalcCommand c = cmd(1, 0, 1000, 0L, List.of(item(1, 100000, 1)));
            c.setCategoryCouponId(1011L);
            c.setShopCouponId(1012L);
            c.setPlatformCouponId(1013L);
            PriceCalcResult r = engine.calculate(c);
            assertEquals(500, r.getCategoryCouponFen());
            assertEquals(2000, r.getShopCouponFen());
            assertEquals(3000, r.getPlatformCouponFen());
            // 100000 - 500 - 2000 - 3000 + 1000
            assertEquals(95500, r.getPayFen());
            assertEquals(3, r.getUsedUserCouponIds().size());
            assertIdentity(r);
        }

        @Test
        void 折扣券_按折扣基点减免并支持封顶() {
            when(userCouponMapper.selectById(7L)).thenReturn(userCoupon(7L, 70L));
            Coupon c = coupon(70, 2, 1, 0, 0, 800);
            c.setMaxDiscountFen(1000L);
            when(couponMapper.selectById(70L)).thenReturn(c);
            PriceCalcCommand command = cmd(1, 0, 0, 0L, List.of(item(1, 10000, 1)));
            command.setPlatformCouponId(7L);
            PriceCalcResult r = engine.calculate(command);
            // 八折应减 2000，封顶 1000
            assertEquals(1000, r.getPlatformCouponFen());
            assertIdentity(r);
        }

        @Test
        void 券门槛不足_抛券不可用() {
            when(userCouponMapper.selectById(7L)).thenReturn(userCoupon(7L, 70L));
            when(couponMapper.selectById(70L)).thenReturn(coupon(70, 1, 1, 2000, 50000, null));
            PriceCalcCommand command = cmd(1, 0, 0, 0L, List.of(item(1, 10000, 1)));
            command.setPlatformCouponId(7L);
            assertThrows(BizException.class, () -> engine.calculate(command));
        }

        @Test
        void 非本人券_抛券不可用() {
            UserCoupon uc = userCoupon(7L, 70L);
            uc.setUserId(8888L);
            when(userCouponMapper.selectById(7L)).thenReturn(uc);
            PriceCalcCommand command = cmd(1, 0, 0, 0L, List.of(item(1, 10000, 1)));
            command.setPlatformCouponId(7L);
            assertThrows(BizException.class, () -> engine.calculate(command));
        }

        @Test
        void 已核销券_抛券不可用() {
            UserCoupon uc = userCoupon(7L, 70L);
            uc.setStatus(1);
            when(userCouponMapper.selectById(7L)).thenReturn(uc);
            PriceCalcCommand command = cmd(1, 0, 0, 0L, List.of(item(1, 10000, 1)));
            command.setPlatformCouponId(7L);
            assertThrows(BizException.class, () -> engine.calculate(command));
        }

        @Test
        void 免邮券_全额抵扣运费() {
            when(userCouponMapper.selectById(9L)).thenReturn(userCoupon(9L, 90L));
            when(couponMapper.selectById(90L)).thenReturn(coupon(90, 4, 1, 0, 0, null));
            PriceCalcCommand command = cmd(1, 0, 1500, 0L, List.of(item(1, 10000, 1)));
            command.setPlatformCouponId(9L);
            PriceCalcResult r = engine.calculate(command);
            assertEquals(1500, r.getFreightCouponFen());
            assertEquals(0, r.getFreightFen());
            assertEquals(10000, r.getPayFen());
            assertIdentity(r);
        }
    }

    // ------------------------------------------------------------------
    // 第 6 层：积分抵现
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("积分层：100:1 且封顶商品金额 50%")
    class PointsLayer {

        @Test
        void 积分抵现_超出50百分比部分被截断() {
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 6000L, List.of(item(1, 10000, 1))));
            assertEquals(5000, r.getPointsDeductFen());
            assertEquals(5000, r.getPayFen());
            assertIdentity(r);
        }

        @Test
        void 积分抵现_券后金额为基数封顶() {
            when(userCouponMapper.selectById(7L)).thenReturn(userCoupon(7L, 70L));
            when(couponMapper.selectById(70L)).thenReturn(coupon(70, 1, 1, 8000, 0, null));
            PriceCalcCommand c = cmd(1, 0, 0, 5000L, List.of(item(1, 10000, 1)));
            c.setPlatformCouponId(7L);
            PriceCalcResult r = engine.calculate(c);
            // 券后 2000，积分最多抵 1000
            assertEquals(1000, r.getPointsDeductFen());
            assertEquals(1000, r.getPayFen());
            assertIdentity(r);
        }
    }

    // ------------------------------------------------------------------
    // 互斥规则
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("互斥：秒杀/拼团/预售")
    class ExclusiveRules {

        @Test
        void 秒杀_无任何优惠仅计运费() {
            PriceCalcResult r = engine.calculate(cmd(2, 0, 800, 0L, List.of(item(1, 10000, 2))));
            assertEquals(0, r.getProductPromoFen());
            assertEquals(0, r.getShopPromoFen());
            assertEquals(20800, r.getPayFen());
            assertIdentity(r);
        }

        @Test
        void 秒杀_带券直接互斥失败() {
            PriceCalcCommand c = cmd(2, 0, 0, 0L, List.of(item(1, 10000, 1)));
            c.setPlatformCouponId(7L);
            assertThrows(BizException.class, () -> engine.calculate(c));
        }

        @Test
        void 秒杀_积分抵现互斥() {
            assertThrows(BizException.class,
                    () -> engine.calculate(cmd(2, 0, 0, 100L, List.of(item(1, 10000, 1)))));
        }

        @Test
        void 拼团_仅成团价加运费_券与积分全禁() {
            PriceCalcResult r = engine.calculate(cmd(3, 0, 500, 0L, List.of(item(1, 10000, 1))));
            assertEquals(10500, r.getPayFen());
            assertThrows(BizException.class,
                    () -> engine.calculate(cmd(3, 0, 0, 100L, List.of(item(1, 10000, 1)))));
            PriceCalcCommand c = cmd(3, 0, 0, 0L, List.of(item(1, 10000, 1)));
            c.setPlatformCouponId(7L);
            assertThrows(BizException.class, () -> engine.calculate(c));
        }

        @Test
        void 预售_定金阶段不可用券_尾款阶段可用() {
            when(userCouponMapper.selectById(7L)).thenReturn(userCoupon(7L, 70L));
            when(couponMapper.selectById(70L)).thenReturn(coupon(70, 1, 1, 1000, 0, null));

            PriceCalcCommand deposit = cmd(4, 0, 0, 0L, List.of(item(1, 10000, 1)));
            deposit.setPresaleActivityId(5L);
            deposit.setPlatformCouponId(7L);
            assertThrows(BizException.class, () -> engine.calculate(deposit));

            PriceCalcCommand finalPay = cmd(4, 0, 0, 0L, List.of(item(1, 10000, 1)));
            finalPay.setPresaleActivityId(5L);
            finalPay.setPresaleFinalStage(true);
            finalPay.setPlatformCouponId(7L);
            PriceCalcResult r = engine.calculate(finalPay);
            assertEquals(1000, r.getPlatformCouponFen());
            assertIdentity(r);
        }
    }

    // ------------------------------------------------------------------
    // 分摊守恒：不可整除组合，最大余数法不差 1 分
    // ------------------------------------------------------------------
    @Nested
    @DisplayName("最大余数法分摊守恒")
    class AllocationConservation {

        @Test
        void 满减不可整除_三行分摊合计不差一分() {
            Promo p = promo(1, 1, SHOP, 1);
            usePromos(List.of(p),
                    Map.of(1L, List.of(level(1, 0, 100, 1000, 0, null, null))),
                    Map.of());
            // 权重 100/300/700 → 总分摊 100
            PriceCalcResult r = engine.calculate(cmd(1, 0, 0, 0L,
                    List.of(item(1, 100, 1), item(2, 300, 1), item(3, 700, 1))));
            assertEquals(100, r.getShopPromoFen());
            long allocSum = r.getItemDetails().stream().mapToLong(ItemPriceDetail::getShopPromoFen).sum();
            assertEquals(100, allocSum);
            assertIdentity(r);
        }

        @Test
        void 多楼层_各种数量组合_守恒恒成立() {
            Promo reduce = promo(1, 1, SHOP, 1);
            Promo nth = promo(2, 4, SHOP, 1);
            Promo limited = promo(3, 5, null, 1);
            usePromos(List.of(reduce, nth, limited), Map.of(
                    1L, List.of(level(1, 0, 777, 1000, 0, null, null)),
                    2L, List.of(level(2, 0, 0, 500, 2, null, null)),
                    3L, List.of(level(3, 0, 0, 900, 0, null, null))), Map.of());
            when(userCouponMapper.selectById(anyLong())).thenAnswer(inv -> userCoupon(inv.getArgument(0), 70L));
            when(couponMapper.selectById(anyLong())).thenReturn(coupon(70, 1, 1, 333, 0, null));

            long[] prices = {1, 7, 99, 1001, 9999, 12345};
            int[] qtys = {1, 2, 3, 5, 7, 11};
            List<CalcItem> items = new ArrayList<>();
            for (int i = 0; i < prices.length; i++) {
                items.add(item(i + 1, prices[i], qtys[i]));
            }
            PriceCalcCommand c = cmd(1, 3, 666, 12345L, items);
            c.setPlatformCouponId(7L);
            PriceCalcResult r = engine.calculate(c);
            assertIdentity(r);
            // 每层分摊额守恒
            assertEquals(r.getProductPromoFen(),
                    r.getItemDetails().stream().mapToLong(ItemPriceDetail::getProductPromoFen).sum());
            assertEquals(r.getShopPromoFen(),
                    r.getItemDetails().stream().mapToLong(ItemPriceDetail::getShopPromoFen).sum());
            assertEquals(r.getPointsDeductFen(),
                    r.getItemDetails().stream().mapToLong(ItemPriceDetail::getPointsAllocFen).sum());
            assertEquals(r.getFreightFen(),
                    r.getItemDetails().stream().mapToLong(ItemPriceDetail::getFreightAllocFen).sum());
        }
    }
}
