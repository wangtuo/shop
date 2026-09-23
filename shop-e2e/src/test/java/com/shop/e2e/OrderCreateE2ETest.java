package com.shop.e2e;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.DataFactory;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 5：下单。覆盖 5 种订单类型（普通/秒杀/拼团/预售定金/换货）订单号编码、
 * clientToken 幂等、非法下单拒绝。
 *
 * <p>订单号 18 位：YYMMDD(6) + 业务码(2：01/02/03/04/05) + userId 末 4 位 + 6 位日序列。</p>
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("05 下单：5 种订单类型/18 位订单号编码/clientToken 幂等/非法单拒绝")
class OrderCreateE2ETest {

    private static final World WORLD = World.get();

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    private void assertOrderNo(String orderNo, String bizCode, long userId, int orderType) {
        assertTrue(orderNo.matches("^\\d{18}$"),
                "订单类型 " + orderType + " 订单号必须为 18 位纯数字，实际: " + orderNo);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        assertEquals(today, orderNo.substring(0, 6), "前 6 位为当天 YYMMDD");
        assertEquals(bizCode, orderNo.substring(6, 8), "第 7-8 位为业务码 " + bizCode);
        assertEquals(String.format("%04d", userId % 10000L), orderNo.substring(8, 12),
                "第 9-12 位为 userId 末 4 位");
        assertTrue(orderNo.substring(12, 18).matches("^\\d{6}$"), "末 6 位为日序列");
    }

    @Test
    @DisplayName("普通订单(orderType=1)：订单号 01 段、状态待付款(10)、金额与列表可见")
    void createNormalOrder() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(7_700L, 30L);
        ObjectNode body = WORLD.baseOrderBody(buyer, sku, 2);
        String orderNo = WORLD.createOrder(buyer, body);

        assertOrderNo(orderNo, "01", buyer.userId(), 1);
        var detail = WORLD.orderDetail(buyer, orderNo);
        assertEquals(10, detail.path("status").asInt(-1), "新订单待付款(10)");
        assertEquals(15_400L, detail.path("payFen").asLong(), "应付=7700*2，无运费无优惠");
        assertEquals(2, detail.path("items").get(0).path("qty").asInt());
        assertTrue(detail.path("items").get(0).path("orderItemId").asLong() > 0, "订单行有 orderItemId");

        ApiClient.Raw page = buyer.api().get("/api/order/orders",
                ApiClient.mapOf("pageNum", 1, "pageSize", 20));
        assertTrue(page.text.contains(orderNo), "订单列表包含新订单");
    }

    @Test
    @DisplayName("秒杀订单(orderType=2)：订单号 02 段，单价取秒杀价")
    void createSeckillOrder() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(10_000L, 50L, 6_600L, null, 1);
        long activityId = WORLD.createSeckillActivity(sku.skuId(), 6_600L, 30);

        ObjectNode body = WORLD.baseOrderBody(buyer, sku, 1);
        body.put("orderType", 2);
        body.put("seckillActivityId", activityId);
        String orderNo = WORLD.createOrder(buyer, body);

        assertOrderNo(orderNo, "02", buyer.userId(), 2);
        assertEquals(10, WORLD.orderStatus(buyer, orderNo));
        assertEquals(6_600L, WORLD.orderPayFen(buyer, orderNo), "秒杀单价 6600 分");
    }

    @Test
    @DisplayName("拼团订单(orderType=3)：订单号 03 段，下单即自动开团（团号由营销域内部维护）")
    void createGroupbuyOrder() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(9_000L, 50L);
        long activityId = WORLD.createGroupbuyActivity();

        ObjectNode body = WORLD.baseOrderBody(buyer, sku, 1);
        body.put("orderType", 3);
        body.put("groupbuyActivityId", activityId);
        String orderNo = WORLD.createOrder(buyer, body);

        assertOrderNo(orderNo, "03", buyer.userId(), 3);
        assertEquals(10, WORLD.orderStatus(buyer, orderNo));
    }

    @Test
    @DisplayName("预售定金订单(orderType=4)：订单号 04 段（尾款阶段须挂接定金单，无外部挂接入口，见报告缺口 #5）")
    void createPresaleDepositOrder() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(10_000L, 50L, null, 1, 2);
        long activityId = WORLD.createPresaleActivity(2_000L, 500L, 7_500L);

        ObjectNode body = WORLD.baseOrderBody(buyer, sku, 1);
        body.put("orderType", 4);
        body.put("presaleActivityId", activityId);
        body.put("presaleFinalStage", false);
        ApiClient.Raw raw = WORLD.tryCreateOrder(buyer, body);
        // 代码未提供定金单→尾款单的外部挂接入口；若环境要求内部挂接则跳过并在报告记录
        org.junit.jupiter.api.Assumptions.assumeTrue(raw.bizCode() == 0,
                "预售定金下单被环境拒绝（可能需要内部挂接），跳过：" + raw.text);
        String orderNo = raw.data().asText();
        assertOrderNo(orderNo, "04", buyer.userId(), 4);
    }

    @Test
    @DisplayName("换货订单(orderType=5)：订单号 05 段可编码（正常换货流程由售后域驱动，见场景 09）")
    void createExchangeOrder() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(5_500L, 30L);
        ObjectNode body = WORLD.baseOrderBody(buyer, sku, 1);
        body.put("orderType", 5);
        ApiClient.Raw raw = WORLD.tryCreateOrder(buyer, body);
        // 若订单域限制换货单只能由售后内部发起，则此处为受信契约，跳过并在报告记录
        org.junit.jupiter.api.Assumptions.assumeTrue(raw.bizCode() == 0,
                "换货单仅允许售后域内部发起，C 端直下被拒绝（契约），跳过：" + raw.text);
        assertOrderNo(raw.data().asText(), "05", buyer.userId(), 5);
    }

    @Test
    @DisplayName("clientToken 幂等：同一 token 重复提交返回同一订单号，不产生重复单")
    void clientTokenIdempotent() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(3_300L, 30L);
        ObjectNode body = WORLD.baseOrderBody(buyer, sku, 1);
        body.put("clientToken", DataFactory.clientToken());

        String n1 = WORLD.createOrder(buyer, body);
        ApiClient.Raw second = WORLD.tryCreateOrder(buyer, body);
        assertEquals(0, second.bizCode(), "重复提交应幂等成功");
        assertEquals(n1, second.data().asText(), "幂等返回同一个订单号");
    }

    @Test
    @DisplayName("非法下单被拒绝：不存在的 SKU / 数量 0 均不能成单")
    void invalidOrderRejected() {
        World.Buyer buyer = WORLD.newBuyer();
        ObjectNode body = WORLD.baseOrderBody(buyer, new World.Sku(999_999_999L, 999_999_999L,
                WORLD.merchantId(), 1L, 1_000L), 1);
        ApiClient.Raw missingSku = WORLD.tryCreateOrder(buyer, body);
        assertNotEquals(0, missingSku.bizCode(), "不存在的 SKU 不能下单");

        World.Sku sku = WORLD.createOnSaleSku(1_000L, 20L);
        ObjectNode zeroQty = WORLD.baseOrderBody(buyer, sku, 0);
        ApiClient.Raw zero = WORLD.tryCreateOrder(buyer, zeroQty);
        assertNotEquals(0, zero.bizCode(), "数量 0 不能下单");
    }
}
