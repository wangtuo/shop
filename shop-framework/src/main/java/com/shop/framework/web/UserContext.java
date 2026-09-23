package com.shop.framework.web;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;

/**
 * 基于 ThreadLocal 的登录上下文。Feign 拦截器、Service 均可读取。
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser getOrNull() {
        return HOLDER.get();
    }

    public static LoginUser get() {
        LoginUser user = HOLDER.get();
        if (user == null || user.getUserId() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    public static Long getUserId() {
        return get().getUserId();
    }

    public static Long getUserIdOrNull() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static Long getMerchantIdOrNull() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getMerchantId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
