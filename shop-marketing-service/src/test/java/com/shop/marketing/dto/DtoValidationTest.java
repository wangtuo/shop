package com.shop.marketing.dto;

import com.shop.marketing.activity.dto.ActivitySaveRequest;
import com.shop.marketing.coupon.dto.ClaimRequest;
import com.shop.marketing.coupon.dto.CouponSaveRequest;
import com.shop.marketing.promo.dto.PromoSaveRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 卡 API-M：三保存 DTO + ClaimRequest 非法报文 Bean Validation 校验。
 */
class DtoValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private boolean invalid(Object bean) {
        Set<ConstraintViolation<Object>> violations = validator.validate(bean);
        return !violations.isEmpty();
    }

    // ---------------- Activity ----------------

    private ActivitySaveRequest baseActivity() {
        ActivitySaveRequest req = new ActivitySaveRequest();
        req.setName("秒杀活动");
        req.setType(10);
        req.setStartTime(LocalDateTime.now().plusDays(1));
        req.setEndTime(LocalDateTime.now().plusDays(2));
        req.setSeckillSkus(new ArrayList<>());
        return req;
    }

    @Test
    void 活动合法报文通过() {
        ActivitySaveRequest req = baseActivity();
        ActivitySaveRequest.SeckillSkuRequest sku = new ActivitySaveRequest.SeckillSkuRequest();
        sku.setSkuId(11L);
        sku.setSeckillPriceFen(100L);
        sku.setTotalStock(10);
        req.getSeckillSkus().add(sku);
        assertFalse(invalid(req));
    }

    @Test
    void 活动非法报文全部拒绝() {
        // type=99 越界
        ActivitySaveRequest r1 = baseActivity();
        r1.setType(99);
        assertTrue(invalid(r1));
        // type=0
        ActivitySaveRequest r2 = baseActivity();
        r2.setType(0);
        assertTrue(invalid(r2));
        // name 空
        ActivitySaveRequest r3 = baseActivity();
        r3.setName(" ");
        assertTrue(invalid(r3));
        // 嵌套：负价 / 0 价 / 0 库存 / 负库存 / skuId 空
        for (Long price : new Long[]{-1L, 0L}) {
            ActivitySaveRequest r = baseActivity();
            ActivitySaveRequest.SeckillSkuRequest sku = new ActivitySaveRequest.SeckillSkuRequest();
            sku.setSkuId(11L);
            sku.setSeckillPriceFen(price);
            sku.setTotalStock(10);
            r.getSeckillSkus().add(sku);
            assertTrue(invalid(r), "秒杀价 " + price);
        }
        for (Integer stock : new Integer[]{0, -5}) {
            ActivitySaveRequest r = baseActivity();
            ActivitySaveRequest.SeckillSkuRequest sku = new ActivitySaveRequest.SeckillSkuRequest();
            sku.setSkuId(11L);
            sku.setSeckillPriceFen(100L);
            sku.setTotalStock(stock);
            r.getSeckillSkus().add(sku);
            assertTrue(invalid(r), "库存 " + stock);
        }
        ActivitySaveRequest rNoSku = baseActivity();
        ActivitySaveRequest.SeckillSkuRequest sku0 = new ActivitySaveRequest.SeckillSkuRequest();
        sku0.setSeckillPriceFen(100L);
        sku0.setTotalStock(10);
        rNoSku.getSeckillSkus().add(sku0);
        assertTrue(invalid(rNoSku));
        // seckillSkus 超 200
        ActivitySaveRequest rBig = baseActivity();
        for (int i = 0; i < 201; i++) {
            ActivitySaveRequest.SeckillSkuRequest sku = new ActivitySaveRequest.SeckillSkuRequest();
            sku.setSkuId((long) i);
            sku.setSeckillPriceFen(100L);
            sku.setTotalStock(1);
            rBig.getSeckillSkus().add(sku);
        }
        assertTrue(invalid(rBig));
        // autoEnd 越界
        ActivitySaveRequest rAuto = baseActivity();
        rAuto.setAutoEnd(9);
        assertTrue(invalid(rAuto));
    }

    // ---------------- Coupon ----------------

    private CouponSaveRequest baseCoupon() {
        CouponSaveRequest req = new CouponSaveRequest();
        req.setName("满减券");
        req.setType(1);
        req.setScopeType(1);
        req.setFaceValueFen(100L);
        req.setThresholdFen(1000L);
        req.setDiscountBp(1000);
        req.setMaxDiscountFen(0L);
        req.setTotalCount(100);
        req.setPerUserLimit(1);
        req.setIssueWay(1);
        req.setValidType(1);
        req.setReceiveStartTime(LocalDateTime.now().plusDays(1));
        req.setReceiveEndTime(LocalDateTime.now().plusDays(2));
        req.setTargets(new ArrayList<>());
        return req;
    }

    @Test
    void 券合法报文通过() {
        assertFalse(invalid(baseCoupon()));
    }

    @Test
    void 券非法报文全部拒绝() {
        class Case {
            final String name;
            final java.util.function.Consumer<CouponSaveRequest> mutator;

            Case(String name, java.util.function.Consumer<CouponSaveRequest> mutator) {
                this.name = name;
                this.mutator = mutator;
            }
        }
        List<Case> cases = List.of(
                new Case("name 空", r -> r.setName("")),
                new Case("type=99", r -> r.setType(99)),
                new Case("type=0", r -> r.setType(0)),
                new Case("scopeType=9", r -> r.setScopeType(9)),
                new Case("validType=3", r -> r.setValidType(3)),
                new Case("issueWay=9", r -> r.setIssueWay(9)),
                new Case("面额负", r -> r.setFaceValueFen(-1L)),
                new Case("门槛负", r -> r.setThresholdFen(-1L)),
                new Case("最大优惠负", r -> r.setMaxDiscountFen(-2L)),
                new Case("discountBp=1001", r -> r.setDiscountBp(1001)),
                new Case("discountBp=0", r -> r.setDiscountBp(0)),
                new Case("discountBp=-5", r -> r.setDiscountBp(-5)),
                new Case("总量负", r -> r.setTotalCount(-1)),
                new Case("限领负", r -> r.setPerUserLimit(-1)),
                new Case("newUserGift=9", r -> r.setNewUserGift(9))
        );
        for (Case c : cases) {
            CouponSaveRequest req = baseCoupon();
            c.mutator.accept(req);
            assertTrue(invalid(req), c.name);
        }
        assertFalse(invalid(baseCoupon()), "基准报文必须合法");

        // 嵌套 target 非法 + targets 超 500
        CouponSaveRequest rTarget = baseCoupon();
        CouponSaveRequest.Target t = new CouponSaveRequest.Target();
        t.setTargetType(0);
        t.setTargetId(1L);
        rTarget.getTargets().add(t);
        assertTrue(invalid(rTarget));

        CouponSaveRequest rBig = baseCoupon();
        for (int i = 0; i < 501; i++) {
            CouponSaveRequest.Target tt = new CouponSaveRequest.Target();
            tt.setTargetType(1);
            tt.setTargetId((long) i);
            rBig.getTargets().add(tt);
        }
        assertTrue(invalid(rBig));
    }

    // ---------------- Promo ----------------

    private PromoSaveRequest basePromo() {
        PromoSaveRequest req = new PromoSaveRequest();
        req.setName("满减促销");
        req.setType(1);
        req.setScopeType(1);
        req.setStartTime(LocalDateTime.now().plusDays(1));
        req.setEndTime(LocalDateTime.now().plusDays(2));
        req.setLevels(new ArrayList<>());
        req.setTargets(new ArrayList<>());
        return req;
    }

    @Test
    void 促销合法报文通过() {
        PromoSaveRequest req = basePromo();
        PromoSaveRequest.Level lv = new PromoSaveRequest.Level();
        lv.setThresholdFen(1000L);
        lv.setReduceFen(100L);
        lv.setDiscountBp(1000);
        lv.setNthIndex(2);
        lv.setGiftSkuId(9L);
        lv.setGiftQty(1);
        req.getLevels().add(lv);
        assertFalse(invalid(req));
    }

    @Test
    void 促销非法报文全部拒绝() {
        // type=9 / scopeType=9
        PromoSaveRequest r1 = basePromo();
        r1.setType(9);
        assertTrue(invalid(r1));
        PromoSaveRequest r2 = basePromo();
        r2.setScopeType(9);
        assertTrue(invalid(r2));
        // 嵌套 Level 非法
        PromoSaveRequest rNeg = basePromo();
        PromoSaveRequest.Level lvNeg = new PromoSaveRequest.Level();
        lvNeg.setReduceFen(-1L);
        rNeg.getLevels().add(lvNeg);
        assertTrue(invalid(rNeg));

        PromoSaveRequest rBp = basePromo();
        PromoSaveRequest.Level lvBp = new PromoSaveRequest.Level();
        lvBp.setDiscountBp(1001);
        rBp.getLevels().add(lvBp);
        assertTrue(invalid(rBp));

        PromoSaveRequest rNth = basePromo();
        PromoSaveRequest.Level lvNth = new PromoSaveRequest.Level();
        lvNth.setNthIndex(1);
        rNth.getLevels().add(lvNth);
        assertTrue(invalid(rNth));

        PromoSaveRequest rQty = basePromo();
        PromoSaveRequest.Level lvQty = new PromoSaveRequest.Level();
        lvQty.setGiftQty(-1);
        rQty.getLevels().add(lvQty);
        assertTrue(invalid(rQty));

        // levels 超 50 / targets 超 500
        PromoSaveRequest rBigLv = basePromo();
        for (int i = 0; i < 51; i++) {
            PromoSaveRequest.Level lv = new PromoSaveRequest.Level();
            lv.setThresholdFen(0L);
            rBigLv.getLevels().add(lv);
        }
        assertTrue(invalid(rBigLv));

        PromoSaveRequest rBigT = basePromo();
        for (int i = 0; i < 501; i++) {
            PromoSaveRequest.Target t = new PromoSaveRequest.Target();
            t.setTargetType(1);
            t.setTargetId((long) i);
            rBigT.getTargets().add(t);
        }
        assertTrue(invalid(rBigT));
    }

    // ---------------- ClaimRequest ----------------

    @Test
    void 领券请求校验() {
        ClaimRequest ok = new ClaimRequest();
        ok.setCouponId(1L);
        ok.setRequestNo("abc-123");
        assertFalse(invalid(ok));

        ClaimRequest noId = new ClaimRequest();
        assertTrue(invalid(noId));

        ClaimRequest longNo = new ClaimRequest();
        longNo.setCouponId(1L);
        longNo.setRequestNo("x".repeat(65));
        assertTrue(invalid(longNo));
    }
}
