package com.shop.gateway.filter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.annotation.NonNull;

import java.util.UUID;

/**
 * 请求 ID 过滤器（C14/R-B3），在所有网关路由过滤器之外（WebFilter 层）：
 * 为每个请求确定 {@code X-Request-Id}（透传入站值或网关注入 UUID），写入响应头并放入
 * exchange 属性供 {@code ShopGatewayExceptionHandler} 复用。
 *
 * <p>历史上本过滤器还用 {@code ServerHttpResponseDecorator.setComplete} 在限流 429 空响应上
 * 补包裹体；W7 CHAOS 实证该路径补写被 reactor-netty 吞掉（content-length=0，content-type
 * 被改成 json 却无体，比纯空体更糟）。限流 deny 的统一包裹体已由
 * {@link com.shop.gateway.ratelimit.ShopRequestRateLimiterGatewayFilterFactory}
 * 在拒绝决策点直接 writeWith，本过滤器不再装饰响应，避免「半写入」响应。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayRequestPrepareFilter implements WebFilter {

    public static final String REQUEST_ID_ATTR = "shopGatewayRequestId";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        String requestId = resolveRequestId(exchange);
        exchange.getAttributes().put(REQUEST_ID_ATTR, requestId);
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(REQUEST_ID_HEADER, requestId);
        return chain.filter(exchange);
    }

    /** 透传入站 X-Request-Id（链路追踪场景由上游生成），缺失时网关注入短 UUID。 */
    private String resolveRequestId(ServerWebExchange exchange) {
        String inbound = exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER);
        if (inbound != null && !inbound.isBlank()) {
            return inbound.trim();
        }
        if (inbound == null) {
            // Spring 6 HttpHeaders 未定义 TRACE_PARENT 常量，按 W3C 规范用小写规范名取值。
            String trace = exchange.getRequest().getHeaders().getFirst("traceparent");
            if (trace != null && !trace.isBlank()) {
                // traceparent: 00-<trace-id>-<span-id>-flags；取 trace-id 段，不可用则 UUID。
                String[] parts = trace.split("-");
                if (parts.length >= 2 && !parts[1].isBlank()) {
                    return parts[1];
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
