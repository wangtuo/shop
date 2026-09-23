package com.shop.marketing.activity.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.marketing.activity.dto.ActivitySaveRequest;
import com.shop.marketing.activity.entity.Activity;
import com.shop.marketing.activity.service.ActivityAdminService;
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

/** 商户/平台营销活动管理（秒杀/拼团/预售/砍价/抽奖）。 */
@RestController
@RequestMapping("/admin/activities")
@RequiredArgsConstructor
public class ActivityAdminController {

    private final ActivityAdminService activityAdminService;

    @PostMapping
    @AuditLog(action = "MARKETING_ACTIVITY_SAVE", targetType = "ACTIVITY",
            targetIdSpEL = "#req.id", captureArgs = true)
    public Result<Long> save(@Valid @RequestBody ActivitySaveRequest req) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(activityAdminService.save(req));
    }

    @PostMapping("/{id}/status")
    @AuditLog(action = "MARKETING_ACTIVITY_STATUS_CHANGE", targetType = "ACTIVITY",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<Void> changeStatus(@PathVariable Long id, @RequestParam Integer status) {
        WebIdentity.requirePlatformAdmin();
        activityAdminService.changeStatus(id, status);
        return Result.success();
    }

    @GetMapping
    public Result<PageResult<Activity>> page(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "20") long pageSize,
                                            @RequestParam(required = false) Integer type,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) Long shopId) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(activityAdminService.page(pageNum, pageSize, type, status, shopId));
    }
}
