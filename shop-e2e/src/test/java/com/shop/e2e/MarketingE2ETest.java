package com.shop.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 3：营销域。管理端券/秒杀/拼团/预售活动创建与启用；用户端券中心、领取、我的券；
 * 系统发券收口（H-1 安全契约）；六层试算与最大余数分摊守恒；活动价试算。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("03 营销域：券创建发放领取/秒杀拼团预售/六层试算与最大余数分摊")
class MarketingE2ETest {

    private static final World WORLD = World.get();
    private static final String MKT = "/api/marketing";

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    @Test
    @DisplayName("平台建券并上架：券中心可见→用户领取→我的券状态为未使用(0)")
    void couponCreateCenterClaimAndMy() {
        long couponId = WORLD.createClaimableCoupon(2_000L, 100, 1);
        World.Buyer buyer = WORLD.newBuyer();

        ApiClient.Raw center = ApiClient.create().get(MKT + "/coupons/center");
        assertEquals(0, center.bizCode(), "券中心匿名可访问(白名单)");
        assertTrue(center.text.contains(String.valueOf(couponId)), "券中心包含新券模板");

        long userCouponId = WORLD.claimCoupon(buyer, couponId);
        assertTrue(userCouponId > 0, "领取成功应返回用户券 ID");

        JsonNode mine = buyer.api().get(MKT + "/coupons/my?status=0").data();
        boolean found = false;
        for (JsonNode uc : mine) {
            if (uc.path("couponId").asLong() == couponId) {
                found = true;
                assertEquals(0, uc.path("status").asInt(-1), "领取后券状态未使用(0)");
            }
        }
        assertTrue(found, "我的券列表包含已领取的券");
    }

    @Test
    @DisplayName("同一用户重复领取同一张券被拒绝（每券每人限 1 张）")
    void duplicateClaimRejected() {
        long couponId = WORLD.createClaimableCoupon(1_000L, 100, 1);
        World.Buyer buyer = WORLD.newBuyer();
        assertTrue(WORLD.claimCoupon(buyer, couponId) > 0, "首次领取成功");
        assertEquals(-1L, WORLD.claimCoupon(buyer, couponId), "重复领取必须失败");
    }

    @Test
    @DisplayName("H-1 安全契约：系统发券已收口为内网 Feign，C 端 /coupons/issue 不可达")
    void issueCouponIsInnerOnly() {
        World.Buyer buyer = WORLD.newBuyer();
        long couponId = WORLD.createClaimableCoupon(500L, 100, 1);
        // C 端用户直接向任意 userId 发券：端点不存在（404/405 或业务错误），绝不允许 code=0
        ApiClient.Raw raw = buyer.api().post(MKT + "/coupons/issue", ApiClient.obj(
                "userId", buyer.userId(),
                "couponId", couponId,
                "issueWay", 4,
                "requestNo", "E2E-ISSUE-" + System.currentTimeMillis()));
        assertNotEquals(0, raw.bizCode(), "C 端系统发券入口必须不可用");

        // 网关 inner 路径同样 404/10404
        ApiClient.Raw inner = buyer.api().rawRequest("POST",
                MKT + "/inner/marketing/coupon/issue",
                ApiClient.obj("userId", buyer.userId(), "couponId", couponId));
        assertEquals(404, inner.httpStatus);
        assertEquals(10404, inner.bizCode());
    }

    @Test
    @DisplayName("秒杀活动：管理端创建启用（含独立秒杀库存）→ 用户端按秒杀价试算")
    void seckillActivityAndTrial() {
        // SKU 日常价 10000，秒杀价 5000，普通库存池备足（秒杀走独立库存 totalStock）
        World.Sku sku = WORLD.createOnSaleSku(10_000L, 100L, 5_000L, null, 1);
        long activityId = WORLD.createSeckillActivity(sku.skuId(), 5_000L, 20);

        World.Buyer buyer = WORLD.newBuyer();
        ObjectNode cmd = baseCmd(buyer, 2, sku, 5_000L, 1);
        cmd.put("seckillActivityId", activityId);
        ApiClient.Raw raw = buyer.api().post(MKT + "/h5/marketing/calculate", cmd);
        assertEquals(0, raw.bizCode(), "秒杀试算成功: " + raw.text);
        JsonNode r = raw.data();
        assertEquals(5_000L, r.path("payFen").asLong(), "秒杀应付=秒杀价");
        assertEquals(5_000L, r.path("originalProductFen").asLong(), "活动价由订单域上送作为试算原价");
    }

