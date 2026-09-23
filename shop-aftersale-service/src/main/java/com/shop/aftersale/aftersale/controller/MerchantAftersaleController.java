package com.shop.aftersale.aftersale.controller;

import com.shop.aftersale.aftersale.dto.AftersalePageQuery;
import com.shop.aftersale.aftersale.dto.AuditRequest;
import com.shop.aftersale.aftersale.dto.EvidenceRequest;
import com.shop.aftersale.aftersale.dto.LogisticsRequest;
import com.shop.aftersale.aftersale.dto.MerchantReceiveRequest;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.enums.AftersaleCodes;
import com.shop.aftersale.aftersale.service.AftersaleService;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.framework.web.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户端售后接口（审核、收货确认、换货/补发发货、商家举证）。
 */
@RestController
@RequestMapping("/merchant/aftersales")
@RequiredArgsConstructor
public class MerchantAftersaleController {

    private final AftersaleService aftersaleService;

    /**
     * 商家审核。同一端点按 request.agree 分支同意/拒绝，静态注解无法拆成两个 action，
     * 故 action 取合并常量，captureArgs 记录 agree/rejectReason 以区分（O5）。
     */
    @PostMapping("/{aftersaleNo}/audit")
    @AuditLog(action = "AFTERSALE_APPROVE_OR_REJECT", targetType = "AFTERSALE",
            targetIdSpEL = "#aftersaleNo", captureArgs = true)
    public Result<Void> audit(@PathVariable String aftersaleNo, @Valid @RequestBody AuditRequest request) {
        aftersaleService.audit(aftersaleNo, UserContext.getMerchantIdOrNull(),
                Boolean.TRUE.equals(request.getAgree()), request.getRejectReason());
        return Result.success();
    }

    /**
     * 商家确认收货退款 / 拒收（accept=false 拒收结论随 captureArgs 入审计）。
     */
    @PostMapping("/{aftersaleNo}/receive")
    @AuditLog(action = "AFTERSALE_CONFIRM_RECEIVE", targetType = "AFTERSALE",
            targetIdSpEL = "#aftersaleNo", captureArgs = true)
    public Result<Void> receive(@PathVariable String aftersaleNo,
                                @Valid @RequestBody MerchantReceiveRequest request) {
        aftersaleService.merchantReceive(aftersaleNo, UserContext.getMerchantIdOrNull(),
                Boolean.TRUE.equals(request.getAccept()), request.getRejectReason());
        return Result.success();
    }

    /** 换货 / 补发发货 */
    @PostMapping("/{aftersaleNo}/ship")
    public Result<Void> ship(@PathVariable String aftersaleNo, @Valid @RequestBody LogisticsRequest request) {
        aftersaleService.shipExchange(aftersaleNo, UserContext.getMerchantIdOrNull(), request);
        return Result.success();
    }

    /** 商家举证 */
    @PostMapping("/{aftersaleNo}/evidence")
    @AuditLog(action = "AFTERSALE_SUBMIT_EVIDENCE", targetType = "AFTERSALE",
            targetIdSpEL = "#aftersaleNo")
    public Result<Void> evidence(@PathVariable String aftersaleNo,
                                 @Valid @RequestBody EvidenceRequest request) {
        aftersaleService.submitEvidence(aftersaleNo, AftersaleCodes.SIDE_MERCHANT,
                UserContext.getUserId(), request);
        return Result.success();
    }

    @GetMapping("/page")
    public Result<PageResult<AftersaleOrder>> page(AftersalePageQuery query) {
        return Result.success(aftersaleService.pageMerchant(query, UserContext.getMerchantIdOrNull()));
    }
}
