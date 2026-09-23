package com.shop.framework.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R4-23：{@link AuthInterceptor} 必须识别类级 {@link Anonymous}——MQ 消费/调度
 * 线程经 Feign 调用 /inner 控制器时不带 X-User-Id，类级注解是内部端点放行的唯一
 * 形态（内部端点的真实鉴权由 InternalTokenInterceptor 校验 X-Internal-Token 承担）。
 */
class AuthInterceptorTest {

    @Anonymous
    @SuppressWarnings("unused")
    static class AnonymousControllerAnnotated {
        public void handle() {
        }
    }

    @SuppressWarnings("unused")
    static class ProtectedController {
        public void handle() {
        }
    }

    private final AuthInterceptor interceptor = new AuthInterceptor();

    private HttpServletRequest requestWithoutUserHeaders() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(com.shop.common.constant.SecurityHeaders.USER_ID)).thenReturn(null);
        return request;
    }

    private HttpServletRequest requestWithUserHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(com.shop.common.constant.SecurityHeaders.USER_ID)).thenReturn("1001");
        when(request.getHeader(com.shop.common.constant.SecurityHeaders.USER_NAME)).thenReturn(null);
        when(request.getHeader(com.shop.common.constant.SecurityHeaders.USER_TYPE)).thenReturn(null);
        when(request.getHeader(com.shop.common.constant.SecurityHeaders.MERCHANT_ID)).thenReturn(null);
        return request;
    }

    private HandlerMethod handlerMethod(Class<?> controllerClass) throws Exception {
        Object bean = controllerClass.getDeclaredConstructor().newInstance();
        return new HandlerMethod(bean, "handle");
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void classLevelAnonymous_passesWithoutUserHeader() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        boolean allowed = interceptor.preHandle(requestWithoutUserHeaders(), response,
                handlerMethod(AnonymousControllerAnnotated.class));
        assertTrue(allowed, "类级 @Anonymous 端点在无 X-User-Id 时必须放行（MQ/调度线程内部调用，R4-23）");
    }

    @Test
    void nonAnonymous_withoutUserHeader_rejects401() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        boolean allowed = interceptor.preHandle(requestWithoutUserHeaders(), response,
                handlerMethod(ProtectedController.class));
        assertFalse(allowed, "非 @Anonymous 端点无 X-User-Id 必须 401");
    }

    @Test
    void nonAnonymous_withUserHeader_passes() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        boolean allowed = interceptor.preHandle(requestWithUserHeader(), response,
                handlerMethod(ProtectedController.class));
        assertTrue(allowed, "非 @Anonymous 端点携带 X-User-Id 必须放行");
    }
}
