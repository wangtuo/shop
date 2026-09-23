package com.shop.settlement.deposit.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.settlement.deposit.dto.DepositPayRequest;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.deposit.vo.DepositPayVO;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.support.WebIdentity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户端：保证金查询与缴纳（design 7.6，B10 缴费走真实支付单 payScene=4）。
 */
@RestController
@RequestMapping("/merchant/deposit")
@RequiredArgsConstructor
public class MerchantDepositController {

    private final DepositService depositService;
    private final MerchantService merchantService;
    private final SettleNoGenerator noGenerator;

    /** 保证金概览 + 流水。 */
    @GetMapping
    public Result<PageResult<SettDepositLog>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        long merchantId = WebIdentity.requireMerchantId();
        // 鉴权：商户必须存在且查本店数据
        merchantService.requireMerchant(merchantId);
        return Result.success(depositService.adminPage(merchantId, pageNum, Math.min(pageSize, 100)));
    }

    /**
     * 缴纳保证金（三段式）：先建 DP 日志（不动余额），再调支付域建单（payScene=4,
     * orderNo=logNo），返回 {@code {logNo, payNo, payUrl}} 由前端拉起收银台；
     * 到账以 ORDER_PAID 事件入账。幂等见 {@link DepositService#initiateDepositPay}。
     */
    @PostMapping
    @AuditLog(action = "SETTLE_DEPOSIT_PAY", targetType = "DEPOSIT_LOG", captureArgs = true)
    public Result<DepositPayVO> pay(@Valid @RequestBody DepositPayRequest request) {
        long merchantId = WebIdentity.requireMerchantId();
        String logNo = noGenerator.nextDepositLogNo();
        return Result.success(depositService.initiateDepositPay(merchantId, request, logNo));
    }
}
