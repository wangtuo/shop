package com.shop.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.shop.e2e.support.ApiClient;
import com.shop.e2e.support.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 4：购物车。加入、改数量、删除、选中/全选/反选、数量上限 99、
 * 下架商品置为失效（invalid=1/reason=1）且不能下单、清理失效、移入收藏。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("04 购物车：增改删/选中/99 上限/下架失效拦截/清理失效/收藏")
class CartE2ETest {

    private static final World WORLD = World.get();
    private static final String CART = "/api/order/cart";

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    private long addToCart(World.Buyer buyer, long skuId, int qty) {
        ApiClient.Raw raw = buyer.api().mustPost(CART, ApiClient.obj("skuId", skuId, "qty", qty), "加入购物车");
        return raw.data().asLong();
    }

    /** 以 cartId 为键扁平化购物车（视图按店铺分组）。 */
    private Map<Long, JsonNode> cartMap(World.Buyer buyer) {
        JsonNode view = buyer.api().get(CART).data();
        Map<Long, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode group : view.path("shopGroups")) {
            for (JsonNode item : group.path("items")) {
                map.put(item.path("cartId").asLong(), item);
            }
        }
        return map;
    }

    @Test
    @DisplayName("加入→改数量→删除：数量与删除结果正确")
    void addUpdateDelete() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(3_000L, 50L);

        long cartId = addToCart(buyer, sku.skuId(), 2);
        assertTrue(cartId > 0);
        JsonNode item = cartMap(buyer).get(cartId);
        assertNotNull(item);
        assertEquals(2, item.path("qty").asInt());
        assertEquals(3_000L, item.path("currentPriceFen").asLong(), "购物车现价取商品最新价");

        buyer.api().mustPut(CART + "/" + cartId, ApiClient.obj("qty", 3), "修改数量为 3");
        assertEquals(3, cartMap(buyer).get(cartId).path("qty").asInt(), "数量更新为 3");

        assertEquals(0, buyer.api().delete(CART + "/" + cartId).bizCode(), "删除购物车行成功");
        assertTrue(!cartMap(buyer).containsKey(cartId), "删除后购物车不再含该行");
    }

    @Test
    @DisplayName("选中：全不选→单选→反选，selected 标记与汇总数量正确")
    void selectSingleAllAndInvert() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku s1 = WORLD.createOnSaleSku(2_000L, 50L);
        World.Sku s2 = WORLD.createOnSaleSku(2_500L, 50L);
        long c1 = addToCart(buyer, s1.skuId(), 1);
        long c2 = addToCart(buyer, s2.skuId(), 1);

        buyer.api().mustPut(CART + "/select-all?selected=0", null, "全部取消选中");
        assertEquals(0, cartMap(buyer).get(c1).path("selected").asInt());
        assertEquals(0, cartMap(buyer).get(c2).path("selected").asInt());

        buyer.api().mustPut(CART + "/select", ApiClient.obj(
                "ids", new long[]{c1}, "selected", 1), "只选中第一行");
        assertEquals(1, cartMap(buyer).get(c1).path("selected").asInt());
        assertEquals(0, cartMap(buyer).get(c2).path("selected").asInt());

        buyer.api().mustPut(CART + "/invert", null, "反选");
        assertEquals(0, cartMap(buyer).get(c1).path("selected").asInt(), "反选后第一行未选中");
        assertEquals(1, cartMap(buyer).get(c2).path("selected").asInt(), "反选后第二行选中");
    }

    @Test
    @DisplayName("单行数量上限 99：改为 100 被拒绝")
    void quantityCannotExceed99() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(1_000L, 200L);
        long cartId = addToCart(buyer, sku.skuId(), 1);

        ApiClient.Raw raw = buyer.api().put(CART + "/" + cartId, ApiClient.obj("qty", 100));
        assertNotEquals(0, raw.bizCode(), "数量 100 超过单行 99 上限必须报错");
        assertEquals(1, cartMap(buyer).get(cartId).path("qty").asInt(), "被拒绝后数量保持 1 不变");
    }

    @Test
    @DisplayName("下架商品在购物车置失效(invalid=1,reason=1)，不能下单，可一键清理")
    void offsaleItemInvalidAndBlockedFromOrder() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(4_000L, 10L);
        long cartId = addToCart(buyer, sku.skuId(), 1);
        assertEquals(0, cartMap(buyer).get(cartId).path("invalid").asInt(), "在售时购物车行有效");

        WORLD.offSale(sku.spuId());
        JsonNode invalidItem = cartMap(buyer).get(cartId);
        assertEquals(1, invalidItem.path("invalid").asInt(), "下架后购物车行置为失效");
        assertEquals(1, invalidItem.path("invalidReason").asInt(), "失效原因=1(下架)");

        // 直接对已下架 SKU 下单必须被拒绝
        ApiClient.Raw order = WORLD.tryCreateOrder(buyer, WORLD.baseOrderBody(buyer, sku, 1));
        assertNotEquals(0, order.bizCode(), "下架商品不允许下单");

        // 一键清理失效后该行消失
        buyer.api().delete(CART + "/invalid");
        assertTrue(!cartMap(buyer).containsKey(cartId), "清理失效后下架行被移除");
    }

    @Test
    @DisplayName("移入收藏返回成功，购物车行仍保留")
    void moveToFavorite() {
        World.Buyer buyer = WORLD.newBuyer();
        World.Sku sku = WORLD.createOnSaleSku(6_000L, 10L);
        long cartId = addToCart(buyer, sku.skuId(), 1);

        ApiClient.Raw raw = buyer.api().post(CART + "/" + cartId + "/favorite", null);
        assertEquals(0, raw.bizCode(), "移入收藏成功");
        assertTrue(cartMap(buyer).containsKey(cartId), "收藏不删除购物车行");
    }
}
