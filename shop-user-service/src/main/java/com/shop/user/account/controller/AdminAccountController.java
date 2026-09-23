package com.shop.user.account.controller;

import com.shop.api.user.enums.UserTypes;
import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.common.result.Result;
import com.shop.framework.audit.AuditLog;
import com.shop.framework.web.UserContext;
import com.shop.user.account.dto.AdminCreateAccountRequest;
import com.shop.user.account.service.AdminAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营账号管理接口（C-2 修复配套）。
 * 商户/运营账号不能自助注册，统一由在岗平台运营开通；
 * 网关对 /users/admin/** 要求登录态，这里再做 userType=2 服务端鉴权（纵深防御）。
 */
@RestController
@RequestMapping("/users/admin/accounts")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminAccountService adminAccountService;

    /** 开通商户登录账号，返回 userId（即后续清算入驻用的 merchantId）。 */
    @PostMapping("/merchant")
    @AuditLog(action = "USER_ACCOUNT_OPEN", targetType = "USER",
            targetIdSpEL = "#request.username", captureArgs = true)
    public Result<Long> createMerchant(@Valid @RequestBody AdminCreateAccountRequest request) {
        requirePlatformAdmin();
        return Result.success(adminAccountService.createMerchantAccount(request));
    }

    /** 新增平台运营账号（首个平台账号走一次性引导接口 /auth/bootstrap-admin）。 */
    @PostMapping("/admin")
    @AuditLog(action = "USER_ACCOUNT_OPEN", targetType = "USER",
            targetIdSpEL = "#request.username", captureArgs = true)
    public Result<Long> createAdmin(@Valid @RequestBody AdminCreateAccountRequest request) {
        requirePlatformAdmin();
        return Result.success(adminAccountService.createPlatformAccount(request));
    }

    private static void requirePlatformAdmin() {
        Integer userType = UserContext.get().getUserType();
        if (userType == null || userType != UserTypes.PLATFORM) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台运营可访问该资源");
        }
    }
}
