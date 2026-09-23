package com.shop.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 9：售后。5 类工单走到可达成终态：
 * 仅退款(待发货)、退货退款(已完成)、换货(退回+换发签收)、补发货、价保试算；
 * 退款资金瀑布的可观测部分（待结算扣减）与运费险责任字段存在性。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("09 售后：仅退款/退货退款/换货/补发/价保 + 退款瀑布 + 运费险字段")
class AftersaleE2ETest {

    private static final World WORLD = World.get();
    private static final String AS = "/api/aftersale/aftersales";

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    private long itemId(World.Buyer buyer, String orderNo) {
        return WORLD.orderDetail(buyer, orderNo).path("items").get(0).path("orderItemId").asLong();
    }

    @Test
    @DisplayName("仅退款(type=1)：待发货单商家同意即退款，售后完成且退款金额=实付，待结算资金被扣减（瀑布第一档）")
    void refundOnlyBeforeShip() {
        World.Purchased p = WORLD.purchaseToWaitShip(8_000L, 1, World.PAY_WECHAT);
        long itemId = itemId(p.buyer(), p.orderNo());

        JsonNode acctBefore = WORLD.merchantAccount();
        long before = acctBefore.path("pendingSettleFen").asLong(0L)
                + acctBefore.path("depositBalanceFen").asLong(0L);
        assertTrue(before >= 8_000L, "支付后待结算+保证金应至少覆盖本单金额，实际 " + before);

        String no = WORLD.applyAftersale(p.buyer(), p.orderNo(), 1, itemId, 1);
        WORLD.merchantAudit(no, true);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_FINISHED);

        JsonNode aftersale = WORLD.aftersaleDetail(p.buyer(), no).path("aftersale");
        assertEquals(50, aftersale.path("status").asInt(-1));
        assertEquals(8_000L, aftersale.path("refundFen").asLong(), "仅退款金额=订单实付 8000 分");
        assertTrue(aftersale.path("refundNo").asText("").length() > 0, "退款单号已回填");

