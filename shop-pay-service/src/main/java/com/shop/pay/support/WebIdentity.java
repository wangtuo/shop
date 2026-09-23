package com.shop.pay.support;

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
}
