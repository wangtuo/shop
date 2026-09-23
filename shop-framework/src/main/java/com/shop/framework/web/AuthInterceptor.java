package com.shop.framework.web;

import com.shop.common.constant.SecurityHeaders;
import com.shop.framework.web.trace.TraceMdcFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 从网关透传头解析登录身份。{@link Anonymous} 接口放行；其余接口必须携带 X-User-Id。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser user = parse(request);
        if (user != null) {
            UserContext.set(user);
            // O1：鉴权成功后回填 MDC userId（仅在 user 非空时），由 TraceMdcFilter finally 清理。
            if (user.getUserId() != null) {
                MDC.put(TraceMdcFilter.MDC_USER_ID, String.valueOf(user.getUserId()));
            }
        }
        if (handler instanceof HandlerMethod handlerMethod) {
            boolean anonymous = handlerMethod.hasMethodAnnotation(Anonymous.class)
                    || handlerMethod.getBeanType().isAnnotationPresent(Anonymous.class);
            if (anonymous) {
                return true;
            }
        } else {
            return true;
        }
        if (user == null || user.getUserId() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    private LoginUser parse(HttpServletRequest request) {
        String userId = request.getHeader(SecurityHeaders.USER_ID);
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String userName = request.getHeader(SecurityHeaders.USER_NAME);
        if (userName != null && !userName.isBlank()) {
            userName = URLDecoder.decode(userName, StandardCharsets.UTF_8);
        }
        String userType = request.getHeader(SecurityHeaders.USER_TYPE);
        String merchantId = request.getHeader(SecurityHeaders.MERCHANT_ID);
        return LoginUser.builder()
                .userId(Long.valueOf(userId))
                .userName(userName)
                .userType(userType == null ? null : Integer.valueOf(userType))
                .merchantId(merchantId == null || merchantId.isBlank() ? null : Long.valueOf(merchantId))
                .build();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
