package com.shop.framework.feign;

import feign.Request;
import feign.RequestTemplate;
import feign.Target;
import org.springframework.cloud.openfeign.FeignClient;

/**
 * 从 Feign 调用上下文解析 clientName（即 {@code @FeignClient} 的 contextId/name），
 * 供错误文案、熔断器与舱壁按下游服务隔离使用。绝不解析/拼接 URL 中的实例 IP。
 */
final class FeignClientNames {

    private static final String UNKNOWN = "unknown";

    private FeignClientNames() {
    }

    /** InvocationHandlerFactory 阶段：target 一定持有接口类型。 */
    static String resolve(Target<?> target) {
        if (target != null) {
            String name = fromType(target.type());
            if (name != null) {
                return name;
            }
            if (target.name() != null && !target.name().isEmpty()) {
                return target.name();
            }
        }
        return UNKNOWN;
    }

    /** Client/Decoder 阶段：优先取模板上的 target，退回 URL 中的服务名（LB 形态为 http://serviceName/...）。 */
    static String fromRequest(Request request) {
        if (request == null) {
            return UNKNOWN;
        }
        RequestTemplate template = request.requestTemplate();
        if (template != null) {
            Target<?> target = template.feignTarget();
            if (target != null) {
                String name = fromType(target.type());
                if (name != null) {
                    return name;
                }
                if (target.name() != null && !target.name().isEmpty()) {
                    return target.name();
                }
            }
        }
        return hostOf(request.url());
    }

    static String fromType(Class<?> type) {
        if (type == null) {
            return null;
        }
        FeignClient annotation = type.getAnnotation(FeignClient.class);
        if (annotation == null) {
            return null;
        }
        if (!annotation.contextId().isEmpty()) {
            return annotation.contextId();
        }
        if (!annotation.value().isEmpty()) {
            return annotation.value();
        }
        if (!annotation.name().isEmpty()) {
            return annotation.name();
        }
        return null;
    }

    /** 仅取 authority 段作为 clientName，不保留路径/端口之外的任何实例信息。 */
    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) {
            return UNKNOWN;
        }
        String rest = url;
        int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest.isEmpty() ? UNKNOWN : rest;
    }
}
