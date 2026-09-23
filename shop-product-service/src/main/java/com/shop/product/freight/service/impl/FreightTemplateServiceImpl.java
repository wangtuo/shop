package com.shop.product.freight.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.product.freight.dto.FreightRegionSaveRequest;
import com.shop.product.freight.dto.FreightTemplateSaveRequest;
import com.shop.product.freight.dto.FreightTemplateVO;
import com.shop.product.freight.entity.FreightRegion;
import com.shop.product.freight.entity.FreightTemplate;
import com.shop.product.freight.mapper.FreightRegionMapper;
import com.shop.product.freight.mapper.FreightTemplateMapper;
import com.shop.product.freight.service.FreightTemplateService;
import com.shop.product.support.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 运费模板商家 CRUD 实现（B7）。
 *
 * <p>is_default 同店唯一：设默认/新建默认/编辑为默认均在同事务内先做条件 UPDATE
 * （{@code WHERE merchant_id=? AND is_default=1 AND id<>?}）清除其他默认，CAS 幂等。
 */
@Service
@RequiredArgsConstructor
public class FreightTemplateServiceImpl implements FreightTemplateService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FreightTemplateMapper templateMapper;
    private final FreightRegionMapper regionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(FreightTemplateSaveRequest request) {
        long merchantId = AuthUtils.requireMerchantId();
        FreightTemplate t = new FreightTemplate();
        t.setMerchantId(merchantId);
        applyTemplateFields(t, request);
        t.setStatus(1);
        templateMapper.insert(t);
        if (Integer.valueOf(1).equals(request.getIsDefault())) {
            templateMapper.clearOtherDefault(merchantId, t.getId());
        }
        return t.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long templateId, FreightTemplateSaveRequest request) {
        FreightTemplate t = loadOwnedTemplate(templateId);
        applyTemplateFields(t, request);
        templateMapper.updateById(t);
        if (Integer.valueOf(1).equals(request.getIsDefault())) {
            templateMapper.clearOtherDefault(t.getMerchantId(), templateId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long templateId) {
        FreightTemplate t = loadOwnedTemplate(templateId);
        templateMapper.deleteById(templateId);
        regionMapper.delete(new LambdaQueryWrapper<FreightRegion>()
                .eq(FreightRegion::getTemplateId, templateId));
    }

    @Override
    public List<FreightTemplate> listTemplates() {
        long merchantId = AuthUtils.requireMerchantId();
        return templateMapper.selectList(new LambdaQueryWrapper<FreightTemplate>()
                .eq(FreightTemplate::getMerchantId, merchantId)
                .orderByDesc(FreightTemplate::getIsDefault)
                .orderByDesc(FreightTemplate::getUpdateTime));
    }

    @Override
    public FreightTemplateVO detail(Long templateId) {
        FreightTemplate t = loadOwnedTemplate(templateId);
        FreightTemplateVO vo = new FreightTemplateVO();
        vo.setTemplate(t);
        vo.setRegions(regionMapper.selectList(new LambdaQueryWrapper<FreightRegion>()
                .eq(FreightRegion::getTemplateId, templateId)
                .orderByAsc(FreightRegion::getId)));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long templateId) {
        FreightTemplate t = loadOwnedTemplate(templateId);
        // 停用模板不允许直接设默认，避免取价查 status=1 默认落空
        if (!Integer.valueOf(1).equals(t.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "停用模板不能设为默认");
        }
        templateMapper.clearOtherDefault(t.getMerchantId(), templateId);
        FreightTemplate patch = new FreightTemplate();
        patch.setId(templateId);
        patch.setIsDefault(1);
        templateMapper.updateById(patch);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long templateId, Integer status) {
        FreightTemplate t = loadOwnedTemplate(templateId);
        FreightTemplate patch = new FreightTemplate();
        patch.setId(templateId);
        patch.setStatus(status);
        // 停用默认模板：同步摘除默认标记，取价回退 DEFAULT_NONE，避免脏默认悬挂
        if (Integer.valueOf(0).equals(status)) {
            patch.setIsDefault(0);
        }
        templateMapper.updateById(patch);
        if (Integer.valueOf(1).equals(status) && Integer.valueOf(1).equals(t.getIsDefault())) {
            templateMapper.clearOtherDefault(t.getMerchantId(), templateId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addRegion(Long templateId, FreightRegionSaveRequest request) {
        FreightTemplate t = loadOwnedTemplate(templateId);
        FreightRegion r = new FreightRegion();
        r.setTemplateId(templateId);
        r.setMerchantId(t.getMerchantId());
        applyRegionFields(r, request);
        regionMapper.insert(r);
        return r.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRegion(Long ruleId, FreightRegionSaveRequest request) {
        FreightRegion r = loadOwnedRegion(ruleId);
        applyRegionFields(r, request);
        regionMapper.updateById(r);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRegion(Long ruleId) {
        loadOwnedRegion(ruleId);
        regionMapper.deleteById(ruleId);
    }

    private FreightTemplate loadOwnedTemplate(Long templateId) {
        long merchantId = AuthUtils.requireMerchantId();
        FreightTemplate t = templateMapper.selectById(templateId);
        if (t == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "运费模板不存在");
        }
        if (!t.getMerchantId().equals(merchantId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作非本店运费模板");
        }
        return t;
    }

    private FreightRegion loadOwnedRegion(Long ruleId) {
        long merchantId = AuthUtils.requireMerchantId();
        FreightRegion r = regionMapper.selectById(ruleId);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "区域运费规则不存在");
        }
        if (!r.getMerchantId().equals(merchantId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作非本店区域规则");
        }
        return r;
    }

    private void applyTemplateFields(FreightTemplate t, FreightTemplateSaveRequest req) {
        t.setName(req.getName());
        t.setChargeType(req.getChargeType());
        t.setDefaultFirst(req.getDefaultFirst());
        t.setDefaultFirstFee(req.getDefaultFirstFee());
        t.setDefaultAdd(req.getDefaultAdd());
        t.setDefaultAddFee(req.getDefaultAddFee());
        t.setFreeConditionFen(req.getFreeConditionFen());
        t.setIsDefault(req.getIsDefault());
    }

    private void applyRegionFields(FreightRegion r, FreightRegionSaveRequest req) {
        try {
            r.setRegionCodes(JSON.writeValueAsString(req.getRegionCodes()));
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "区域编码序列化失败");
        }
        r.setFirstUnit(req.getFirstUnit());
        r.setFirstFeeFen(req.getFirstFeeFen());
        r.setAddUnit(req.getAddUnit());
        r.setAddFeeFen(req.getAddFeeFen());
        r.setDeliverable(req.getDeliverable());
    }
}