    @Test
    @DisplayName("拼团活动：管理端创建启用 → 用户端拼团价试算成功（开团/参团无独立 HTTP，见报告缺口 #5）")
    void groupbuyActivityAndTrial() {
        World.Sku sku = WORLD.createOnSaleSku(10_000L, 100L);
        long activityId = WORLD.createGroupbuyActivity();

        World.Buyer buyer = WORLD.newBuyer();
        ObjectNode cmd = baseCmd(buyer, 3, sku, 8_000L, 1);
        cmd.put("groupbuyActivityId", activityId);
        ApiClient.Raw raw = buyer.api().post(MKT + "/h5/marketing/calculate", cmd);
        assertEquals(0, raw.bizCode(), "拼团价试算成功: " + raw.text);
        assertEquals(8_000L, raw.data().path("payFen").asLong(), "拼团应付=上送拼团价");
    }

    @Test
    @DisplayName("预售活动：管理端创建启用 → 定金阶段与尾款阶段试算均成功（登记无独立 HTTP，见报告缺口 #5）")
    void presaleActivityTwoStagesTrial() {
        World.Sku sku = WORLD.createOnSaleSku(10_000L, 100L, null, 1, 2);
        // 定金 2000 + 膨胀抵扣 500 + 尾款 7500 = 10000
        long activityId = WORLD.createPresaleActivity(2_000L, 500L, 7_500L);

        World.Buyer buyer = WORLD.newBuyer();
        ObjectNode deposit = baseCmd(buyer, 4, sku, 2_000L, 1);
        deposit.put("presaleActivityId", activityId);
        deposit.put("presaleFinalStage", false);
        ApiClient.Raw r1 = buyer.api().post(MKT + "/h5/marketing/calculate", deposit);
        assertEquals(0, r1.bizCode(), "定金阶段试算成功: " + r1.text);
        assertTrue(r1.data().path("payFen").asLong() > 0, "定金阶段应付为正");

        ObjectNode finality = baseCmd(buyer, 4, sku, 7_500L, 1);
        finality.put("presaleActivityId", activityId);
        finality.put("presaleFinalStage", true);
        ApiClient.Raw r2 = buyer.api().post(MKT + "/h5/marketing/calculate", finality);
        assertEquals(0, r2.bizCode(), "尾款阶段试算成功: " + r2.text);
        assertTrue(r2.data().path("payFen").asLong() > 0, "尾款阶段应付为正");
    }

