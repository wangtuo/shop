package com.shop.settlement.support;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;

/**
 * HTTP 身份鉴权工具：商户端 / 平台端身份校验。
 */
public final class WebIdentity {

    /** 平台运营 userType=2（CONTRACTS.md §4） */
    public static final int USER_TYPE_PLATFORM = 2;

    private WebIdentity() {
    }

    /** 获取当前登录商户ID，非商户身份抛 FORBIDDEN。 */
    public static long requireMerchantId() {
        LoginUser user = UserContext.get();
        if (user.getMerchantId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅商户可访问该资源");
        }
        return user.getMerchantId();
    }

    /** 平台运营鉴权。 */
    public static void requirePlatformAdmin() {
        LoginUser user = UserContext.get();
        if (user.getUserType() == null || user.getUserType() != USER_TYPE_PLATFORM) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台运营可访问该资源");
        }
    }
}
