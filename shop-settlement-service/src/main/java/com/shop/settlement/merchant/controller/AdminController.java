package com.shop.settlement.merchant.controller;

import com.shop.common.result.PageResult;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.settlement.deposit.entity.SettDepositLog;
import com.shop.settlement.deposit.service.DepositService;
import com.shop.settlement.merchant.dto.DepositFineRequest;
import com.shop.settlement.merchant.dto.OnboardMerchantRequest;
import com.shop.settlement.merchant.dto.UpdateLevelRequest;
import com.shop.settlement.merchant.entity.SettMerchant;
import com.shop.settlement.merchant.service.MerchantService;
import com.shop.settlement.statement.service.StatementSettleService;
import com.shop.settlement.support.WebIdentity;
import com.shop.settlement.withdraw.service.WithdrawService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 平台运营端：商户入驻/等级、保证金记录、手动触发日终结算批。
 */
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final MerchantService merchantService;
    private final DepositService depositService;
    private final StatementSettleService statementSettleService;
    private final WithdrawService withdrawService;

    /** 商户入驻（等级/类目默认佣金率/应缴保证金）。 */
    @PostMapping("/admin/merchants")
    @AuditLog(action = "MERCHANT_CREATE", targetType = "MERCHANT",
            targetIdSpEL = "#request.merchantId", captureArgs = true)
    public Result<Long> onboard(@Valid @RequestBody OnboardMerchantRequest request) {
        WebIdentity.requirePlatformAdmin();
        SettMerchant merchant = merchantService.onboard(request.getMerchantId(), request.getMerchantName(),
                request.getCategoryId(), request.getCategoryName(),
                request.getCommissionRateBps(), request.getDepositRequiredFen());
        return Result.success(merchant.getId());
    }

    /** 调整商户等级 S/A/B/C（决定结算周期 T+1/7/15/30）。 */
    @PutMapping("/admin/merchants/{id}/level")
    @AuditLog(action = "MERCHANT_LEVEL_CHANGE", targetType = "MERCHANT",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<Void> updateLevel(@PathVariable("id") Long id,
                                    @Valid @RequestBody UpdateLevelRequest request) {
        WebIdentity.requirePlatformAdmin();
        merchantService.updateLevel(id, request.getLevel());
        return Result.success();
    }

    /** 商户清退登记（进入 90 天观察期，期满无售后纠纷退保证金）。 */
    @PostMapping("/admin/merchants/{id}/resign")
    @AuditLog(action = "MERCHANT_RESIGN", targetType = "MERCHANT",
            targetIdSpEL = "#id", captureArgs = true)
    public Result<Void> resign(@PathVariable("id") Long id) {
        WebIdentity.requirePlatformAdmin();
        merchantService.requireMerchant(id);
        depositService.resign(id);
        return Result.success();
    }

    /** 保证金流水查询（可按商户过滤）。 */
    @GetMapping("/admin/deposit/records")
    public Result<PageResult<SettDepositLog>> depositRecords(
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        WebIdentity.requirePlatformAdmin();
        return Result.success(depositService.adminPage(merchantId, pageNum, Math.min(pageSize, 100)));
    }

    /**
     * 保证金罚款（B10）：余额行锁校验禁负 → 扣保证金 + DP log30（clientToken 幂等）→
     * 平台账户 43 同事务入账；扣后低于应缴 50% 发 DEPOSIT_ALERT。返回罚款流水号。
     */
    @PostMapping("/admin/deposit/fine")
    @AuditLog(action = "SETTLE_DEPOSIT_FINE", targetType = "DEPOSIT_LOG",
            targetIdSpEL = "#request.merchantId", captureArgs = true)
    public Result<String> depositFine(@Valid @RequestBody DepositFineRequest request) {
        WebIdentity.requirePlatformAdmin();
        SettDepositLog fineLog = depositService.fine(request.getMerchantId(), request.getAmountFen(),
                request.getReason(), request.getClientToken());
        return Result.success(fineLog.getLogNo());
    }

    /**
     * 提现打款失败人工标记（B10）：20→40，冻结退回可提现并发 WITHDRAW_RESULT(失败)。
     * 供真实渠道查询确认失败 / 人工核实后的终态闭环。
     */
    @PostMapping("/admin/withdraw/{no}/mark-failed")
    @AuditLog(action = "WITHDRAW_MARK_FAILED", targetType = "WITHDRAW",
            targetIdSpEL = "#no", captureArgs = true)
    public Result<Void> markWithdrawFailed(@org.springframework.web.bind.annotation.PathVariable("no") String no,
                                           @RequestParam(required = false) String reason) {
        WebIdentity.requirePlatformAdmin();
        withdrawService.markFailed(no, reason);
        return Result.success();
    }

    /** 手动触发日终结算批（对账/补偿场景）。 */
    @PostMapping("/admin/reconcile/settle")
    @AuditLog(action = "SETTLE_MANUAL_RUN", targetType = "RECON_BATCH", captureArgs = true)
    public Result<List<StatementSettleService.MerchantSettle>> manualSettle(
            @RequestParam(required = false) String date) {
        WebIdentity.requirePlatformAdmin();
        LocalDate today = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        return Result.success(statementSettleService.runDailySettle(today));
    }
}
