package com.shop.product.category.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.util.JsonUtils;
import com.shop.product.category.dto.AttrKeySaveRequest;
import com.shop.product.category.entity.ProductAttrKey;
import com.shop.product.category.mapper.ProductAttrKeyMapper;
import com.shop.product.category.service.AttrKeyService;
import com.shop.product.support.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 属性主数据服务实现（B13）。写操作仅平台运营；
 * 枚举/必填/数值类型校验只产出告警集合，兼容存量直通 attrsJson。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttrKeyServiceImpl implements AttrKeyService {

    /** 枚举值类型 */
    private static final int VALUE_TYPE_ENUM = 1;
    /** 数值值类型 */
    private static final int VALUE_TYPE_NUMBER = 2;
    /** 必填标记 */
    private static final int REQUIRED = 1;
    /** 启用 */
    private static final int STATUS_ENABLED = 1;

    private final ProductAttrKeyMapper attrKeyMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AttrKeySaveRequest request) {
        AuthUtils.requirePlatform();
        validateRequest(request);
        ProductAttrKey attrKey = new ProductAttrKey();
        apply(attrKey, request);
        attrKey.setStatus(request.getStatus() == null ? STATUS_ENABLED : request.getStatus());
        attrKeyMapper.insert(attrKey);
        return attrKey.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AttrKeySaveRequest request) {
        AuthUtils.requirePlatform();
        validateRequest(request);
        ProductAttrKey attrKey = requireAttrKey(id);
        apply(attrKey, request);
        if (request.getStatus() != null) {
            attrKey.setStatus(request.getStatus());
        }
        attrKeyMapper.updateById(attrKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AuthUtils.requirePlatform();
        requireAttrKey(id);
        // uk_category_name(category_id,name,deleted)：deleted 置行 id，同名属性删除后可再建
        attrKeyMapper.update(null, new LambdaUpdateWrapper<ProductAttrKey>()
                .eq(ProductAttrKey::getId, id)
                .setSql("deleted = id"));
    }

    @Override
    public List<ProductAttrKey> listByCategory(Long categoryId) {
        if (categoryId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "类目 ID 不能为空");
        }
        return attrKeyMapper.selectEnabledByCategory(categoryId);
    }

    @Override
    public List<String> validateAttrs(Long categoryId, String attrsJson) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> attrs = parseAttrs(attrsJson, warnings);
        List<ProductAttrKey> keys = attrKeyMapper.selectEnabledByCategory(categoryId);
        Map<String, ProductAttrKey> byName = new LinkedHashMap<>();
        for (ProductAttrKey key : keys) {
            // 类目私有优先于全局同名属性
            byName.putIfAbsent(key.getName(), key);
        }
        // 必填缺失
        for (ProductAttrKey key : keys) {
            if (key.getRequiredFlag() != null && key.getRequiredFlag() == REQUIRED
                    && isBlank(attrs.get(key.getName()))) {
                warnings.add("属性[" + key.getName() + "]为主数据必填项，当前缺失，已按快照暂存");
            }
        }
        // 逐值校验 + 未收录告警
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            ProductAttrKey key = byName.get(entry.getKey());
            if (key == null) {
                warnings.add("属性[" + entry.getKey() + "]未收录类目属性主数据，仅按快照保存");
                continue;
            }
            String value = entry.getValue();
            if (isBlank(value)) {
                continue;
            }
            int valueType = key.getValueType() == null ? VALUE_TYPE_ENUM : key.getValueType();
            if (valueType == VALUE_TYPE_ENUM) {
                List<String> options = parseOptions(key.getValueOptions());
                if (!options.isEmpty() && !options.contains(value)) {
                    warnings.add("属性[" + key.getName() + "]取值[" + value
                            + "]不在枚举范围内" + options + "，已按快照暂存");
                }
            } else if (valueType == VALUE_TYPE_NUMBER) {
                try {
                    new BigDecimal(value.trim());
                } catch (NumberFormatException e) {
                    warnings.add("属性[" + key.getName() + "]取值[" + value
                            + "]不是合法数值" + (StringUtils.hasText(key.getUnit())
                            ? "（单位 " + key.getUnit() + "）" : "") + "，已按快照暂存");
                }
            }
        }
        return warnings;
    }

    private void validateRequest(AttrKeySaveRequest request) {
        if (request.getValueType() != null && request.getValueType() == VALUE_TYPE_ENUM
                && (request.getValueOptions() == null || request.getValueOptions().isEmpty())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "枚举属性必须提供可选值列表：" + request.getName());
        }
    }

    private void apply(ProductAttrKey attrKey, AttrKeySaveRequest request) {
        attrKey.setCategoryId(request.getCategoryId());
        attrKey.setName(request.getName().trim());
        attrKey.setAttrType(request.getAttrType());
        attrKey.setValueType(request.getValueType() == null ? VALUE_TYPE_ENUM : request.getValueType());
        attrKey.setValueOptions(request.getValueType() != null && request.getValueType() == VALUE_TYPE_NUMBER
                ? null : JsonUtils.toJson(safeList(request.getValueOptions())));
        attrKey.setUnit(request.getUnit() == null ? "" : request.getUnit().trim());
        attrKey.setRequiredFlag(request.getRequiredFlag() == null ? 0 : request.getRequiredFlag());
        attrKey.setSort(request.getSort() == null ? 0 : request.getSort());
    }

    private ProductAttrKey requireAttrKey(Long id) {
        ProductAttrKey attrKey = attrKeyMapper.selectById(id);
        if (attrKey == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "属性主数据不存在：" + id);
        }
        return attrKey;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseAttrs(String attrsJson, List<String> warnings) {
        if (!StringUtils.hasText(attrsJson)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> raw = JsonUtils.fromJson(attrsJson, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            if (raw != null) {
                raw.forEach((k, v) -> result.put(k, v == null ? null : String.valueOf(v)));
            }
            return result;
        } catch (Exception e) {
            log.warn("attrsJson 解析失败，跳过主数据校验: {}", e.getMessage());
            warnings.add("attrsJson 不是合法 JSON 对象，已原样按快照保存");
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseOptions(String valueOptionsJson) {
        if (!StringUtils.hasText(valueOptionsJson)) {
            return new ArrayList<>();
        }
        try {
            List<String> options = JsonUtils.fromJson(valueOptionsJson, List.class);
            return options == null ? new ArrayList<>() : (List<String>) options;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    private boolean isBlank(String value) {
        return !StringUtils.hasText(value);
    }
}
