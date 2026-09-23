package com.shop.product.freight.service;

import com.shop.product.freight.dto.FreightRegionSaveRequest;
import com.shop.product.freight.dto.FreightTemplateSaveRequest;
import com.shop.product.freight.dto.FreightTemplateVO;
import com.shop.product.freight.entity.FreightTemplate;

import java.util.List;

/**
 * 商家运费模板 / 区域规则 CRUD（B7）。所有方法强制当前登录商户归属鉴权。
 */
public interface FreightTemplateService {

    /** 新建模板，返回模板 ID；isDefault=1 时 CAS 清除同店其他默认。 */
    Long create(FreightTemplateSaveRequest request);

    /** 编辑模板（不含启停，启停走 {@link #updateStatus}）。 */
    void update(Long templateId, FreightTemplateSaveRequest request);

    /** 逻辑删除模板及其区域规则。 */
    void delete(Long templateId);

    /** 当前商户模板列表（按默认优先、更新时间倒序）。 */
    List<FreightTemplate> listTemplates();

    /** 模板详情（含区域规则），非本店抛 FORBIDDEN。 */
    FreightTemplateVO detail(Long templateId);

    /** 显式设置店铺默认模板（同事务 CAS 清除其他默认）。 */
    void setDefault(Long templateId);

    /** 启用/停用模板。 */
    void updateStatus(Long templateId, Integer status);

    /** 新增区域规则，返回规则 ID。 */
    Long addRegion(Long templateId, FreightRegionSaveRequest request);

    /** 编辑区域规则。 */
    void updateRegion(Long ruleId, FreightRegionSaveRequest request);

    /** 删除区域规则。 */
    void deleteRegion(Long ruleId);
}
