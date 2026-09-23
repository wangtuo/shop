package com.shop.marketing.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.marketing.common.audit.AuditRejectRequest;
import com.shop.marketing.common.audit.MarketingAuditService;
import com.shop.marketing.support.MarketingStatusMachine;
import com.shop.marketing.support.WebIdentity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台审核台（卡 B2）：按审核态分页查询 + 通过/驳回。全部接口仅平台运营 userType=2。
 */
@RestController
@RequestMapping("/admin/audits")
@RequiredArgsConstructor
public class PlatformAuditController {

    private final MarketingAuditService auditService;

    /** 审核单据分页，默认查待审核（auditStatus=1）。 */
    @GetMapping
    public Result<PageResult<?>> page(@RequestParam String type,
                                      @RequestParam(required = false) Integer auditStatus,
                                      @RequestParam(defaultValue = "1") long pageNum,
                                      @RequestParam(defaultValue = "20") long pageSize) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(auditService.page(parseDomain(type), auditStatus, pageNum, pageSize));
    }

    @PostMapping("/{type}/{id}/approve")
    @AuditLog(action = "MARKETING_AUDIT_APPROVE", targetType = "MARKETING_AUDIT",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<Void> approve(@PathVariable String type, @PathVariable Long id) {
        WebIdentity.requirePlatformAdmin();
        LoginUser user = UserContext.get();
        auditService.approve(parseDomain(type), id, user.getUserId());
        return Result.success();
    }

    @PostMapping("/{type}/{id}/reject")
    @AuditLog(action = "MARKETING_AUDIT_REJECT", targetType = "MARKETING_AUDIT",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<Void> reject(@PathVariable String type, @PathVariable Long id,
                               @Valid @RequestBody AuditRejectRequest req) {
        WebIdentity.requirePlatformAdmin();
        LoginUser user = UserContext.get();
        auditService.reject(parseDomain(type), id, user.getUserId(), req.getRemark());
        return Result.success();
    }

    private MarketingStatusMachine.Domain parseDomain(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toLowerCase()) {
            case "promo" -> MarketingStatusMachine.Domain.PROMO;
            case "coupon" -> MarketingStatusMachine.Domain.COUPON;
            case "activity" -> MarketingStatusMachine.Domain.ACTIVITY;
            default -> throw new com.shop.common.exception.BizException(
                    com.shop.common.exception.ErrorCode.PARAM_INVALID, "非法审核类型: " + type);
        };
    }
}
