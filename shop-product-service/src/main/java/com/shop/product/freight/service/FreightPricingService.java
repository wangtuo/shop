package com.shop.product.freight.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.api.product.dto.FreightCalcRequest;
import com.shop.api.product.dto.FreightCalcResponse;
import com.shop.api.user.client.UserClient;
import com.shop.api.user.dto.AddressDTO;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.product.freight.entity.FreightRegion;
import com.shop.product.freight.entity.FreightTemplate;
import com.shop.product.freight.mapper.FreightRegionMapper;
import com.shop.product.freight.mapper.FreightTemplateMapper;
import com.shop.product.goods.entity.ProductSku;
import com.shop.product.goods.mapper.ProductSkuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 运费服务端定价器（B7 / TRADE C32）：服务端取价是运费唯一权威口径。
 *
 * <p>决策顺序：
 * <ol>
 *   <li>按 item.skuId 加载 SKU，全部 SKU 必须属于同一商家（本契约不支持跨店合并试算）；</li>
 *   <li>取店铺启用中的默认模板（is_default=1, status=1）；无模板/默认停用 → 0 运费，
 *       快照 source=DEFAULT_NONE（运营须尽快配置，发布说明声明）；</li>
 *   <li>区域规则命中（区 &gt; 市 &gt; 省，最具体优先）优先于模板默认费率；
 *       deliverable=0 命中 → 业务异常拒单；</li>
 *   <li>按 chargeType 1件/2重量(g)/3体积(cm³)汇总 totalUnit，套用
 *       {@code firstFee + ceil(max(0,totalUnit-firstUnit)/addUnit)*addFee}；</li>
 *   <li>满额包邮：freeConditionFen&gt;0 且内部重载传入 goodsAmountFen &gt;= 门槛 → 0 运费。</li>
 * </ol>
 *
 * <p>shop-api {@link FreightCalcRequest} 无 goodsAmountFen 字段，故 inner 端点入参
 * 不含商品金额（满额判定在营销/订单编排侧通过内部重载发起）；不改 shop-api。
 */
@Service
@RequiredArgsConstructor
public class FreightPricingService {

    /** 快照序列化器：仅 Map/Number/String/Boolean/null，默认配置即确定性输出。 */
    static final ObjectMapper JSON = new ObjectMapper();

    private static final int CHARGE_BY_PIECE = 1;
    private static final int CHARGE_BY_WEIGHT = 2;
    private static final int CHARGE_BY_VOLUME = 3;

    private final FreightTemplateMapper templateMapper;
    private final FreightRegionMapper regionMapper;
    private final ProductSkuMapper skuMapper;
    /** addressId 反查依赖 user 域；ObjectProvider 保持缺 bean 场景可启动 */
    private final ObjectProvider<UserClient> userClientProvider;

    /** inner 端点入口：无商品金额，不参与满额包邮判定。 */
    public FreightCalcResponse calc(FreightCalcRequest request) {
        return calc(request, null);
    }

