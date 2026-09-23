package com.shop.aftersale.support;

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

    /**
     * 平台运营鉴权，非 userType=2 一律 403。
     *
     * <p>仅做身份校验、不消费操作人信息时使用；需要记录真实操作人（如仲裁 operatorId）
     * 必须用 {@link #requirePlatformAdminUser()}，禁止再以 0L 充当平台运营 ID（O5）。</p>
     */
    public static void requirePlatformAdmin() {
        requirePlatformAdminUser();
    }

    /**
     * 平台运营鉴权并返回登录用户：非 userType=2 抛 403，未登录抛 UNAUTHORIZED。
     * 返回的 {@link LoginUser#getUserId()} 即真实平台运营 ID，用于状态日志/退款单操作人留痕。
     */
    public static LoginUser requirePlatformAdminUser() {
        LoginUser user = UserContext.get();
        if (user.getUserType() == null || user.getUserType() != USER_TYPE_PLATFORM) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅平台运营可访问该资源");
        }
        return user;
    }
}
