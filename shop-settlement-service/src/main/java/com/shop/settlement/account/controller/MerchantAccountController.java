package com.shop.settlement.account.controller;

import com.shop.api.settlement.enums.AccountRole;
import com.shop.common.result.Result;
import com.shop.settlement.account.entity.SettAccount;
import com.shop.settlement.account.service.AccountService;
import com.shop.settlement.account.vo.MerchantAccountVO;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.support.WebIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户端：资金账户余额（design 7.1.1 / 7.4）。
 */
@RestController
@RequestMapping("/merchant/account")
@RequiredArgsConstructor
public class MerchantAccountController {

    private final AccountService accountService;
    private final MerchantService merchantService;
    private final DepositService depositService;

    @GetMapping
    public Result<MerchantAccountVO> balance() {
        long merchantId = WebIdentity.requireMerchantId();
        SettAccount account = accountService.getOrCreate(merchantId, AccountRole.MERCHANT);
        SettMerchant merchant = merchantService.requireMerchant(merchantId);
        return Result.success(MerchantAccountVO.builder()
                .merchantId(merchantId)
                .availableFen(account.getAvailableFen())
                .frozenFen(account.getFrozenFen())
                .pendingSettleFen(account.getPendingSettleFen())
                .depositBalanceFen(merchant.getDepositBalanceFen())
                .depositRequiredFen(merchant.getDepositRequiredFen())
                .depositThresholdFen(depositService.thresholdFen(merchant))
                .withdrawBlocked(depositService.belowAlertThreshold(merchant))
                .build());
    }
}
