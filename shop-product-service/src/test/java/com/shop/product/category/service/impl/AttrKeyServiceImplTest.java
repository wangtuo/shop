package com.shop.product.category.service.impl;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.category.dto.AttrKeySaveRequest;
import com.shop.product.category.entity.ProductAttrKey;
import com.shop.product.category.mapper.ProductAttrKeyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B13：属性主数据校验（必填/枚举/数值/未收录仅告警，不硬拒）与平台写鉴权。
 */
@ExtendWith(MockitoExtension.class)
class AttrKeyServiceImplTest {

    @Mock
    private ProductAttrKeyMapper attrKeyMapper;

    private AttrKeyServiceImpl attrKeyService;

    private static final long CATEGORY = 300L;

    @BeforeEach
    void setUp() {
        attrKeyService = new AttrKeyServiceImpl(attrKeyMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void loginPlatform() {
        UserContext.set(LoginUser.builder().userId(1L).userType(2).build());
    }

    private void loginMerchant() {
        UserContext.set(LoginUser.builder().userId(99L).userType(1).merchantId(7L).build());
    }

    private ProductAttrKey key(String name, int valueType, String options, String unit, int required) {
        ProductAttrKey k = new ProductAttrKey();
        k.setCategoryId(CATEGORY);
        k.setName(name);
        k.setAttrType(3);
        k.setValueType(valueType);
        k.setValueOptions(options);
        k.setUnit(unit);
        k.setRequiredFlag(required);
        k.setStatus(1);
        return k;
    }

    @Test
    void 必填属性缺失_仅告警不抛异常() {
        when(attrKeyMapper.selectEnabledByCategory(CATEGORY)).thenReturn(List.of(
                key("材质", 1, "[\"棉\",\"涤纶\"]", "", 1)));

        List<String> warnings = attrKeyService.validateAttrs(CATEGORY, "{\"颜色\":\"红\"}");

        assertTrue(warnings.stream().anyMatch(w -> w.contains("材质") && w.contains("必填")));
        // 未收录属性同时告警，但 attrsJson 仍按快照保留（不抛异常即兼容）
        assertTrue(warnings.stream().anyMatch(w -> w.contains("颜色") && w.contains("未收录")));
    }

    @Test
    void 枚举取值越界_告警且枚举合法时无告警() {
        when(attrKeyMapper.selectEnabledByCategory(CATEGORY)).thenReturn(List.of(
                key("颜色", 1, "[\"红\",\"蓝\"]", "", 0)));

        List<String> bad = attrKeyService.validateAttrs(CATEGORY, "{\"颜色\":\"绿\"}");
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).contains("枚举范围"));

        List<String> ok = attrKeyService.validateAttrs(CATEGORY, "{\"颜色\":\"红\"}");
        assertTrue(ok.isEmpty());
    }

    @Test
    void 数值类型非法_带单位告警_合法数值通过() {
        when(attrKeyMapper.selectEnabledByCategory(CATEGORY)).thenReturn(List.of(
                key("重量", 2, null, "g", 0)));

        List<String> bad = attrKeyService.validateAttrs(CATEGORY, "{\"重量\":\"abc\"}");
        assertEquals(1, bad.size());
        assertTrue(bad.get(0).contains("不是合法数值"));
        assertTrue(bad.get(0).contains("g"));

        List<String> ok = attrKeyService.validateAttrs(CATEGORY, "{\"重量\":\"12.5\"}");
        assertTrue(ok.isEmpty());
    }

    @Test
    void 未收录属性与非法JSON_告警但不拒绝保存() {
        when(attrKeyMapper.selectEnabledByCategory(CATEGORY)).thenReturn(List.of());

        List<String> unknown = attrKeyService.validateAttrs(CATEGORY, "{\"自研字段\":\"x\"}");
        assertTrue(unknown.stream().anyMatch(w -> w.contains("自研字段") && w.contains("未收录")));

        List<String> broken = attrKeyService.validateAttrs(CATEGORY, "not-json{");
        assertFalse(broken.isEmpty());
        assertTrue(broken.get(0).contains("JSON"));
    }

    @Test
    void 商户创建属性主数据_禁止() {
        loginMerchant();
        assertThrows(BizException.class, () -> attrKeyService.create(new AttrKeySaveRequest()));
        verify(attrKeyMapper, never()).insert(any());
    }

    @Test
    void 平台创建枚举属性_无可选值_参数拒绝() {
        loginPlatform();
        AttrKeySaveRequest req = new AttrKeySaveRequest();
        req.setCategoryId(0L);
        req.setName("颜色");
        req.setAttrType(1);
        req.setValueType(1);
        req.setValueOptions(List.of());

        BizException ex = assertThrows(BizException.class, () -> attrKeyService.create(req));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(attrKeyMapper, never()).insert(any());
    }

    @Test
    void 平台创建属性_落库默认启用() {
        loginPlatform();
        AttrKeySaveRequest req = new AttrKeySaveRequest();
        req.setCategoryId(0L);
        req.setName("颜色");
        req.setAttrType(1);
        req.setValueType(1);
        req.setValueOptions(List.of("红", "蓝"));

        attrKeyService.create(req);

        ArgumentCaptor<ProductAttrKey> captor = ArgumentCaptor.forClass(ProductAttrKey.class);
        verify(attrKeyMapper).insert(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals("颜色", captor.getValue().getName());
    }

    @Test
    void 商户删除属性_禁止() {
        loginMerchant();
        assertThrows(BizException.class, () -> attrKeyService.delete(1L));
        verify(attrKeyMapper, never()).update(any(), any());
    }

    @Test
    void 平台删除不存在属性_NOT_FOUND() {
        loginPlatform();
        when(attrKeyMapper.selectById(anyLong())).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> attrKeyService.delete(404L));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }
}
