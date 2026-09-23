package com.shop.settlement.clearing.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.settlement.clearing.entity.SettClearing;
import com.shop.settlement.clearing.service.ClearingService;
import com.shop.settlement.support.WebIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户端：清算单查询（design 7.3）。
 */
@RestController
@RequestMapping("/merchant/clearing")
@RequiredArgsConstructor
public class MerchantClearingController {

    private final ClearingService clearingService;

    @GetMapping
    public Result<PageResult<SettClearing>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        long merchantId = WebIdentity.requireMerchantId();
        return Result.success(clearingService.pageMerchant(merchantId, pageNum, Math.min(pageSize, 100)));
    }
}
