package com.shop.marketing.support;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;

/**
 * HTTP 身份鉴权工具：C 端登录身份 / 平台运营身份校验（网关注入 X-User-* 头）。
 */
public final class WebIdentity {

    /** 平台运营 userType=2（CONTRACTS.md §4） */
    public static final int USER_TYPE_PLATFORM = 2;

    /** 商户 userType=1（design.md 2.1.1） */
    public static final int USER_TYPE_MERCHANT = 1;

    private WebIdentity() {
    }

    /** 当前登录用户（未登录抛 UNAUTHORIZED）。 */
    public static LoginUser requireUser() {
        return UserContext.get();
    }

    /** 平台运营鉴权，非 userType=2 一律 403。 */
    public static void requirePlatformAdmin() {
        LoginUser user = UserContext.get();
        if (user.getUserType() == null || user.getUserType() != USER_TYPE_PLATFORM) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台运营可访问该资源");
        }
    }

    /**
     * 商户鉴权：userType=1 且网关注入了 merchantId，否则 403
     * （网关尚未注入商户身份时按 B2 残留口径直接拒绝，不阻塞平台审核主路径）。
     */
    public static LoginUser requireMerchant() {
        LoginUser user = UserContext.get();
        if (user.getUserType() == null || user.getUserType() != USER_TYPE_MERCHANT
                || user.getMerchantId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅商户可访问该资源");
        }
        return user;
    }
}
