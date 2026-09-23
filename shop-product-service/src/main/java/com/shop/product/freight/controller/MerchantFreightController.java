package com.shop.product.freight.controller;

import com.shop.common.result.Result;
import com.shop.product.freight.dto.FreightRegionSaveRequest;
import com.shop.product.freight.dto.FreightTemplateSaveRequest;
import com.shop.product.freight.dto.FreightTemplateStatusRequest;
import com.shop.product.freight.dto.FreightTemplateVO;
import com.shop.product.freight.entity.FreightTemplate;
import com.shop.product.freight.service.FreightTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端运费模板管理（/merchant/freight/templates，B7）。
 * 鉴权复用商品域现状：merchantId 取自登录上下文（AuthUtils），服务内做归属校验。
 */
@RestController
@RequestMapping("/merchant/freight/templates")
@RequiredArgsConstructor
public class MerchantFreightController {

    private final FreightTemplateService freightTemplateService;

    /** 新建运费模板 */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody FreightTemplateSaveRequest request) {
        return Result.success(freightTemplateService.create(request));
    }

    /** 编辑运费模板 */
    @PutMapping("/{templateId}")
    public Result<Void> update(@PathVariable Long templateId,
                               @Valid @RequestBody FreightTemplateSaveRequest request) {
        freightTemplateService.update(templateId, request);
        return Result.success();
    }

    /** 当前商户模板列表 */
    @GetMapping
    public Result<List<FreightTemplate>> list() {
        return Result.success(freightTemplateService.listTemplates());
    }

    /** 模板详情（含区域规则） */
    @GetMapping("/{templateId}")
    public Result<FreightTemplateVO> detail(@PathVariable Long templateId) {
        return Result.success(freightTemplateService.detail(templateId));
    }

    /** 逻辑删除模板 */
    @PostMapping("/{templateId}/delete")
    public Result<Void> delete(@PathVariable Long templateId) {
        freightTemplateService.delete(templateId);
        return Result.success();
    }

    /** 设为店铺默认模板（同店唯一） */
    @PostMapping("/{templateId}/default")
    public Result<Void> setDefault(@PathVariable Long templateId) {
        freightTemplateService.setDefault(templateId);
        return Result.success();
    }

    /** 启用/停用模板 */
    @PutMapping("/{templateId}/status")
    public Result<Void> updateStatus(@PathVariable Long templateId,
                                     @Valid @RequestBody FreightTemplateStatusRequest request) {
        freightTemplateService.updateStatus(templateId, request.getStatus());
        return Result.success();
    }

    /** 新增区域运费规则 */
    @PostMapping("/{templateId}/regions")
    public Result<Long> addRegion(@PathVariable Long templateId,
                                  @Valid @RequestBody FreightRegionSaveRequest request) {
        return Result.success(freightTemplateService.addRegion(templateId, request));
    }

    /** 编辑区域运费规则 */
    @PutMapping("/regions/{ruleId}")
    public Result<Void> updateRegion(@PathVariable Long ruleId,
                                     @Valid @RequestBody FreightRegionSaveRequest request) {
        freightTemplateService.updateRegion(ruleId, request);
        return Result.success();
    }

    /** 删除区域运费规则 */
    @DeleteMapping("/regions/{ruleId}")
    public Result<Void> deleteRegion(@PathVariable Long ruleId) {
        freightTemplateService.deleteRegion(ruleId);
        return Result.success();
    }
}
