package com.shop.e2e;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.DataFactory;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 11：并发。秒杀库存 N 用 2N 并发请求恰好成交 N 单（Redis 独立库存 + DB 条件更新，不超卖）；
 * 库存为 1 的券 2 人并发抢恰好 1 人成功；同一 clientToken 并发重复提交只产生一单；
 * MQ 重复事件幂等见场景 07（PaySuccessFanoutE2ETest）。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("11 并发：秒杀不超卖/券不超领/clientToken 并发幂等")
class ConcurrencyE2ETest {

    private static final World WORLD = World.get();

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    @Test
    @DisplayName("秒杀库存 10 件、20 人并发抢购：恰好 10 单成功，无超卖，售罄后再来者被拒")
    void seckillNoOversell() throws Exception {
        final int stock = 10;
        final int rivals = stock * 2;

        World.Sku sku = WORLD.createOnSaleSku(20_000L, 50L, 9_900L, null, 1);
        long activityId = WORLD.createSeckillActivity(sku.skuId(), 9_900L, stock);

        // 预注册（每个买家独立账号，规避秒杀每用户去重）
        List<World.Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < rivals + 1; i++) {
            buyers.add(WORLD.newBuyer());
        }

        ExecutorService pool = Executors.newFixedThreadPool(rivals);
        CyclicBarrier barrier = new CyclicBarrier(rivals);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < rivals; i++) {
                World.Buyer buyer = buyers.get(i);
                futures.add(pool.submit(() -> {
                    ObjectNode body = WORLD.baseOrderBody(buyer, sku, 1);
                    body.put("clientToken", DataFactory.clientToken());
                    body.put("orderType", 2);
                    body.put("seckillActivityId", activityId);
                    barrier.await(30, TimeUnit.SECONDS);
                    ApiClient.Raw raw = WORLD.tryCreateOrder(buyer, body);
                    return raw.bizCode() == 0 ? raw.data().asText() : null;
                }));
            }

            Set<String> successOrders = new HashSet<>();
            int success = 0;
            for (Future<String> f : futures) {
                String orderNo = f.get(60, TimeUnit.SECONDS);
                if (orderNo != null) {
                    success++;
                    successOrders.add(orderNo);
                }
            }
            assertEquals(stock, success, "20 人抢 10 件秒杀：恰好 10 单成功，不超卖");
            assertEquals(stock, successOrders.size(), "成功订单号互不重复");

            // 每个成功者用自己的登录态可查到订单，且为秒杀业务码 02（订单按用户隔离，互查不到）
            int verifiable = 0;
            for (World.Buyer b : buyers.subList(0, rivals)) {
                ApiClient.Raw page = b.api().get("/api/order/orders",
                        ApiClient.mapOf("pageNum", 1, "pageSize", 5));
                String text = page.text;
                for (String no : successOrders) {
                    if (text.contains(no) && no.substring(6, 8).equals("02")) {
                        verifiable++;
                        break;
                    }
                }
            }
            assertEquals(stock, verifiable, "10 个成功者都能在自己的订单列表查到秒杀单");

            // 售罄后新买家必须被拒
            ObjectNode more = WORLD.baseOrderBody(buyers.get(rivals), sku, 1);
            more.put("orderType", 2);
            more.put("seckillActivityId", activityId);
            ApiClient.Raw soldOut = WORLD.tryCreateOrder(buyers.get(rivals), more);
            assertNotSuccess(soldOut, "秒杀售罄后新下单必须被拒");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("库存 1 张的券 2 人并发领取：恰好 1 人成功，不超发")
    void couponNoOverClaim() throws Exception {
        long couponId = WORLD.createClaimableCoupon(3_000L, 1, 1);
        World.Buyer b1 = WORLD.newBuyer();
        World.Buyer b2 = WORLD.newBuyer();
        List<Long> results = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<?>> fs = List.of(
                    pool.submit(() -> awaitAndClaim(barrier, b1, couponId, results)),
                    pool.submit(() -> awaitAndClaim(barrier, b2, couponId, results)));
            for (Future<?> f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long success = results.stream().filter(id -> id > 0).count();
        assertEquals(1, success, "两张并发领取只有一张成功");

        // 券已被领完：第三个人再领也失败
        assertEquals(-1L, WORLD.claimCoupon(WORLD.newBuyer(), couponId), "券库存为 0 后领取失败");
    }

    @Test
    @DisplayName("同一 clientToken 并发重复提交：两次响应同一订单号，只生成一单")
    void concurrentDuplicateClientToken() throws Exception {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(4_400L, 20L);
        ObjectNode body = WORLD.baseOrderBody(buyer, sku, 1);
        String token = DataFactory.clientToken();
        body.put("clientToken", token);

        List<ApiClient.Raw> raws = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            // 必须先把两个任务都提交到池中、再统一 get：若在循环内 submit 后立即 get，
            // 第一个任务会一直阻塞在 barrier 等待第二个任务，而主线程又在等第一个任务
            // 结束才提交第二个 —— 自测死锁（barrier 30s 超时）。
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException | BrokenBarrierException |
                             java.util.concurrent.TimeoutException e) {
                        throw new RuntimeException(e);
                    }
                    raws.add(WORLD.tryCreateOrder(buyer, body));
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, raws.size());
        Set<String> nos = new HashSet<>();
        for (ApiClient.Raw r : raws) {
            assertEquals(0, r.bizCode(), "并发重复提交都应幂等成功: " + r.text);
            nos.add(r.data().asText());
        }
        assertEquals(1, nos.size(), "同一 clientToken 只产生一个订单号");
    }

    private void awaitAndClaim(CyclicBarrier barrier, World.Buyer buyer, long couponId, List<Long> sink) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
            sink.add(WORLD.claimCoupon(buyer, couponId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertNotSuccess(ApiClient.Raw raw, String msg) {
        assertTrue(raw.bizCode() != 0, msg + "，实际: " + raw.text);
    }
}
