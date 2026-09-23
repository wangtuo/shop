package com.shop.pay.feature.recon.controller;

import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.pay.feature.recon.entity.ReconBatch;
import com.shop.pay.feature.recon.entity.ReconDiff;
import com.shop.pay.feature.recon.service.ReconcileService;
import com.shop.pay.support.WebIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 平台对账管理接口（design 6.5）：触发 T+1 对账、差错查询与补偿处理。
 * H-1：全部端点强制平台运营身份（userType=2）。
 */
@RestController
@RequestMapping("/platform/recon")
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileService reconcileService;

    /** 手动触发指定日期/渠道对账（平台）。 */
    @PostMapping("/run")
    @AuditLog(action = "RECON_SETTLE", targetType = "RECON_BATCH", captureArgs = true)
    public Result<ReconBatch> run(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("channel") String channel) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(reconcileService.runReconcile(date, channel));
    }

    /** 差错列表：status 不传查全部（10 待处理 20 处理中 30 已处理 40 人工挂账）。 */
    @GetMapping("/diffs")
    public Result<List<ReconDiff>> diffs(@RequestParam(value = "status", required = false) Integer status) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(reconcileService.listDiffs(status));
    }

    /** 单笔差错补偿处理。 */
    @PostMapping("/diffs/{id}/handle")
    @AuditLog(action = "RECON_DIFF_HANDLE", targetType = "RECON_DIFF",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<ReconDiff> handle(@PathVariable("id") Long id) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(reconcileService.handleDiff(id));
    }

    /** 批量补偿重试待处理差错。 */
    @PostMapping("/retry")
    @AuditLog(action = "RECON_RETRY", targetType = "RECON_BATCH", captureArgs = true)
    public Result<Integer> retry(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(reconcileService.retryPendingDiffs(limit));
    }
}
