package com.shop.product.goods.service;

import com.shop.api.product.dto.SpuDTO;
import com.shop.common.result.PageResult;
import com.shop.product.goods.dto.SpuAuditRequest;
import com.shop.product.goods.dto.SpuBrowseQuery;
import com.shop.product.goods.dto.SpuDetailVO;
import com.shop.product.goods.dto.SpuManageQuery;
import com.shop.product.goods.dto.SpuSaveRequest;
import com.shop.product.goods.dto.SpuSaveResult;

/**
 * SPU/SKU 商户管理与平台审核服务。
 */
public interface SpuService {

    /** 商户新建 SPU（草稿态），返回 SPU ID 与属性主数据校验告警（B13，告警不阻断）。 */
    SpuSaveResult create(SpuSaveRequest request);

    /** 商户编辑 SPU（仅草稿/审核拒绝态可编辑），返回属性主数据校验告警。 */
    SpuSaveResult update(Long spuId, SpuSaveRequest request);

    /** 提交审核：草稿/审核拒绝 → 待审核。 */
    void submitAudit(Long spuId);

    /** 平台审核：待审核 → 已上架 / 审核拒绝。 */
    void audit(Long spuId, SpuAuditRequest request);

    /** 商户上架：已下架 → 已上架。 */
    void onSale(Long spuId);

    /** 商户下架：已上架 → 已下架。 */
    void offSale(Long spuId);

    /** 平台违规下架（待审核/在售/下架/售罄 → 违规下架）。 */
    void violationOff(Long spuId, String remark);

    /** 商户逻辑删除商品。 */
    void delete(Long spuId);

    /** 商户端商品管理分页（强制按登录 merchantId 过滤）。 */
    PageResult<SpuDTO> managePage(SpuManageQuery query);

    /** 平台端商品分页（可按状态过滤，默认列出待审核）。 */
    PageResult<SpuDTO> adminPage(SpuManageQuery query);

    /** 商户/平台查看商品完整详情（商户限本店）。 */
    SpuDetailVO manageDetail(Long spuId);

    /** C 端 SPU 详情（仅上架/售罄可见，带版本缓存）。 */
    SpuDetailVO browseDetail(Long spuId);

    /** C 端按三级类目/关键词分页在售商品。 */
    PageResult<SpuDTO> browsePage(SpuBrowseQuery query);
}
