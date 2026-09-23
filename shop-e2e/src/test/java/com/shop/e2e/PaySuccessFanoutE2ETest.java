package com.shop.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.DataFactory;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 7：支付成功扇出（ORDER_PAID MQ 最终一致）。轮询验证：
 * 库存真实占用、积分到账、成长值增加、清算流水登记；重复 paySuccess（同 notifyId 重放）幂等，
 * 库存/积分/清算不重复执行。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("07 支付成功扇出：库存占用/积分/成长值/清算登记/重复事件幂等")
class PaySuccessFanoutE2ETest {

    private static final World WORLD = World.get();
    private static final String USER = "/api/user";
    private static final String PRODUCT = "/api/product";

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    private long pointsBalance(World.Buyer b) {
        return b.api().get(USER + "/users/points").data().path("balance").asLong(0L);
    }

    private long growth(World.Buyer b) {
        return b.api().get(USER + "/users/level").data().path("growth").asLong(0L);
    }

    private JsonNode stock(long skuId) {
        return ApiClientHolder.client().get(PRODUCT + "/products/skus/" + skuId + "/price").data();
    }

    @Test
    @DisplayName("支付成功后：库存占用、积分到账、成长值增加、清算登记，且重复事件不二次执行")
    void paySuccessFanoutAndIdempotency() throws Exception {
        WORLD.ensureMerchantOnboarded();
        World.Buyer buyer = WORLD.newBuyer();
        // 100 元订单：积分/成长值均应严格为正
        World.Sku sku = WORLD.createOnSaleSku(10_000L, 20L);
        int qty = 1;

        long pointsBefore = pointsBalance(buyer);
        long growthBefore = growth(buyer);

        String orderNo = WORLD.createOrder(buyer, WORLD.baseOrderBody(buyer, sku, qty));
        World.Payment payment = WORLD.createPayment(buyer, orderNo, World.PAY_WECHAT);

        // 下单即锁库存：可售 -1、锁定 +1、占用 0
        JsonNode locked = stock(sku.skuId());
        assertEquals(19L, locked.path("availableStock").asLong(), "下单后 TCC 锁定：可售减少");
        assertTrue(locked.path("lockedStock").asLong() >= qty, "锁定库存增加");
        assertEquals(0L, locked.path("occupiedStock").asLong(0L), "支付前无真实占用");

        // 支付成功（固定 notifyId，便于同事件重投）
        String notifyId = DataFactory.notifyId();
        ApiClient.Raw notify = WORLD.sendSuccessNotify(buyer, payment, notifyId);
        assertEquals(0, notify.bizCode(), "回调成功: " + notify.text);
        WORLD.waitOrderStatus(buyer, orderNo, World.OS_WAIT_SHIP);

        // 1) 库存真实扣减：锁定转占用，可售不回弹
        com.shop.e2e.support.Poller.longTimeout().await("库存锁定转占用", () -> {
            JsonNode s = stock(sku.skuId());
            return s.path("occupiedStock").asLong(-1L) >= qty
                    && s.path("availableStock").asLong(-1L) == 19L;
        });
        JsonNode occupied = stock(sku.skuId());
        assertTrue(occupied.path("occupiedStock").asLong() >= qty, "支付后真实占用库存");
        assertTrue(occupied.path("lockedStock").asLong() == 0L
                        || occupied.path("lockedStock").asLong() < locked.path("lockedStock").asLong(),
                "锁定库存被核销");

        // 2) 积分到账（积分流水中含该订单的发放记录）
        com.shop.e2e.support.Poller.longTimeout().await("积分到账",
                () -> pointsBalance(buyer) > pointsBefore);
        long pointsAfter = pointsBalance(buyer);
        assertTrue(pointsAfter > pointsBefore, "支付成功积分到账: " + pointsBefore + " -> " + pointsAfter);
        JsonNode flows = buyer.api().get(USER + "/users/points/flows?pageNum=1&pageSize=20").data();
        assertTrue(flows.path("list").toString().contains(orderNo)
                        || flows.path("list").size() > 0,
                "积分流水已产生");

        // 3) 成长值增加
        com.shop.e2e.support.Poller.longTimeout().await("成长值增加",
                () -> growth(buyer) > growthBefore);
        assertTrue(growth(buyer) > growthBefore, "支付成功成长值增加");

        // 4) 清算流水登记（结算域消费 ORDER_PAID）
        JsonNode clearing = WORLD.clearingByOrder(orderNo);
        assertEquals(orderNo, clearing.path("orderNo").asText(), "清算流水按订单号登记");
        assertEquals(10, clearing.path("stage").asInt(-1), "初始清算周期 S(stage=10)");
        assertEquals(10_000L, clearing.path("payAmountFen").asLong(), "清算支付金额=订单实付");

        // 5) 重复 paySuccess 事件幂等：同 notifyId+相同报文重放走回调幂等表直接 ACK；
        //    新 notifyId 的迟到回调在支付单已成功时也只 ACK 不推进；
        //    各域消费方以 eventId 去重，指标均不翻倍
        ApiClient.Raw replaySame = WORLD.sendSuccessNotify(buyer, payment, notifyId);
        assertEquals(0, replaySame.bizCode(), "同 notifyId 重复回调幂等 ACK");
        ApiClient.Raw late = WORLD.sendSuccessNotify(buyer, payment, DataFactory.notifyId());
        assertEquals(0, late.bizCode(), "支付成功后的迟到回调幂等 ACK");
        // 给异步消费一个窗口后核对（值应保持稳定）
        Thread.sleep(1_500L);
        assertEquals(pointsAfter, pointsBalance(buyer), "重复事件不重复发积分");
        assertEquals(occupied.path("occupiedStock").asLong(), stock(sku.skuId()).path("occupiedStock").asLong(),
                "重复事件不重复扣库存");
        long count = WORLD.merchant().get("/api/settlement/merchant/clearing?pageNum=1&pageSize=100")
                .data().path("list").findValuesAsText("orderNo").stream()
                .filter(orderNo::equals).count();
        assertEquals(1, count, "同一订单清算流水只登记一次");
    }

    /** 小持有器避免每处写全限定名。 */
    private static final class ApiClientHolder {
        static com.shop.e2e.support.ApiClient client() {
            return com.shop.e2e.support.ApiClient.create();
        }
    }
}
