package com.shop.product.freight.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shop.common.exception.BizException;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.product.freight.dto.FreightRegionSaveRequest;
import com.shop.product.freight.dto.FreightTemplateSaveRequest;
import com.shop.product.freight.entity.FreightRegion;
import com.shop.product.freight.entity.FreightTemplate;
import com.shop.product.freight.mapper.FreightRegionMapper;
import com.shop.product.freight.mapper.FreightTemplateMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B7 模板 CRUD：is_default 同店唯一 CAS、归属鉴权、停用联动、区域规则 JSON 落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FreightTemplateServiceImplTest {

    @Mock
    private FreightTemplateMapper templateMapper;
    @Mock
    private FreightRegionMapper regionMapper;

    private FreightTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FreightTemplateServiceImpl(templateMapper, regionMapper);
        UserContext.set(LoginUser.builder().userId(1L).userType(1).merchantId(10L).build());
        when(templateMapper.insert(any(FreightTemplate.class))).thenAnswer(inv -> {
            inv.getArgument(0, FreightTemplate.class).setId(777L);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void create_asDefault_clearsOtherDefaultsViaCas() {
        Long id = service.create(saveRequest("默认模板", 1, 1));
        assertEquals(777L, id);
        verify(templateMapper).clearOtherDefault(10L, 777L);
    }

    @Test
    void create_nonDefault_neverClears() {
        service.create(saveRequest("普通模板", 1, 0));
        verify(templateMapper, never()).clearOtherDefault(any(), any());
    }

    @Test
    void setDefault_enabledTemplate_clearsOthersAndMarks() {
        when(templateMapper.selectById(88L)).thenReturn(template(88L, 10L, 1, 0));

        service.setDefault(88L);

        verify(templateMapper).clearOtherDefault(10L, 88L);
        ArgumentCaptor<FreightTemplate> captor = ArgumentCaptor.forClass(FreightTemplate.class);
        verify(templateMapper).updateById(captor.capture());
        assertEquals(88L, captor.getValue().getId());
        assertEquals(1, captor.getValue().getIsDefault());
    }

    @Test
    void setDefault_disabledTemplate_rejected() {
        when(templateMapper.selectById(88L)).thenReturn(template(88L, 10L, 0, 1));
        BizException e = assertThrows(BizException.class, () -> service.setDefault(88L));
        assertEquals(10001, e.getCode());
        verify(templateMapper, never()).clearOtherDefault(any(), any());
    }

    @Test
    void update_foreignTemplate_forbidden() {
        when(templateMapper.selectById(88L)).thenReturn(template(88L, 99L, 1, 1));
        BizException e = assertThrows(BizException.class, () -> service.update(88L, saveRequest("x", 1, 0)));
        assertEquals(10003, e.getCode());
        verify(templateMapper, never()).updateById(any(FreightTemplate.class));
    }

    @Test
    void disableDefault_clearsDefaultFlag() {
        when(templateMapper.selectById(88L)).thenReturn(template(88L, 10L, 1, 1));

        service.updateStatus(88L, 0);

        ArgumentCaptor<FreightTemplate> captor = ArgumentCaptor.forClass(FreightTemplate.class);
        verify(templateMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getIsDefault());
    }

    @Test
    void addRegion_serializesCodesAndDelete_cascades() {
        when(templateMapper.selectById(88L)).thenReturn(template(88L, 10L, 1, 1));
        FreightRegionSaveRequest req = new FreightRegionSaveRequest();
        req.setRegionCodes(List.of("330000", "330100"));
        req.setFirstUnit(1);
        req.setFirstFeeFen(800L);
        req.setAddUnit(1);
        req.setAddFeeFen(200L);
        req.setDeliverable(1);

        when(regionMapper.insert(any(FreightRegion.class))).thenAnswer(inv -> {
            inv.getArgument(0, FreightRegion.class).setId(999L);
            return 1;
        });
        Long ruleId = service.addRegion(88L, req);
        assertEquals(999L, ruleId);
        ArgumentCaptor<FreightRegion> captor = ArgumentCaptor.forClass(FreightRegion.class);
        verify(regionMapper).insert(captor.capture());
        assertEquals(10L, captor.getValue().getMerchantId());
        assertTrue(captor.getValue().getRegionCodes().contains("330000"));
        assertTrue(captor.getValue().getRegionCodes().contains("330100"));

        service.delete(88L);
        verify(templateMapper).deleteById(88L);
        verify(regionMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    void updateRegion_foreignOwner_forbidden() {
        when(regionMapper.selectById(9L)).thenReturn(region(9L, 99L));
        FreightRegionSaveRequest req = new FreightRegionSaveRequest();
        req.setRegionCodes(List.of("110000"));
        req.setFirstUnit(1);
        req.setFirstFeeFen(0L);
        req.setAddUnit(1);
        req.setAddFeeFen(0L);
        req.setDeliverable(1);
        BizException e = assertThrows(BizException.class, () -> service.updateRegion(9L, req));
        assertEquals(10003, e.getCode());
        verify(regionMapper, never()).updateById(any(FreightRegion.class));
    }

    private FreightTemplateSaveRequest saveRequest(String name, int chargeType, int isDefault) {
        FreightTemplateSaveRequest req = new FreightTemplateSaveRequest();
        req.setName(name);
        req.setChargeType(chargeType);
        req.setDefaultFirst(1);
        req.setDefaultFirstFee(800L);
        req.setDefaultAdd(1);
        req.setDefaultAddFee(200L);
        req.setFreeConditionFen(0L);
        req.setIsDefault(isDefault);
        return req;
    }

    private FreightTemplate template(Long id, Long merchantId, int status, int isDefault) {
        FreightTemplate t = new FreightTemplate();
        t.setId(id);
        t.setMerchantId(merchantId);
        t.setStatus(status);
        t.setIsDefault(isDefault);
        return t;
    }

    private FreightRegion region(Long id, Long merchantId) {
        FreightRegion r = new FreightRegion();
        r.setId(id);
        r.setMerchantId(merchantId);
        r.setTemplateId(88L);
        return r;
    }
}
