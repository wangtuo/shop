package com.shop.marketing.controller.merchant;

import com.shop.common.result.Result;
import com.shop.framework.web.LoginUser;
import com.shop.marketing.common.audit.MarketingAuditService;
import com.shop.marketing.support.WebIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 商户端活动：提交审核（草稿/驳回 → 待审核）。 */
@RestController
@RequestMapping("/merchant/activities")
@RequiredArgsConstructor
public class MerchantActivityController {

    private final MarketingAuditService auditService;

    @PostMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id) {
        LoginUser merchant = WebIdentity.requireMerchant();
        auditService.submitActivity(id, merchant.getMerchantId());
        return Result.success();
    }
}
