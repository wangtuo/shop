package com.shop.settlement.statement.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.settlement.statement.entity.SettStatement;
import com.shop.settlement.statement.service.StatementSettleService;
import com.shop.settlement.support.WebIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户端：结算单查询（design 7.3.2）。
 */
@RestController
@RequestMapping("/merchant/statements")
@RequiredArgsConstructor
public class MerchantStatementController {

    private final StatementSettleService statementSettleService;

    @GetMapping
    public Result<PageResult<SettStatement>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        long merchantId = WebIdentity.requireMerchantId();
        return Result.success(statementSettleService.pageMerchant(merchantId, pageNum, Math.min(pageSize, 100)));
    }
}
