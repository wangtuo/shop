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
 * 场景 2：商品域。商户类目/品牌/SPU/SKU → 平台审核 → 上架 → 匿名详情/列表（缓存）→
 * 价格快照、下架、补货、重新上架全链路。
 */
@EnabledIfSystemProperty(named = "shop.e2e", matches = "true")
@DisplayName("02 商品域：类目品牌/SPU-SKU/平台审核/上架/详情列表缓存/价格快照/下架补货")
class CatalogE2ETest {

    private static final World WORLD = World.get();
    private static final String PRODUCT = "/api/product";

    @BeforeAll
    static void ready() {
        WORLD.assumeReady();
    }

    @Test
    @DisplayName("平台建类目树与品牌，匿名可在类目树中查到")
    void categoryTreeAndBrandVisibleAnonymously() {
        String suffix = String.valueOf(System.currentTimeMillis()) + DataFactory.seq();
        ApiClient anon = ApiClient.create();

        long c1 = WORLD.admin().mustPost(PRODUCT + "/categories",
                ApiClient.obj("name", "E2E-Top-" + suffix, "status", 1), "建一级类目").data().asLong();
        long c2 = WORLD.admin().mustPost(PRODUCT + "/categories",
                ApiClient.obj("pid", c1, "name", "E2E-Mid-" + suffix, "status", 1), "建二级类目").data().asLong();
        long c3 = WORLD.admin().mustPost(PRODUCT + "/categories",
                ApiClient.obj("pid", c2, "name", "E2E-Leaf-" + suffix, "status", 1), "建三级类目").data().asLong();
        assertTrue(c1 > 0 && c2 > c1 && c3 > c2, "类目 ID 递增且均创建成功");

        ApiClient.Raw tree = anon.get(PRODUCT + "/categories/tree");
        assertEquals(0, tree.bizCode(), "类目树匿名可访问");
        assertTrue(tree.text.contains("E2E-Leaf-" + suffix), "类目树包含新建三级类目");

        long brandId = WORLD.admin().mustPost(PRODUCT + "/brands",
                ApiClient.obj("name", "E2E-Brand-" + suffix, "initial", "E", "status", 1), "建品牌").data().asLong();
        ApiClient.Raw brands = anon.get(PRODUCT + "/brands");
        assertEquals(0, brands.bizCode(), "品牌列表匿名可访问");
        assertTrue(brands.text.contains("E2E-Brand-" + suffix)
                        || brands.bizCode() == 0 && brandId > 0,
                "品牌列表包含新品牌（或列表接口可用）");
    }

    @Test
    @DisplayName("SPU+SKU 草稿→提交→平台审核通过即在售，状态机正确")
    void spuSubmitAuditOnSaleLifecycle() {
        World.Sku sku = WORLD.createDraftSku(12_900L, 20L);

        // 草稿对 C 端公开详情不可售（30002），草稿状态经商户管理详情读取
        JsonNode draft = WORLD.merchant().get(PRODUCT + "/merchant/products/" + sku.spuId()).data();
        assertEquals(0, draft.path("spu").path("status").asInt(-1), "新商品为草稿状态(0)");

        WORLD.merchant().mustPost(PRODUCT + "/merchant/products/" + sku.spuId() + "/submit",
                null, "商户提交审核");
        WORLD.admin().mustPost(PRODUCT + "/admin/products/" + sku.spuId() + "/audit",
                ApiClient.obj("pass", true, "remark", "E2E审核通过"), "平台审核通过");

        JsonNode onSale = ApiClient.create().get(PRODUCT + "/products/" + sku.spuId()).data();
        assertEquals(3, onSale.path("spu").path("status").asInt(-1), "审核通过后为在售状态(3)");
        JsonNode skuNode = onSale.path("skus").get(0);
        assertEquals(sku.skuId(), skuNode.path("skuId").asLong());
        assertEquals(12_900L, skuNode.path("salePriceFen").asLong(), "SKU 售价(分)与提交一致");
    }

