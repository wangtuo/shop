package com.shop.common.constant;

/**
 * 网关 -> 内部服务透传的身份请求头。
 * 外部流量进入网关时这些头会被剥离，鉴权通过后由网关注入，内部服务只信任网关。
 */
public final class SecurityHeaders {

    private SecurityHeaders() {
    }

    public static final String USER_ID = "X-User-Id";
    public static final String USER_NAME = "X-User-Name";
    public static final String USER_TYPE = "X-User-Type";
    public static final String MERCHANT_ID = "X-Merchant-Id";
    public static final String TRACE_ID = "X-Trace-Id";
    /**
     * 服务间 Feign 调用令牌头。仅 Feign 客户端拦截器可注入；网关对外部流量无条件剥离，
     * /inner/** 端点校验该头，防止内网接口被外部直连或伪造。
     */
    public static final String INTERNAL_TOKEN = "X-Internal-Token";
}