    /**
     * 内部定价重载：由营销/订单编排侧携带订单商品金额（分）发起，支持满额包邮。
     */
    public FreightCalcResponse calc(FreightCalcRequest request, Long goodsAmountFen) {
        if (goodsAmountFen != null && goodsAmountFen < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "商品金额不能为负");
        }
        List<FreightCalcRequest.FreightItem> items = request.getItems();
        List<Long> skuIds = items.stream().map(FreightCalcRequest.FreightItem::getSkuId).toList();
        List<ProductSku> skus = skuMapper.selectBatchIds(skuIds);
        if (skus.size() != skuIds.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "部分 SKU 不存在，无法试算运费");
        }
        Long merchantId = skus.get(0).getMerchantId();
        for (ProductSku sku : skus) {
            if (!Objects.equals(sku.getMerchantId(), merchantId)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "不支持跨店铺商品合并试算运费");
            }
        }

        String[] address = resolveAddress(request);
        String province = address[0];
        String city = address[1];
        String district = address[2];

        FreightTemplate template = templateMapper.selectOne(new LambdaQueryWrapper<FreightTemplate>()
                .eq(FreightTemplate::getMerchantId, merchantId)
                .eq(FreightTemplate::getIsDefault, 1)
                .eq(FreightTemplate::getStatus, 1)
                .last("LIMIT 1"));
        if (template == null) {
            return buildDefaultNone(merchantId, request.getOrderNo());
        }

        List<FreightRegion> regions = regionMapper.selectList(new LambdaQueryWrapper<FreightRegion>()
                .eq(FreightRegion::getTemplateId, template.getId()));
        FreightRegion matched = matchRegion(regions, province, city, district);
        if (matched != null && Integer.valueOf(0).equals(matched.getDeliverable())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "收货区域不在配送范围");
        }

        long totalUnit = totalUnit(template.getChargeType(), items, skus);
        int firstUnit;
        long firstFeeFen;
        int addUnit;
        long addFeeFen;
        if (matched != null) {
            firstUnit = matched.getFirstUnit();
            firstFeeFen = matched.getFirstFeeFen();
            addUnit = matched.getAddUnit();
            addFeeFen = matched.getAddFeeFen();
        } else {
            firstUnit = template.getDefaultFirst();
            firstFeeFen = template.getDefaultFirstFee();
            addUnit = template.getDefaultAdd();
            addFeeFen = template.getDefaultAddFee();
        }
        long addSteps = continuationSteps(totalUnit, firstUnit, addUnit);
        long freightFen = chargeFee(totalUnit, firstUnit, firstFeeFen, addUnit, addFeeFen);

        boolean freeShipping = false;
        Long threshold = template.getFreeConditionFen();
        if (threshold != null && threshold > 0 && goodsAmountFen != null && goodsAmountFen >= threshold) {
            freeShipping = true;
            freightFen = 0L;
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("source", "TEMPLATE");
        snapshot.put("merchantId", merchantId);
        snapshot.put("orderNo", request.getOrderNo());
        snapshot.put("templateId", template.getId());
        snapshot.put("templateName", template.getName());
        snapshot.put("chargeType", template.getChargeType());
        snapshot.put("regionMatched", matched != null);
        snapshot.put("regionRuleId", matched == null ? null : matched.getId());
        snapshot.put("deliverable", 1);
        snapshot.put("totalUnit", totalUnit);
        snapshot.put("firstUnit", firstUnit);
        snapshot.put("firstFeeFen", firstFeeFen);
        snapshot.put("addUnit", addUnit);
        snapshot.put("addFeeFen", addFeeFen);
        snapshot.put("addSteps", addSteps);
        snapshot.put("freeConditionFen", threshold);
        snapshot.put("goodsAmountFen", goodsAmountFen);
        snapshot.put("freeShipping", freeShipping);
        snapshot.put("freightFen", freightFen);
        return FreightCalcResponse.builder()
                .freightFen(freightFen)
                .snapshotJson(toJson(snapshot))
                .build();
    }

    /**
     * 纯函数：阶梯运费 = 首费 + ceil(max(0, totalUnit-firstUnit)/addUnit) * 续费。
     * 守卫：totalUnit/firstUnit 负、费用负、addUnit&lt;=0 一律按参数错误拒绝（防除零/脏配置）。
     */
    static long chargeFee(long totalUnit, long firstUnit, long firstFeeFen, long addUnit, long addFeeFen) {
        if (totalUnit < 0 || firstUnit < 0 || firstFeeFen < 0 || addFeeFen < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "运费规则配置非法：单位数/费用不能为负");
        }
        if (addUnit <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "运费规则配置非法：续件单位数必须大于 0");
        }
        long steps = continuationSteps(totalUnit, firstUnit, addUnit);
        return firstFeeFen + steps * addFeeFen;
    }

    /** 续费件数 ceil(max(0,totalUnit-firstUnit)/addUnit)。 */
    static long continuationSteps(long totalUnit, long firstUnit, long addUnit) {
        if (addUnit <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "运费规则配置非法：续件单位数必须大于 0");
        }
        long over = Math.max(0L, totalUnit - firstUnit);
        return (over + addUnit - 1) / addUnit;
    }

    /**
     * 纯函数：区域规则命中，区 &gt; 市 &gt; 省最具体优先；同具体度取 id 较小者保证确定性。
     */
    static FreightRegion matchRegion(List<FreightRegion> regions, String province, String city, String district) {
        FreightRegion best = null;
        int bestSpecificity = 0;
        for (FreightRegion r : regions) {
            List<String> codes = parseRegionCodes(r.getRegionCodes());
            int specificity = 0;
            if (district != null && !district.isBlank() && codes.contains(district)) {
                specificity = 3;
            } else if (city != null && !city.isBlank() && codes.contains(city)) {
                specificity = 2;
            } else if (province != null && !province.isBlank() && codes.contains(province)) {
                specificity = 1;
            }
            if (specificity > bestSpecificity
                    || (specificity > 0 && specificity == bestSpecificity && best != null && r.getId() < best.getId())) {
                best = r;
                bestSpecificity = specificity;
            }
        }
        return best;
    }

    private long totalUnit(int chargeType, List<FreightCalcRequest.FreightItem> items, List<ProductSku> skus) {
        Map<Long, ProductSku> skuMap = new LinkedHashMap<>();
        for (ProductSku sku : skus) {
            skuMap.put(sku.getId(), sku);
        }
        long total = 0L;
        for (FreightCalcRequest.FreightItem item : items) {
            ProductSku sku = skuMap.get(item.getSkuId());
            long qty = item.getQty();
            if (chargeType == CHARGE_BY_PIECE) {
                total += qty;
            } else if (chargeType == CHARGE_BY_WEIGHT) {
                Integer w = sku.getWeightGram();
                if (w == null || w < 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "SKU 重量信息缺失，无法按重量计运费");
                }
                total += qty * w;
            } else if (chargeType == CHARGE_BY_VOLUME) {
                Integer v = sku.getVolumeCc();
                if (v == null || v < 0) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "SKU 体积信息缺失，无法按体积计运费");
                }
                total += qty * v;
            } else {
                throw new BizException(ErrorCode.PARAM_INVALID, "运费模板计费方式非法");
            }
        }
        return total;
    }

    private String[] resolveAddress(FreightCalcRequest request) {
        String province = request.getProvince();
        String city = request.getCity();
        String district = request.getDistrict();
        if (province != null && !province.isBlank()) {
            return new String[]{province.trim(), trimToNull(city), trimToNull(district)};
        }
        if (request.getAddressId() != null) {
            UserClient userClient = userClientProvider.getIfAvailable();
            if (userClient == null) {
                throw new BizException(ErrorCode.DEPENDENCY_FAIL, "地址服务不可用");
            }
            Result<AddressDTO> result = userClient.getAddress(request.getAddressId());
            AddressDTO address = result == null ? null : result.getData();
            if (address == null || address.getProvince() == null || address.getProvince().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "收货地址不存在或不完整");
            }
            return new String[]{address.getProvince().trim(), trimToNull(address.getCity()),
                    trimToNull(address.getDistrict())};
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "收货地址不能为空：请上送省市区编码或 addressId");
    }

    private FreightCalcResponse buildDefaultNone(Long merchantId, String orderNo) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("source", "DEFAULT_NONE");
        snapshot.put("merchantId", merchantId);
        snapshot.put("orderNo", orderNo);
        snapshot.put("reason", "NO_ENABLED_DEFAULT_TEMPLATE");
        snapshot.put("freightFen", 0L);
        return FreightCalcResponse.builder().freightFen(0L).snapshotJson(toJson(snapshot)).build();
    }

    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static List<String> parseRegionCodes(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "区域规则编码数据损坏");
        }
    }

    private static String toJson(Map<String, Object> snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "运费快照序列化失败");
        }
    }
}