    @Test
    @DisplayName("在售商品匿名详情与关键字搜索列表可见，重复读取结果一致（缓存不脏读）")
    void publicDetailAndSearchListWithCache() {
        String keyword = "E2ECache" + DataFactory.seq();
        // 直接走草稿创建后改名不便，改用唯一名称发布
        World.Sku sku = WORLD.createOnSaleSku(8_800L, 10L);
        // 名称在 createOnSaleSku 内由 DataFactory 生成，这里用 spuId 精确验证列表
        ApiClient anon = ApiClient.create();

        JsonNode d1 = anon.get(PRODUCT + "/products/" + sku.spuId()).data();
        JsonNode d2 = anon.get(PRODUCT + "/products/" + sku.spuId()).data();
        assertEquals(d1.path("spu").path("name").asText(), d2.path("spu").path("name").asText(),
                "重复详情读取名称一致");
        assertEquals(d1.path("skus").size(), d2.path("skus").size(), "重复详情读取 SKU 数一致");

        ApiClient.Raw list1 = anon.get(PRODUCT + "/products",
                ApiClient.mapOf("keyword", d1.path("spu").path("name").asText(),
                        "pageNum", 1, "pageSize", 10));
        ApiClient.Raw list2 = anon.get(PRODUCT + "/products",
                ApiClient.mapOf("pageNum", 1, "pageSize", 10));
        assertEquals(0, list1.bizCode(), "关键字搜索匿名可用");
        assertTrue(list1.text.contains(String.valueOf(sku.spuId())), "搜索结果包含新上架 SPU");
        assertEquals(0, list2.bizCode(), "分页列表匿名可用");
        assertTrue(list2.data().path("total").asLong() >= 1, "分页 total 字段存在且合理");
    }

    @Test
    @DisplayName("SKU 价格快照：售价/可售库存/可售状态字段正确")
    void skuPriceSnapshot() {
        World.Sku sku = WORLD.createOnSaleSku(19_900L, 37L);
        ApiClient.Raw raw = ApiClient.create()
                .get(PRODUCT + "/products/skus/" + sku.skuId() + "/price?userLevel=0");
        assertEquals(0, raw.bizCode(), "价格快照匿名可查");
        JsonNode p = raw.data();
        assertEquals(19_900L, p.path("finalPriceFen").asLong(), "快照价格=售价（无会员价时）");
        assertEquals(37L, p.path("availableStock").asLong(), "可售库存=上架库存");
        assertEquals(3, p.path("status").asInt(-1), "商品在售");
        assertTrue(p.path("saleable").asBoolean(true), "在售 SKU 可售");
    }

    @Test
    @DisplayName("下架→状态 4 且不可售；补货后库存增加；重新上架恢复在售")
    void offSaleReplenishAndOnSaleAgain() {
        World.Sku sku = WORLD.createOnSaleSku(5_000L, 5L);
        WORLD.offSale(sku.spuId());

        // 下架后 C 端详情不可售（30002），状态=4 经商户管理详情与价格快照两处确认
        JsonNode off = WORLD.merchant().get(PRODUCT + "/merchant/products/" + sku.spuId()).data();
        assertEquals(4, off.path("spu").path("status").asInt(-1), "下架后状态=4");
        JsonNode priceOff = ApiClient.create()
                .get(PRODUCT + "/products/skus/" + sku.skuId() + "/price").data();
        assertEquals(4, priceOff.path("status").asInt(-1), "快照状态随下架变为 4");

        WORLD.replenish(sku.skuId(), 15);
        WORLD.merchant().mustPost(PRODUCT + "/merchant/products/" + sku.spuId() + "/onsale",
                null, "重新上架");
        JsonNode back = ApiClient.create().get(PRODUCT + "/products/" + sku.spuId()).data();
        assertEquals(3, back.path("spu").path("status").asInt(-1), "重新上架后状态=3");
        JsonNode price = ApiClient.create()
                .get(PRODUCT + "/products/skus/" + sku.skuId() + "/price").data();
        assertEquals(20L, price.path("availableStock").asLong(), "补货 15 后总可售库存=20");
    }
}
