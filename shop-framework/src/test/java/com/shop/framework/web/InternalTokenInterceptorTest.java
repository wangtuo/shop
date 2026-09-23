package com.shop.framework.web;

import com.shop.common.constant.SecurityHeaders;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务间 X-Internal-Token 拦截器单测（C-1）：无 token/错 token 拒绝 401，正确 token 放行。
 */
class InternalTokenInterceptorTest {

    private static final String TOKEN = "test-internal-token";

    private InternalTokenInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private final Object handler = new Object();

    @BeforeEach
    void setUp() {
        interceptor = new InternalTokenInterceptor(TOKEN);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test
    void 无token_返回401并拒绝() throws Exception {
        boolean pass = interceptor.preHandle(request, response, handler);
        assertFalse(pass);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void 错误token_返回401并拒绝() throws Exception {
        request.addHeader(SecurityHeaders.INTERNAL_TOKEN, "forged-token");
        boolean pass = interceptor.preHandle(request, response, handler);
        assertFalse(pass);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void 空白token_返回401并拒绝() throws Exception {
        request.addHeader(SecurityHeaders.INTERNAL_TOKEN, "   ");
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    @Test
    void 正确token_放行() throws Exception {
        request.addHeader(SecurityHeaders.INTERNAL_TOKEN, TOKEN);
        assertTrue(interceptor.preHandle(request, response, handler));
    }
}
