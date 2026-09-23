package com.shop.pay.support;

import com.shop.common.exception.BizException;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * H-1：平台身份校验工具（userType=2 放行，普通用户/商户/未登录拒绝）。
 */
class WebIdentityTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void requirePlatformAdmin_普通用户_403() {
        UserContext.set(LoginUser.builder().userId(9L).userType(0).build());
        BizException ex = assertThrows(BizException.class, WebIdentity::requirePlatformAdmin);
        assertEquals(10003, ex.getCode());
    }

    @Test
    void requirePlatformAdmin_商户_403() {
        UserContext.set(LoginUser.builder().userId(50L).userType(1).merchantId(2002L).build());
        assertThrows(BizException.class, WebIdentity::requirePlatformAdmin);
    }

    @Test
    void requirePlatformAdmin_平台_通过() {
        UserContext.set(LoginUser.builder().userId(1L).userType(2).build());
        WebIdentity.requirePlatformAdmin();
    }

    @Test
    void requireUser_未登录_401() {
        BizException ex = assertThrows(BizException.class, WebIdentity::requireUser);
        assertEquals(10002, ex.getCode());
    }
}
