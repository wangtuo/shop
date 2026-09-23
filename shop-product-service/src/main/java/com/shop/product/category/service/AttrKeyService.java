package com.shop.product.category.service;

import com.shop.product.category.dto.AttrKeySaveRequest;
import com.shop.product.category.entity.ProductAttrKey;

import java.util.List;

/**
 * 规格/属性主数据服务（B13）：平台运营维护，建品时按主数据校验 attrsJson，
 * 未收录属性仍进商品快照但返回告警（向后兼容，不强拒）。
 */
public interface AttrKeyService {

    /** 新建属性主数据（平台运营），返回 ID。 */
    Long create(AttrKeySaveRequest request);

    /** 编辑属性主数据（平台运营）。 */
    void update(Long id, AttrKeySaveRequest request);

    /** 删除属性主数据（平台运营，逻辑删除）。 */
    void delete(Long id);

    /** 查类目适用的启用属性（类目私有 + 全局通用）。 */
    List<ProductAttrKey> listByCategory(Long categoryId);

    /**
     * 按属性主数据校验 SPU attrsJson（JSON 对象：{属性名: 值}）。
     * 必填缺失 / 枚举越界 / 数值非法 / 未收录属性均只告警不拒绝；
     * attrsJson 原样进快照，本方法无副作用。
     *
     * @return 人读告警信息列表，空列表表示无告警
     */
    List<String> validateAttrs(Long categoryId, String attrsJson);
}
