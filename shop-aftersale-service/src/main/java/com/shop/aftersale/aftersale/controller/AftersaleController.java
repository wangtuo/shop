package com.shop.aftersale.aftersale.controller;

import com.shop.aftersale.aftersale.dto.AftersaleApplyRequest;
import com.shop.aftersale.aftersale.dto.AftersaleDetailVO;
import com.shop.aftersale.aftersale.dto.AftersalePageQuery;
import com.shop.aftersale.aftersale.dto.EvidenceRequest;
import com.shop.aftersale.aftersale.dto.LogisticsRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialRequest;
import com.shop.aftersale.aftersale.dto.PriceProtectTrialVO;
import com.shop.aftersale.aftersale.entity.AftersaleOrder;
import com.shop.aftersale.aftersale.enums.AftersaleCodes;
import com.shop.aftersale.aftersale.service.AftersaleService;
import com.shop.aftersale.support.WebIdentity;
import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
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
 * C 端售后接口。
 */
@RestController
@RequestMapping("/aftersales")
@RequiredArgsConstructor
public class AftersaleController {

    private final AftersaleService aftersaleService;

    /** 申请售后（仅退款/退货退款/换货/补发/价保） */
    @PostMapping
    public Result<String> apply(@Valid @RequestBody AftersaleApplyRequest request) {
        return Result.success(aftersaleService.apply(request, UserContext.getUserId()));
    }

    /** 用户撤单 */
    @PostMapping("/{aftersaleNo}/cancel")
    public Result<Void> cancel(@PathVariable String aftersaleNo) {
        aftersaleService.cancel(aftersaleNo, UserContext.getUserId());
        return Result.success();
    }

    /** 拒绝后修改重提 */
    @PostMapping("/{aftersaleNo}/resubmit")
    public Result<Void> resubmit(@PathVariable String aftersaleNo,
                                 @Valid @RequestBody AftersaleApplyRequest request) {
        aftersaleService.resubmit(aftersaleNo, request, UserContext.getUserId());
        return Result.success();
    }

    /** 用户填写退货物流 */
    @PostMapping("/{aftersaleNo}/return-logistics")
    public Result<Void> returnLogistics(@PathVariable String aftersaleNo,
                                        @Valid @RequestBody LogisticsRequest request) {
        aftersaleService.fillReturnLogistics(aftersaleNo, UserContext.getUserId(), request);
        return Result.success();
    }

    /** 用户确认签收换货/补发 */
    @PostMapping("/{aftersaleNo}/exchange-confirm")
    public Result<Void> exchangeConfirm(@PathVariable String aftersaleNo) {
        aftersaleService.confirmExchange(aftersaleNo, UserContext.getUserId());
        return Result.success();
    }

    /** 申请平台介入 */
    @PostMapping("/{aftersaleNo}/intervene")
    public Result<Void> intervene(@PathVariable String aftersaleNo) {
        aftersaleService.applyIntervene(aftersaleNo, UserContext.getUserId());
        return Result.success();
    }

    /** 买家举证 */
    @PostMapping("/{aftersaleNo}/evidence")
    public Result<Void> evidence(@PathVariable String aftersaleNo,
                                 @Valid @RequestBody EvidenceRequest request) {
        aftersaleService.submitEvidence(aftersaleNo, AftersaleCodes.SIDE_BUYER,
                UserContext.getUserId(), request);
        return Result.success();
    }

    /** 价保试算 */
    @PostMapping("/price-protect/trial")
    public Result<PriceProtectTrialVO> priceProtectTrial(@Valid @RequestBody PriceProtectTrialRequest request) {
        return Result.success(aftersaleService.trialPriceProtect(request, UserContext.getUserId()));
    }

    @GetMapping("/{aftersaleNo}")
    public Result<AftersaleDetailVO> detail(@PathVariable String aftersaleNo) {
        return Result.success(aftersaleService.detail(aftersaleNo, WebIdentity.requireUser()));
    }

    @GetMapping("/page")
    public Result<PageResult<AftersaleOrder>> page(AftersalePageQuery query) {
        return Result.success(aftersaleService.pageUser(query, UserContext.getUserId()));
    }
}
