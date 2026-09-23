package com.shop.framework.web;

import com.shop.common.constant.SecurityHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 服务间调用认证拦截器（C-1 纵深防御）：仅拦截 {@code /inner/**}（相对 context-path 的路径），
 * 请求头 {@code X-Internal-Token} 必须等于配置值 {@code shop.internal.token}，否则 401。
 *
 * <p>该头只可能由 Feign 侧 {@code FeignRequestInterceptor} 注入；网关对外部流量无条件剥离该头，
 * 且 /inner/** 在网关直接 404，因此外部请求无法触达内部端点。本拦截器在 {@link AuthInterceptor}
 * 之前执行，不改变 @Anonymous 既有放行语义（通过本拦截后仍由 AuthInterceptor 决定登录身份要求）。
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    private final String expectedToken;

    public InternalTokenInterceptor(
            @Value("${shop.internal.token:dev-local-only-internal-token}") String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String token = request.getHeader(SecurityHeaders.INTERNAL_TOKEN);
        if (token == null || token.isBlank() || !expectedToken.equals(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
