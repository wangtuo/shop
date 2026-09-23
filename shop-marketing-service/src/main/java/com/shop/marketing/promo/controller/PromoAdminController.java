package com.shop.marketing.promo.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.marketing.promo.dto.PromoSaveRequest;
import com.shop.marketing.promo.entity.Promo;
import com.shop.marketing.promo.entity.PromoLevel;
import com.shop.marketing.promo.entity.PromoTarget;
import com.shop.marketing.promo.service.PromoAdminService;
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

/** 商户/平台促销管理。 */
@RestController
@RequestMapping("/admin/promos")
@RequiredArgsConstructor
public class PromoAdminController {

    private final PromoAdminService promoAdminService;

    @PostMapping
    @AuditLog(action = "MARKETING_PROMO_SAVE", targetType = "PROMO",
            targetIdSpEL = "#req.id", captureArgs = true)
    public Result<Long> save(@Valid @RequestBody PromoSaveRequest req) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(promoAdminService.save(req));
    }

    @PostMapping("/{id}/status")
    @AuditLog(action = "MARKETING_PROMO_STATUS_CHANGE", targetType = "PROMO",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        WebIdentity.requirePlatformAdmin();
        promoAdminService.changeStatus(id, status);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<Promo> detail(@PathVariable Long id) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(promoAdminService.detail(id));
    }

    @GetMapping("/{id}/levels")
    public Result<List<PromoLevel>> levels(@PathVariable Long id) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(promoAdminService.levels(id));
    }

    @GetMapping("/{id}/targets")
    public Result<List<PromoTarget>> targets(@PathVariable Long id) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(promoAdminService.targets(id));
    }

    @GetMapping
    public Result<PageResult<Promo>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "20") long pageSize,
                                          @RequestParam(required = false) Integer type,
                                          @RequestParam(required = false) Integer status,
                                          @RequestParam(required = false) Long shopId) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(promoAdminService.page(pageNum, pageSize, type, status, shopId));
    }
}
