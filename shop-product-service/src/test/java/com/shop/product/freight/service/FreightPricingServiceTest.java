package com.shop.product.freight.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.api.product.dto.FreightCalcRequest;
import com.shop.api.product.dto.FreightCalcResponse;
import com.shop.api.user.client.UserClient;
import com.shop.common.exception.BizException;
import com.shop.product.freight.entity.FreightRegion;
import com.shop.product.freight.entity.FreightTemplate;
import com.shop.product.freight.mapper.FreightRegionMapper;
import com.shop.product.freight.mapper.FreightTemplateMapper;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.mapper.ProductSkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * B7 定价器单测：区域命中/未命中、三种 charge_type 首件/恰好/零续件/除零、
 * 满额包邮临界、不可配送拒单、无模板/停用默认决策、取价快照钉死与并发改模板一致性。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightPricingServiceTest {

    private static final ObjectMapper CHECK = new ObjectMapper();

    @Mock
    private FreightTemplateMapper templateMapper;
    @Mock
    private FreightRegionMapper regionMapper;
    @Mock
    private ProductSkuMapper skuMapper;
    @Mock
    private ObjectProvider<UserClient> userClientProvider;

    private FreightPricingService pricing;

    @BeforeEach
    void setUp() {
        pricing = new FreightPricingService(templateMapper, regionMapper, skuMapper, userClientProvider);
    }

    // ---------- 纯函数：阶梯计费 ----------

    @Test
    void chargeFee_pieceBoundaries() {
        // 恰好首件（totalUnit == firstUnit）→ 零续件，只收首费
        assertEquals(800L, FreightPricingService.chargeFee(1, 1, 800, 1, 200));
        assertEquals(800L, FreightPricingService.chargeFee(2, 2, 800, 1, 200));
        // 续 1 件 / 续 2 件
        assertEquals(1000L, FreightPricingService.chargeFee(2, 1, 800, 1, 200));
        assertEquals(1200L, FreightPricingService.chargeFee(3, 1, 800, 1, 200));
        // addUnit=2：超出 1 与超出 2 都算 1 个续件单位，超出 3 算 2 个（ceil）
        assertEquals(1100L, FreightPricingService.chargeFee(3, 1, 800, 2, 300));
        assertEquals(1100L, FreightPricingService.chargeFee(3, 1, 800, 2, 300));
        assertEquals(1400L, FreightPricingService.chargeFee(4, 1, 800, 2, 300));
        // totalUnit < firstUnit：max(0,..) 守卫，不出现负续件
        assertEquals(800L, FreightPricingService.chargeFee(0, 1, 800, 2, 300));
    }

    @Test
    void chargeFee_guardsDivideByZeroAndNegative() {
        BizException e1 = assertThrows(BizException.class,
                () -> FreightPricingService.chargeFee(10, 1, 800, 0, 200));
        BizException e2 = assertThrows(BizException.class,
                () -> FreightPricingService.chargeFee(10, 1, -1, 1, 200));
        BizException e3 = assertThrows(BizException.class,
                () -> FreightPricingService.continuationSteps(5, 1, -2));
        for (BizException e : List.of(e1, e2, e3)) {
            assertEquals(com.shop.common.exception.ErrorCode.PARAM_INVALID.getCode(), e.getCode());
        }
    }

    @Test
    void matchRegion_districtBeatsCityAndProvince() {
        FreightRegion province = region(1L, List.of("330000"), 1, 100, 1, 10, 1);
        FreightRegion city = region(2L, List.of("330100"), 1, 200, 1, 20, 1);
        FreightRegion district = region(3L, List.of("330106"), 1, 300, 1, 30, 1);
        List<FreightRegion> all = List.of(province, city, district);

        assertEquals(3L, FreightPricingService.matchRegion(all, "330000", "330100", "330106").getId());
        assertEquals(2L, FreightPricingService.matchRegion(all, "330000", "330100", "339999").getId());
        assertEquals(1L, FreightPricingService.matchRegion(all, "330000", "339999", null).getId());
        assertNull(FreightPricingService.matchRegion(all, "110000", null, null));
    }

    // ---------- 端到端取价（mock mappers） ----------

    @Test
    void calc_pieceNoRegionMatch_usesTemplateDefaultFee() throws Exception {
        prepareSkus(List.of(sku(1L, 10L, 1, null, null)));
        prepareTemplate(defaultTemplate(1));
        when(regionMapper.selectList(any())).thenReturn(List.of());

        FreightCalcResponse resp = pricing.calc(request(List.of(item(1L, 1)), "330000", "330100", "330106"));
        assertEquals(800L, resp.getFreightFen());

        JsonNode snap = CHECK.readTree(resp.getSnapshotJson());
        // 快照内容钉死（字段值）
        assertEquals("TEMPLATE", snap.get("source").asText());
        assertEquals(10L, snap.get("merchantId").asLong());
        assertEquals(100L, snap.get("templateId").asLong());
        assertEquals("默认模板", snap.get("templateName").asText());
        assertEquals(1, snap.get("chargeType").asInt());
        assertFalse(snap.get("regionMatched").asBoolean());
        assertTrue(snap.get("regionRuleId").isNull());
        assertEquals(1L, snap.get("totalUnit").asLong());
        assertEquals(1, snap.get("firstUnit").asInt());
        assertEquals(800L, snap.get("firstFeeFen").asLong());
        assertEquals(1, snap.get("addUnit").asInt());
        assertEquals(200L, snap.get("addFeeFen").asLong());
        assertEquals(0L, snap.get("addSteps").asLong());
        assertEquals(0L, snap.get("freeConditionFen").asLong());
        assertTrue(snap.get("goodsAmountFen").isNull());
        assertFalse(snap.get("freeShipping").asBoolean());
        assertEquals(800L, snap.get("freightFen").asLong());
        assertKeyOrder(resp.getSnapshotJson());
    }

    @Test
    void calc_pieceMultiQuantity_continuationFee() {
        prepareSkus(List.of(sku(1L, 10L, 3, null, null)));
        prepareTemplate(defaultTemplate(1));
        when(regionMapper.selectList(any())).thenReturn(List.of());

        assertEquals(1200L, pricing.calc(request(List.of(item(1L, 3)), "330000", null, null)).getFreightFen());
    }

    @Test
    void calc_regionMatch_overridesDefaultFee() {
        prepareSkus(List.of(sku(1L, 10L, 4, null, null)));
        prepareTemplate(defaultTemplate(1));
        when(regionMapper.selectList(any())).thenReturn(List.of(
                region(50L, List.of("330106"), 1, 1000, 2, 300, 1)));

        FreightCalcResponse resp = pricing.calc(request(List.of(item(1L, 4)), "330000", "330100", "330106"));
        // 首 1 件 1000，超出 3 件按 addUnit=2 ceil=2 步 → 1000 + 2*300 = 1600
        assertEquals(1600L, resp.getFreightFen());
        assertTrue(resp.getSnapshotJson().contains("\"regionMatched\":true"));
        assertTrue(resp.getSnapshotJson().contains("\"regionRuleId\":50"));
        assertTrue(resp.getSnapshotJson().contains("\"addSteps\":2"));
    }

    @Test
    void calc_weightCharge_usesSkuWeightGram() {
        prepareSkus(List.of(sku(1L, 10L, 2, 500, null)));
        FreightTemplate t = defaultTemplate(2);
        t.setDefaultFirst(1000);
        t.setDefaultFirstFee(1000L);
        t.setDefaultAdd(500);
        t.setDefaultAddFee(200L);
        prepareTemplate(t);
        when(regionMapper.selectList(any())).thenReturn(List.of());

        // 2 件 * 500g = 1000g，恰好首重 → 零续重
        assertEquals(1000L, pricing.calc(request(List.of(item(1L, 2)), "330000", null, null)).getFreightFen());
        // 3 件 = 1500g，续 500g 一步
        prepareSkus(List.of(sku(1L, 10L, 3, 500, null)));
        assertEquals(1200L, pricing.calc(request(List.of(item(1L, 3)), "330000", null, null)).getFreightFen());
    }

    @Test
    void calc_volumeCharge_usesSkuVolumeCc() {
        prepareSkus(List.of(sku(1L, 10L, 2, null, 1000)));
        FreightTemplate t = defaultTemplate(3);
        t.setDefaultFirst(1000);
        t.setDefaultFirstFee(500L);
        t.setDefaultAdd(1000);
        t.setDefaultAddFee(100L);
        prepareTemplate(t);
        when(regionMapper.selectList(any())).thenReturn(List.of());

        // 2 * 1000 = 2000 cm³，首 1000，续 1000 一步 → 500 + 100 = 600
        assertEquals(600L, pricing.calc(request(List.of(item(1L, 2)), "330000", null, null)).getFreightFen());
    }

    @Test
    void calc_weightMissing_throwsParamInvalid() {
        prepareSkus(List.of(sku(1L, 10L, 1, null, null)));
        prepareTemplate(defaultTemplate(2));
        when(regionMapper.selectList(any())).thenReturn(List.of());
        BizException e = assertThrows(BizException.class,
                () -> pricing.calc(request(List.of(item(1L, 1)), "330000", null, null)));
        assertEquals(com.shop.common.exception.ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    void calc_dirtyAddUnitZero_throws() {
        prepareSkus(List.of(sku(1L, 10L, 4, null, null)));
        prepareTemplate(defaultTemplate(1));
        // 脏数据：区域规则 addUnit=0（DTO 入参已拦，防库内脏配置）
        when(regionMapper.selectList(any())).thenReturn(List.of(
                region(51L, List.of("330106"), 1, 1000, 0, 300, 1)));
        BizException e = assertThrows(BizException.class,
                () -> pricing.calc(request(List.of(item(1L, 4)), "330000", "330100", "330106")));
        assertEquals(com.shop.common.exception.ErrorCode.PARAM_INVALID.getCode(), e.getCode());
    }

    @Test
    void calc_freeShippingBoundary() {
        prepareSkus(List.of(sku(1L, 10L, 3, null, null)));
        FreightTemplate t = defaultTemplate(1);
        t.setFreeConditionFen(9900L);
        prepareTemplate(t);
        when(regionMapper.selectList(any())).thenReturn(List.of());

        FreightCalcRequest req = request(List.of(item(1L, 3)), "330000", null, null);
        // 临界：恰好等于门槛 → 包邮
        FreightCalcResponse at = pricing.calc(req, 9900L);
        assertEquals(0L, at.getFreightFen());
        assertTrue(at.getSnapshotJson().contains("\"freeShipping\":true"));
        // 差 1 分 → 不包邮
        FreightCalcResponse below = pricing.calc(req, 9899L);
        assertEquals(1200L, below.getFreightFen());
        assertTrue(below.getSnapshotJson().contains("\"freeShipping\":false"));
        // 不带金额（inner 端点）→ 不判包邮
        FreightCalcResponse noAmount = pricing.calc(req);
        assertEquals(1200L, noAmount.getFreightFen());
    }

    @Test
    void calc_undeliverableRegion_rejects() {
        prepareSkus(List.of(sku(1L, 10L, 1, null, null)));
        prepareTemplate(defaultTemplate(1));
        when(regionMapper.selectList(any())).thenReturn(List.of(
                region(60L, List.of("710000"), 1, 0, 1, 0, 0)));
        BizException e = assertThrows(BizException.class,
                () -> pricing.calc(request(List.of(item(1L, 1)), "710000", null, null)));
        assertEquals(com.shop.common.exception.ErrorCode.PARAM_INVALID.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("配送范围"));
    }

    @Test
    void calc_noTemplate_defaultsToZeroWithSnapshotMark() throws Exception {
        prepareSkus(List.of(sku(1L, 10L, 1, null, null)));
        when(templateMapper.selectOne(any())).thenReturn(null);

        FreightCalcResponse resp = pricing.calc(request(List.of(item(1L, 5)), "330000", null, null));
        assertEquals(0L, resp.getFreightFen());
        JsonNode snap = CHECK.readTree(resp.getSnapshotJson());
        assertEquals("DEFAULT_NONE", snap.get("source").asText());
        assertEquals(10L, snap.get("merchantId").asLong());
        assertEquals("NO_ENABLED_DEFAULT_TEMPLATE", snap.get("reason").asText());
        assertEquals(0L, snap.get("freightFen").asLong());
    }

    @Test
    void calc_snapshotStableUnderConcurrentTemplateMutation() {
        prepareSkus(List.of(sku(1L, 10L, 2, null, null)));
        prepareTemplate(defaultTemplate(1));
        when(regionMapper.selectList(any())).thenReturn(List.of());
        FreightCalcRequest req = request(List.of(item(1L, 2)), "330000", null, null);

        // 第一次取价：800 + 1*200 = 1000，快照固化
        FreightCalcResponse first = pricing.calc(req);
        String firstSnapshot = first.getSnapshotJson();
        assertEquals(1000L, first.getFreightFen());

        // 模板随后被商家并发修改（首费翻倍）
        FreightTemplate changed = defaultTemplate(1);
        changed.setDefaultFirstFee(1600L);
        prepareTemplate(changed);
        FreightCalcResponse second = pricing.calc(req);
        assertEquals(1800L, second.getFreightFen());

        // 旧响应持有的快照字符串不可变，仍钉死在旧规则上（随订单价格快照落库可追溯）
        assertTrue(firstSnapshot.contains("\"firstFeeFen\":800"));
        assertTrue(firstSnapshot.contains("\"freightFen\":1000"));
        assertFalse(firstSnapshot.equals(second.getSnapshotJson()));
    }

    @Test
    void calc_missingSkuAndCrossMerchantAndNoAddress_rejected() {
        FreightCalcRequest noAddr = FreightCalcRequest.builder()
                .items(List.of(item(1L, 1))).build();
        prepareSkus(List.of(sku(1L, 10L, 1, null, null)));
        prepareTemplate(defaultTemplate(1));
        assertThrows(BizException.class, () -> pricing.calc(noAddr));

        // SKU 不存在
        when(skuMapper.selectBatchIds(any())).thenReturn(List.of());
        assertThrows(BizException.class,
                () -> pricing.calc(request(List.of(item(99L, 1)), "330000", null, null)));

        // 跨店铺合并试算
        prepareSkus(List.of(sku(1L, 10L, 1, null, null), sku(2L, 20L, 1, null, null)));
        BizException cross = assertThrows(BizException.class,
                () -> pricing.calc(request(List.of(item(1L, 1), item(2L, 1)), "330000", null, null)));
        assertEquals(com.shop.common.exception.ErrorCode.PARAM_INVALID.getCode(), cross.getCode());
    }

    // ---------- helpers ----------

    private void prepareSkus(List<ProductSku> skus) {
        when(skuMapper.selectBatchIds(any())).thenReturn(skus);
    }

    private void prepareTemplate(FreightTemplate t) {
        when(templateMapper.selectOne(any())).thenReturn(t);
    }

    private FreightTemplate defaultTemplate(int chargeType) {
        FreightTemplate t = new FreightTemplate();
        t.setId(100L);
        t.setMerchantId(10L);
        t.setName("默认模板");
        t.setChargeType(chargeType);
        t.setDefaultFirst(1);
        t.setDefaultFirstFee(800L);
        t.setDefaultAdd(1);
        t.setDefaultAddFee(200L);
        t.setFreeConditionFen(0L);
        t.setIsDefault(1);
        t.setStatus(1);
        return t;
    }

    private FreightRegion region(Long id, List<String> codes, int firstUnit, long firstFee,
                                 int addUnit, long addFee, int deliverable) {
        FreightRegion r = new FreightRegion();
        r.setId(id);
        r.setTemplateId(100L);
        r.setMerchantId(10L);
        try {
            r.setRegionCodes(FreightPricingService.JSON.writeValueAsString(codes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        r.setFirstUnit(firstUnit);
        r.setFirstFeeFen(firstFee);
        r.setAddUnit(addUnit);
        r.setAddFeeFen(addFee);
        r.setDeliverable(deliverable);
        return r;
    }

    private ProductSku sku(Long id, Long merchantId, int qtyUnused, Integer weightGram, Integer volumeCc) {
        ProductSku s = new ProductSku();
        s.setId(id);
        s.setMerchantId(merchantId);
        s.setWeightGram(weightGram);
        s.setVolumeCc(volumeCc);
        return s;
    }

    private FreightCalcRequest.FreightItem item(long skuId, int qty) {
        return FreightCalcRequest.FreightItem.builder().skuId(skuId).qty(qty).build();
    }

    private FreightCalcRequest request(List<FreightCalcRequest.FreightItem> items,
                                       String province, String city, String district) {
        return FreightCalcRequest.builder().items(new ArrayList<>(items))
                .province(province).city(city).district(district).build();
    }

    /** 快照必须是固定键序（解析为 JSON 对象字段名出现顺序）。 */
    private void assertKeyOrder(String json) {
        Matcher m = Pattern.compile("\"([a-zA-Z]+)\":").matcher(json);
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        assertEquals(List.of("source", "merchantId", "orderNo", "templateId", "templateName",
                "chargeType", "regionMatched", "regionRuleId", "deliverable", "totalUnit",
                "firstUnit", "firstFeeFen", "addUnit", "addFeeFen", "addSteps",
                "freeConditionFen", "goodsAmountFen", "freeShipping", "freightFen"), keys);
    }
}
