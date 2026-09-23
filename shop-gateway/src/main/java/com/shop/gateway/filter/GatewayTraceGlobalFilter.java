package com.shop.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网关链路 ID 全局过滤器（O1/C51），顺序 {@code HIGHEST + 200}：在 {@link JwtAuthGlobalFilter}
 * （HIGHEST+100，剥离外部伪造头）之后、路由过滤器之前执行。
 *
 * <ol>
 *   <li>入站 {@code X-Request-Id}/{@code X-Trace-Id} 只有满足白名单
 *       {@code [A-Za-z0-9-]{8,64}} 才采纳（防日志注入/CRLF/伪造），否则各自新生成 32 位
 *       UUID（去横线）；</li>
 *   <li>经 mutate 向下游透传两个头（先移除入站同名头，杜绝重复头/未采纳值漏出）；</li>
 *   <li>响应头回写 X-Request-Id；同步刷新 {@link GatewayRequestPrepareFilter} 的 exchange
 *       属性，保证异常处理器与 429 包裹体使用同一个已校验 ID。</li>
 * </ol>
 * 与 {@link GatewayRequestPrepareFilter}（WebFilter 层，负责 429 包裹与初始响应头）共存：
 * 两层都 set 同一响应头（底层 headers 相同，后者覆盖），最终值以本过滤器校验结果为准。
 */
@Component
public class GatewayTraceGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = acceptOrGenerate(exchange.getRequest().getHeaders().getFirst(HEADER_REQUEST_ID));
        String traceId = acceptOrGenerate(exchange.getRequest().getHeaders().getFirst(HEADER_TRACE_ID));

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_REQUEST_ID);
                    headers.remove(HEADER_TRACE_ID);
                    headers.set(HEADER_REQUEST_ID, requestId);
                    headers.set(HEADER_TRACE_ID, traceId);
                })
                .build();

        exchange.getAttributes().put(GatewayRequestPrepareFilter.REQUEST_ID_ATTR, requestId);
        exchange.getResponse().getHeaders().set(HEADER_REQUEST_ID, requestId);

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    static String acceptOrGenerate(String inbound) {
        if (inbound != null) {
            String trimmed = inbound.trim();
            if (SAFE_ID.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public int getOrder() {
        // JwtAuthGlobalFilter: HIGHEST+100（先清洗伪造头）；本过滤器在其后注入可信头。
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }
}
