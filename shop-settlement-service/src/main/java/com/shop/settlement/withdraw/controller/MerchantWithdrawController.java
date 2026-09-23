package com.shop.settlement.withdraw.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.ratelimit.RateLimit;
import com.shop.settlement.support.SettleNoGenerator;
import com.shop.settlement.support.WebIdentity;
import com.shop.settlement.withdraw.dto.ApplyWithdrawRequest;
import com.shop.settlement.withdraw.dto.AutoWithdrawConfigRequest;
import com.shop.settlement.withdraw.entity.SettWithdraw;
import com.shop.settlement.withdraw.service.WithdrawService;
import com.shop.settlement.withdraw.vo.AutoWithdrawConfigVO;
import com.shop.settlement.withdraw.vo.WithdrawVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户端：提现申请/列表 + 自动提现配置（design 7.4）。
 */
@RestController
@RequiredArgsConstructor
public class MerchantWithdrawController {

    private final WithdrawService withdrawService;
    private final SettleNoGenerator noGenerator;

    /**
     * 申请提现（100 元起、单日 50 万、前 3 笔/月免费、保证金 50% 校验在 Service 完成）。
     * 幂等见 {@link WithdrawService#apply}（M-3：merchantId + clientToken/withdrawNo）。
     */
    @PostMapping("/merchant/withdrawals")
    @RateLimit(prefix = "settlement:withdraw", permits = 5, windowSeconds = 60,
            message = "提现申请过于频繁，请稍后再试")
    public Result<String> apply(@Valid @RequestBody ApplyWithdrawRequest request) {
        long merchantId = WebIdentity.requireMerchantId();
        // 服务端预生成提现单号并覆盖 body：既是业务单号，也是缺省 clientToken 时的幂等键
        request.setWithdrawNo(noGenerator.nextWithdrawNo());
        SettWithdraw withdraw = withdrawService.apply(merchantId, request, false);
        return Result.success(withdraw.getWithdrawNo());
    }

    /** 提现单列表（仅本店，账号脱敏）。 */
    @GetMapping("/merchant/withdrawals")
    public Result<PageResult<WithdrawVO>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        long merchantId = WebIdentity.requireMerchantId();
        return Result.success(withdrawService.pageMerchant(merchantId, pageNum, Math.min(pageSize, 100)));
    }

    /** 查询自动提现配置（账号脱敏）。 */
    @GetMapping("/merchant/withdraw/auto-config")
    public Result<AutoWithdrawConfigVO> getConfig() {
        long merchantId = WebIdentity.requireMerchantId();
        return Result.success(withdrawService.getConfigMasked(merchantId));
    }

    /** 设置自动提现（每日/每周，银行卡/支付宝）。 */
    @PutMapping("/merchant/withdraw/auto-config")
    public Result<AutoWithdrawConfigVO> saveConfig(@Valid @RequestBody AutoWithdrawConfigRequest request) {
        long merchantId = WebIdentity.requireMerchantId();
        return Result.success(withdrawService.saveConfig(merchantId, request));
    }
}