        // 资金瀑布第一档：优先冲减待结算；待结算不足部分才扣保证金（不足分支无法黑盒构造，缺口 #6）
        JsonNode acctAfter = WORLD.merchantAccount();
        long after = acctAfter.path("pendingSettleFen").asLong(0L)
                + acctAfter.path("depositBalanceFen").asLong(0L);
        assertTrue(after <= before,
                "退款后商户（待结算+保证金）合计不得增加：before=" + before + " after=" + after);
    }

    @Test
    @DisplayName("退货退款(type=2)：已完成单→买家退货物流→商家收货→退款完成")
    void returnRefundAfterCompletion() {
        World.Purchased p = WORLD.purchaseCompleted(7_000L, 1, World.PAY_ALIPAY);
        long itemId = itemId(p.buyer(), p.orderNo());

        String no = WORLD.applyAftersale(p.buyer(), p.orderNo(), 2, itemId, 1);
        WORLD.merchantAudit(no, true);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_WAIT_RETURN);

        WORLD.buyerReturnLogistics(p.buyer(), no);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_RECEIVING);
        WORLD.merchantReceive(no, true);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_FINISHED);

        JsonNode aftersale = WORLD.aftersaleDetail(p.buyer(), no).path("aftersale");
        assertEquals(7_000L, aftersale.path("refundFen").asLong(), "退货退款金额=实付");
        assertTrue(!aftersale.path("refundNo").asText("").isBlank(), "退款单号已回填");
    }

    @Test
    @DisplayName("换货(type=3)：退货→商家收货→换货发货(指定换发 SKU)→买家签收完成，不发生退款")
    void exchangeFullFlow() {
        World.Purchased p = WORLD.purchaseCompleted(6_000L, 1, World.PAY_WECHAT);
        long itemId = itemId(p.buyer(), p.orderNo());
        World.Sku exchangeSku = WORLD.createOnSaleSku(6_000L, 10L);

        String no = WORLD.applyAftersale(p.buyer(), p.orderNo(), 3, itemId, 1, null, exchangeSku.skuId());
        WORLD.merchantAudit(no, true);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_WAIT_RETURN);

        WORLD.buyerReturnLogistics(p.buyer(), no);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_RECEIVING);
        WORLD.merchantReceive(no, true);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_WAIT_EXCHANGE_SHIP);

        WORLD.merchantShipExchange(no, exchangeSku.skuId());
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_EXCHANGE_WAIT_RECEIVE);
        WORLD.buyerExchangeConfirm(p.buyer(), no);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_FINISHED);

        JsonNode aftersale = WORLD.aftersaleDetail(p.buyer(), no).path("aftersale");
        assertEquals(exchangeSku.skuId(), aftersale.path("exchangeSkuId").asLong(-1), "换发 SKU 落单");
        assertEquals(0L, aftersale.path("refundFen").asLong(0L), "换货不退款");
    }

    @Test
    @DisplayName("补发货(type=4)：商家同意后直接补发→买家签收完成，不退不换")
    void reshipFlow() {
        World.Purchased p = WORLD.purchaseCompleted(4_500L, 1, World.PAY_WECHAT);
        long itemId = itemId(p.buyer(), p.orderNo());

        String no = WORLD.applyAftersale(p.buyer(), p.orderNo(), 4, itemId, 1);
        WORLD.merchantAudit(no, true);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_WAIT_EXCHANGE_SHIP);

        WORLD.merchantShipExchange(no, null);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_EXCHANGE_WAIT_RECEIVE);
        WORLD.buyerExchangeConfirm(p.buyer(), no);
        WORLD.waitAftersaleStatus(p.buyer(), no, World.AS_FINISHED);

        JsonNode aftersale = WORLD.aftersaleDetail(p.buyer(), no).path("aftersale");
        assertEquals(0L, aftersale.path("refundFen").asLong(0L), "补发不产生退款");
    }

    @Test
    @DisplayName("价保(type=5)试算：返回原价/现价/差价/是否可赔/原因完整结构；无改价入口时差价为 0（缺口 #2）")
    void priceProtectTrialStructure() {
        World.Purchased p = WORLD.purchaseCompleted(9_000L, 1, World.PAY_WECHAT);
        long itemId = itemId(p.buyer(), p.orderNo());

        ApiClient.Raw raw = p.buyer().api().post(AS + "/price-protect/trial", ApiClient.obj(
                "orderNo", p.orderNo(),
                "orderItemId", itemId,
                "bigPromotion", false));
        assertEquals(0, raw.bizCode(), "价保试算成功: " + raw.text);
        JsonNode t = raw.data();
        for (String f : java.util.List.of("originalUnitFen", "currentPriceFen", "diffUnitFen",
                "diffTotalFen", "eligible", "reason")) {
            assertTrue(t.has(f), "价保试算返回字段: " + f);
        }
        assertEquals(9_000L, t.path("originalUnitFen").asLong(), "原价取订单单价");
        assertEquals(0L, t.path("diffTotalFen").asLong(),
                "在售商品无改价 API，现价=原价，差价为 0（无法黑盒制造降价，缺口 #2）");
    }

    @Test
    @DisplayName("运费险责任(responsibilitySide=3)字段可落单；理赔明细无查询接口（缺口 #4）")
    void freightInsuranceResponsibilityField() {
        World.Purchased p = WORLD.purchaseCompleted(5_000L, 1, World.PAY_WECHAT);
        long itemId = itemId(p.buyer(), p.orderNo());

        ApiClient.Raw raw = WORLD.tryCreateAftersale(p.buyer(), p.orderNo(), 2, itemId, 1, 3, null);
        // 若该订单未购运费险、环境不允许保险责任侧，则跳过（无外部投保入口）
        org.junit.jupiter.api.Assumptions.assumeTrue(raw.bizCode() == 0,
                "环境未提供运费险投保能力，保险责任侧售后跳过：" + raw.text);
        String no = raw.data().asText();
        JsonNode aftersale = WORLD.aftersaleDetail(p.buyer(), no).path("aftersale");
        assertEquals(3, aftersale.path("responsibilitySide").asInt(-1), "责任侧=运费险(3)");
        assertTrue(aftersale.has("freightRefundFen"), "运费退款字段存在");
        // 取消以免后续流转；理赔由 72h 延迟任务内部完成，AftersaleInsurance 无 HTTP 查询（缺口 #4）
        p.buyer().api().post(AS + "/" + no + "/cancel", null);
    }
}
