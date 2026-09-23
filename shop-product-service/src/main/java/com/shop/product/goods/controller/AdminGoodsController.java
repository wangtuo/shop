package com.shop.product.goods.controller;

import com.shop.api.product.dto.SpuDTO;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.product.goods.dto.SpuAuditRequest;
import com.shop.product.goods.dto.SpuDetailVO;
import com.shop.product.goods.dto.SpuManageQuery;
import com.shop.product.goods.service.SpuService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端商品管理（/admin/products）：审核、违规下架、商品查看。仅 userType=2。
 */
@RestController
@RequestMapping("/admin/products")
@RequiredArgsConstructor
@Validated
public class AdminGoodsController {

    private final SpuService spuService;

    /** 平台商品分页（status=1 即待审核列表） */
    @GetMapping
    public Result<PageResult<SpuDTO>> page(@ModelAttribute SpuManageQuery query) {
        return Result.success(spuService.adminPage(query));
    }

    /** 平台查看商品详情 */
    @GetMapping("/{spuId}")
    public Result<SpuDetailVO> detail(@PathVariable Long spuId) {
        return Result.success(spuService.manageDetail(spuId));
    }

    /** 审核通过 / 拒绝 */
    @PostMapping("/{spuId}/audit")
    @AuditLog(action = "PRODUCT_AUDIT", targetType = "SPU",
            targetIdSpEL = "#spuId", captureArgs = true)
    public Result<Void> audit(@PathVariable Long spuId, @Valid @RequestBody SpuAuditRequest request) {
        spuService.audit(spuId, request);
        return Result.success();
    }

    /** 违规下架 */
    @PostMapping("/{spuId}/violation")
    @AuditLog(action = "PRODUCT_VIOLATION", targetType = "SPU",
            targetIdSpEL = "#spuId", captureArgs = true)
    public Result<Void> violation(@PathVariable Long spuId,
                                  @RequestParam(required = false) @Size(max = 256, message = "违规备注最长 256 字")
                                  String remark) {
        spuService.violationOff(spuId, remark);
        return Result.success();
    }
}
