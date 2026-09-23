package com.shop.aftersale.aftersale.controller;

import com.shop.aftersale.aftersale.dto.ArbitrateRequest;
import com.shop.aftersale.aftersale.service.AftersaleService;
import com.shop.aftersale.support.WebIdentity;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营端：平台介入仲裁（5 个工作日内终局裁决）。
 */
@RestController
@RequestMapping("/platform/aftersales")
@RequiredArgsConstructor
public class PlatformAftersaleController {

    private final AftersaleService aftersaleService;

    @PostMapping("/{aftersaleNo}/arbitration")
    @AuditLog(action = "AFTERSALE_ARBITRATE", targetType = "AFTERSALE",
            targetIdSpEL = "#aftersaleNo", captureArgs = true)
    public Result<Void> arbitrate(@PathVariable String aftersaleNo,
                                  @Valid @RequestBody ArbitrateRequest request) {
        // H-1：强制平台运营身份（userType=2）
        WebIdentity.requirePlatformAdmin();
        aftersaleService.arbitrate(aftersaleNo, request);
        return Result.success();
    }
}
