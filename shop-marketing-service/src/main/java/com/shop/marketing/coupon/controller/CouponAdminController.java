package com.shop.marketing.coupon.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.marketing.coupon.dto.CouponSaveRequest;
import com.shop.marketing.coupon.entity.Coupon;
import com.shop.marketing.coupon.entity.CouponTarget;
import com.shop.marketing.coupon.service.CouponAdminService;
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

import java.util.List;

/** 商户/平台优惠券模板管理。 */
@RestController
@RequestMapping("/admin/coupons")
@RequiredArgsConstructor
public class CouponAdminController {

    private final CouponAdminService couponAdminService;

    @PostMapping
    @AuditLog(action = "MARKETING_COUPON_SAVE", targetType = "COUPON",
            targetIdSpEL = "#req.id", captureArgs = true)
    public Result<Long> save(@Valid @RequestBody CouponSaveRequest req) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(couponAdminService.save(req));
    }

    @PostMapping("/{id}/status")
    @AuditLog(action = "MARKETING_COUPON_STATUS_CHANGE", targetType = "COUPON",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        WebIdentity.requirePlatformAdmin();
        couponAdminService.changeStatus(id, status);
        return Result.success();
    }

    @GetMapping("/{id}/targets")
    public Result<List<CouponTarget>> targets(@PathVariable Long id) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(couponAdminService.targets(id));
    }

    @GetMapping
    public Result<PageResult<Coupon>> page(@RequestParam(defaultValue = "1") long pageNum,
                                           @RequestParam(defaultValue = "20") long pageSize,
                                           @RequestParam(required = false) Integer type,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) Long shopId) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(couponAdminService.page(pageNum, pageSize, type, status, shopId));
    }
}