    @Test
    @DisplayName("六层试算：3 个等值商品用 1000 分无门槛券，最大余数分摊 333/333/334 且总分摊守恒")
    void sixLayerCalcLargestRemainderAllocation() {
        // 3 个独立在售 SKU，各 1000 分
        World.Sku s1 = WORLD.createOnSaleSku(1_000L, 50L);
        World.Sku s2 = WORLD.createOnSaleSku(1_000L, 50L);
        World.Sku s3 = WORLD.createOnSaleSku(1_000L, 50L);
        long couponId = WORLD.createClaimableCoupon(1_000L, 100, 1);
        World.Buyer buyer = WORLD.newBuyer();
        long userCouponId = WORLD.claimCoupon(buyer, couponId);
        assertTrue(userCouponId > 0);

        ObjectNode cmd = ApiClient.obj(
                "userId", buyer.userId(),
                "userLevel", 0,
                "orderType", 1,
                "freightFen", 0L,
                "platformCouponId", userCouponId);
        ArrayNode items = cmd.arrayNode();
        for (World.Sku sku : List.of(s1, s2, s3)) {
            items.add(calcItem(sku, 1_000L, 1));
        }
        cmd.set("items", items);

        ApiClient.Raw raw = buyer.api().post(MKT + "/h5/marketing/calculate", cmd);
        assertEquals(0, raw.bizCode(), "六层试算成功: " + raw.text);
        JsonNode r = raw.data();

        // 六层字段全部存在
        for (String f : List.of("originalProductFen", "productPromoFen", "shopPromoFen",
                "categoryCouponFen", "shopCouponFen", "platformCouponFen",
                "freightCouponFen", "pointsDeductFen", "freightFen", "payFen")) {
            assertTrue(r.has(f), "试算结果包含六层字段: " + f);
        }

        assertEquals(3_000L, r.path("originalProductFen").asLong());
        assertEquals(1_000L, r.path("platformCouponFen").asLong(), "平台券抵扣面额 1000 分");
        long expectedPay = r.path("originalProductFen").asLong()
                - r.path("productPromoFen").asLong()
                - r.path("shopPromoFen").asLong()
                - r.path("categoryCouponFen").asLong()
                - r.path("shopCouponFen").asLong()
                - r.path("platformCouponFen").asLong()
                - r.path("pointsDeductFen").asLong()
                + r.path("freightFen").asLong()
                - r.path("freightCouponFen").asLong();
        assertEquals(expectedPay, r.path("payFen").asLong(),
                "恒等式：payFen = 原价 − 商品层 − 店铺层 − 三类券 − 积分 + 运费 − 运费券");
        assertEquals(2_000L, r.path("payFen").asLong(), "3000 − 1000 券 = 2000");

        JsonNode details = r.path("itemDetails");
        assertEquals(3, details.size());
        List<Long> couponAlloc = new ArrayList<>();
        long sumOriginal = 0, sumPaid = 0, sumCoupon = 0, sumProduct = 0, sumShop = 0, sumPoints = 0;
        for (JsonNode d : details) {
            couponAlloc.add(d.path("couponAllocFen").asLong());
            sumOriginal += d.path("originalFen").asLong();
            sumPaid += d.path("paidFen").asLong();
            sumCoupon += d.path("couponAllocFen").asLong();
            sumProduct += d.path("productPromoFen").asLong();
            sumShop += d.path("shopPromoFen").asLong();
            sumPoints += d.path("pointsAllocFen").asLong();
            // 行内守恒（运费分摊单独列）
            assertEquals(d.path("originalFen").asLong(),
                    d.path("productPromoFen").asLong() + d.path("shopPromoFen").asLong()
                            + d.path("couponAllocFen").asLong() + d.path("pointsAllocFen").asLong()
                            + d.path("paidFen").asLong(),
                    "单行分摊守恒：原价 = 商品优惠 + 店铺优惠 + 券分摊 + 积分分摊 + 实付");
        }
        assertEquals(3_000L, sumOriginal);
        assertEquals(1_000L, sumCoupon, "券分摊总额=券面额，不丢分不多分");
        assertEquals(2_000L, sumPaid, "行实付合计=2000");
        assertEquals(3_000L, sumPaid + sumCoupon + sumProduct + sumShop + sumPoints,
                "所有分摊行求和回到订单原价");

        // 最大余数法：333/333/334 —— 差额不超过 1，且只有一个槽位多拿 1 分
        Collections.sort(couponAlloc);
        assertEquals(List.of(333L, 333L, 334L), couponAlloc, "最大余数分摊结果");
        long max = couponAlloc.get(2);
        assertEquals(1, couponAlloc.stream().filter(v -> v == max).count(), "余出的 1 分只落在一个槽位");
    }

    private ObjectNode baseCmd(World.Buyer buyer, int orderType, World.Sku sku, long unitPriceFen, int qty) {
        ObjectNode cmd = ApiClient.obj(
                "userId", buyer.userId(),
                "userLevel", 0,
                "orderType", orderType,
                "freightFen", 0L);
        ArrayNode items = cmd.arrayNode();
        items.add(calcItem(sku, unitPriceFen, qty));
        cmd.set("items", items);
        return cmd;
    }

    private ObjectNode calcItem(World.Sku sku, long unitPriceFen, int qty) {
        return ApiClient.obj(
                "skuId", sku.skuId(),
                "spuId", sku.spuId(),
                "merchantId", WORLD.merchantId(),
                "shopId", sku.shopId(),
                "category3Id", WORLD.baseCategory3Id(),
                "qty", qty,
                "salePriceFen", unitPriceFen);
    }
}
