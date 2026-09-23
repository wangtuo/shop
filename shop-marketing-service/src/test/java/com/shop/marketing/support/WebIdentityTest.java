package com.shop.marketing.support;

import com.shop.common.exception.BizException;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebIdentityTest {

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void 未登录访问抛401() {
        BizException ex = assertThrows(BizException.class, WebIdentity::requirePlatformAdmin);
        assertEquals(10002, ex.getCode());
    }

    @Test
    void 普通消费者访问平台资源抛403() {
        UserContext.set(LoginUser.builder().userId(1L).userType(0).build());
        BizException ex = assertThrows(BizException.class, WebIdentity::requirePlatformAdmin);
        assertEquals(10003, ex.getCode());
    }

    @Test
    void 商户身份访问平台资源抛403() {
        UserContext.set(LoginUser.builder().userId(1L).userType(1).merchantId(9L).build());
        BizException ex = assertThrows(BizException.class, WebIdentity::requirePlatformAdmin);
        assertEquals(10003, ex.getCode());
    }

    @Test
    void 平台运营身份通过() {
        UserContext.set(LoginUser.builder().userId(1L).userType(2).build());
        WebIdentity.requirePlatformAdmin();
    }
}
