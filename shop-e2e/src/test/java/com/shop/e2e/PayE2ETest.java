package com.shop.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.DataFactory;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 6：支付。7 种支付方式（6 个 mock 渠道回调 + 余额即时成功）、
 * 坏签名拒绝且状态不变、同一 notifyId 重复回调幂等、组合支付成交后按比例退款
 * （拆分采用最大余数法，明细无 HTTP 投影，见报告缺口 #6）。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("06 支付：7 渠道回调/坏签名拒绝/回调幂等/组合支付按比例退款")
class PayE2ETest {

    private static final World WORLD = World.get();

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    /** 1 微信 2 支付宝 4 银行卡 5 云闪付 6 花呗 7 白条。 */
    @ParameterizedTest(name = "mock 渠道 payMethod={0} 成功回调后订单待发货")
    @ValueSource(ints = {1, 2, 4, 5, 6, 7})
    @DisplayName("6 个 mock 渠道：HMAC 正确的 SUCCESS 回调驱动支付成功")
    void mockChannelCallbackSuccess(int payMethod) {
        World.Purchased p = WORLD.purchaseToWaitShip(8_800L, 1, payMethod);
        JsonNode pay = WORLD.paymentView(p.buyer(), p.payment().payNo());
        assertEquals(30, pay.path("status").asInt(-1), "支付单状态=成功(30)，渠道 " + payMethod);
        assertEquals(p.payment().amountFen(), pay.path("amountFen").asLong());
        assertEquals(20, WORLD.orderStatus(p.buyer(), p.orderNo()),
                "支付成功后订单进入待发货(20)，渠道 " + payMethod);
    }

    @Test
    @DisplayName("余额支付：支付单创建即成功，订单进入待发货（账户余额需环境预置，否则跳过）")
    void balancePayImmediateSuccess() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(5_000L, 10L);
        String orderNo = WORLD.createOrder(buyer, WORLD.baseOrderBody(buyer, sku, 1));

        ApiClient.Raw raw = buyer.api().post("/api/pay/pays", ApiClient.obj(
                "orderNo", orderNo,
                "payMethod", World.PAY_BALANCE,
                "amountFen", 5_000L,
                "subject", "E2E余额单",
                "terminal", 1));
        // 新注册用户无余额，且无 C 端充值入口（缺口 #1/#7）：环境未预置余额时跳过
        org.junit.jupiter.api.Assumptions.assumeTrue(raw.bizCode() == 0,
                "买家余额不足且无外部充值入口，跳过余额正向支付：" + raw.text);
        String payNo = raw.data().path("payNo").asText();
        WORLD.waitOrderStatus(buyer, orderNo, World.OS_WAIT_SHIP);
        assertEquals(30, WORLD.paymentView(buyer, payNo).path("status").asInt(-1),
                "余额支付无需回调，创建即成功(30)");
    }

    @Test
    @DisplayName("坏签名回调被拒（60002）：支付单仍待支付(10)，订单仍待付款(10)")
    void badSignatureRejectedAndStateUnchanged() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(9_900L, 10L);
        String orderNo = WORLD.createOrder(buyer, WORLD.baseOrderBody(buyer, sku, 1));
        World.Payment payment = WORLD.createPayment(buyer, orderNo, World.PAY_WECHAT);

        ApiClient.Raw bad = WORLD.sendBadSignatureNotify(buyer, payment);
        assertNotEquals(0, bad.bizCode(), "坏签名必须业务拒绝");
        assertEquals(60002, bad.bizCode(), "验签失败码 60002，实际: " + bad.text);

        JsonNode pay = WORLD.paymentView(buyer, payment.payNo());
        assertEquals(10, pay.path("status").asInt(-1), "坏签名不改变支付单状态，仍待支付(10)");
        assertEquals(10, WORLD.orderStatus(buyer, orderNo), "订单仍待付款(10)");
    }

    @Test
    @DisplayName("同一 notifyId 重复回调幂等：支付成功一次，订单只进入一次待发货")
    void duplicateNotifyIdempotent() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(12_000L, 10L);
        String orderNo = WORLD.createOrder(buyer, WORLD.baseOrderBody(buyer, sku, 1));
        World.Payment payment = WORLD.createPayment(buyer, orderNo, World.PAY_ALIPAY);

        String notifyId = DataFactory.notifyId();
        ApiClient.Raw first = WORLD.sendSuccessNotify(buyer, payment, notifyId);
        assertEquals(0, first.bizCode(), "首次回调成功: " + first.text);
        WORLD.waitOrderStatus(buyer, orderNo, World.OS_WAIT_SHIP);

        // 完全相同的回调（同 notifyId/渠道流水/签名）重放
        ApiClient.Raw again = WORLD.sendSuccessNotify(buyer, payment, notifyId);
        assertEquals(0, again.bizCode(), "重复回调幂等 ACK，不报错");
        assertEquals(30, WORLD.paymentView(buyer, payment.payNo()).path("status").asInt(-1),
                "支付单仍是成功(30)，无重复状态机推进");
        assertEquals(20, WORLD.orderStatus(buyer, orderNo));
    }

    @Test
    @DisplayName("组合支付（微信 7000 + 支付宝 3000）成交，全额退款按资金渠道比例拆分且总额守恒")
    void mixedPaymentRefundProportional() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(10_000L, 10L);
        String orderNo = WORLD.createOrder(buyer, WORLD.baseOrderBody(buyer, sku, 1));

        // parts 合计必须等于支付单金额；任一渠道 SUCCESS 回调（全额）后全部资金流置成功
        World.Payment payment = WORLD.createMixedPayment(buyer, orderNo, 10_000L,
                new int[]{World.PAY_WECHAT, World.PAY_ALIPAY}, new long[]{7_000L, 3_000L});
        ApiClient.Raw notify = WORLD.sendSuccessNotify(buyer, payment, DataFactory.notifyId());
        assertEquals(0, notify.bizCode(), "组合支付回调成功: " + notify.text);
        WORLD.waitOrderStatus(buyer, orderNo, World.OS_WAIT_SHIP);

        // 待发货状态申请仅退款（全额）→ 商家审核通过即触发退款
        long itemId = WORLD.orderDetail(buyer, orderNo).path("items").get(0).path("orderItemId").asLong();
        String aftersaleNo = WORLD.applyAftersale(buyer, orderNo, 1, itemId, 1);
        WORLD.merchantAudit(aftersaleNo, true);
        WORLD.waitAftersaleStatus(buyer, aftersaleNo, World.AS_FINISHED);

        JsonNode as = WORLD.aftersaleDetail(buyer, aftersaleNo).path("aftersale");
        String refundNo = as.path("refundNo").asText();
        assertTrue(refundNo != null && !refundNo.isBlank(), "售后完成应登记 refundNo");
        assertEquals(10_000L, as.path("refundFen").asLong(), "全额退款金额=支付金额（拆分合计守恒）");

        JsonNode refund = WORLD.refundView(buyer, refundNo);
        assertEquals(30, refund.path("status").asInt(-1), "退款成功(30)");
        assertEquals(10_000L, refund.path("amountFen").asLong(), "退款总额 10000 分");
        // 7:3 的渠道级最大余数拆分在资金流水内部完成，无 HTTP 明细投影（报告缺口 #6）
        assertTrue(WORLD.paymentView(buyer, refund.path("payNo").asText()).path("status").asInt() == 70
                        || WORLD.paymentView(buyer, refund.path("payNo").asText()).path("status").asInt() == 60,
                "原支付单进入已退款(70)/退款中(60)");
    }
}
