package com.shop.framework.feign;

import com.shop.common.constant.SecurityHeaders;
import com.shop.framework.web.LoginUser;
import com.shop.framework.web.UserContext;
import com.shop.framework.web.trace.TraceMdcFilter;
import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Feign 调用透传登录身份与链路头，保证服务间调用鉴权信息不丢失。
 */
public class FeignRequestInterceptor {

    /**
     * 服务间内部令牌（C-1）：对所有 Feign 请求注入 X-Internal-Token，
     * 下游 /inner/** 由 InternalTokenInterceptor 校验；网关对外部流量剥离该头。
     */
    @Bean
    public RequestInterceptor internalTokenPropagationInterceptor(
            @Value("${shop.internal.token:dev-local-only-internal-token}") String internalToken) {
        return template -> template.header(SecurityHeaders.INTERNAL_TOKEN, internalToken);
    }

    @Bean
    public RequestInterceptor loginUserPropagationInterceptor() {
        return template -> {
            LoginUser user = UserContext.getOrNull();
            if (user != null) {
                if (user.getUserId() != null) {
                    template.header(SecurityHeaders.USER_ID, String.valueOf(user.getUserId()));
                }
                if (user.getUserName() != null) {
                    template.header(SecurityHeaders.USER_NAME,
                            URLEncoder.encode(user.getUserName(), StandardCharsets.UTF_8));
                }
                if (user.getUserType() != null) {
                    template.header(SecurityHeaders.USER_TYPE, String.valueOf(user.getUserType()));
                }
                if (user.getMerchantId() != null) {
                    template.header(SecurityHeaders.MERCHANT_ID, String.valueOf(user.getMerchantId()));
                }
            }
        };
    }

    /**
     * 链路头透传（O1/C52）：把当前请求 MDC 中的 requestId/traceId 带给下游，
     * 使一次网关→多服务调用在各服务日志中可用同一 ID 串联。只在 MDC 有值时追加，
     * 异步/无入口上下文的 Feign 调用不产生伪造头。
     */
    @Bean
    public RequestInterceptor tracePropagationInterceptor() {
        return template -> {
            String requestId = MDC.get(TraceMdcFilter.MDC_REQUEST_ID);
            if (requestId != null && !requestId.isBlank()) {
                template.header(TraceMdcFilter.HEADER_REQUEST_ID, requestId);
            }
            String traceId = MDC.get(TraceMdcFilter.MDC_TRACE_ID);
            if (traceId != null && !traceId.isBlank()) {
                template.header(TraceMdcFilter.HEADER_TRACE_ID, traceId);
            }
        };
    }
}
