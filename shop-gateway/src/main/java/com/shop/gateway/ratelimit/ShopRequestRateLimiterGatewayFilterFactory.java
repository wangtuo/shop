package com.shop.gateway.ratelimit;

import com.shop.common.exception.ErrorCode;
import com.shop.gateway.error.GatewayErrorBody;
import lombok.Getter;
import lombok.Setter;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 生产级限流过滤器工厂（替换官方 {@code RequestRateLimiter}）：判定逻辑完全复用
 * {@link ShopRedisRateLimiter}（三维度桶 / fail-closed），唯一区别是 <b>被限流/Redis 故障
 * fail-closed 拒绝时，除 {@code 429} 与 X-RateLimit-* 头外，强制写出统一业务包裹体
 * {@code code=10007}</b>，而不是官方过滤器的「setStatusCode(429) + 空体 setComplete()」。
 *
 * <p>W7 CHAOS 实证：官方空体 429 无法被客户端按统一契约解析（R-B4 验收要求
 * 「429+10007 包裹体」）；曾尝试用 {@code ServerHttpResponseDecorator.setComplete}
 * 事后补体，但该路径下 reactor-netty 已按无体响应提交（content-length=0），补写被吞。
 * 故在过滤器内拿到 deny 决策的同一点直接 writeWith 包裹体（与 JwtAuth 401/404 直写同型，
 * 生产验证可送达）。</p>
 *
 * <p>yml 用法与官方一致（参数名 keyResolver/rateLimiter/statusCode）：
 * <pre>{@code
 * - name: ShopRequestRateLimiter
 *   args:
 *     keyResolver: "#{@shopKeyResolver}"
 *     rateLimiter: "#{@shopRedisRateLimiter}"
 *     statusCode: TOO_MANY_REQUESTS
 * }</pre>
 */
@Component
public class ShopRequestRateLimiterGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ShopRequestRateLimiterGatewayFilterFactory.Config> {

    /** KeyResolver 解析为空键时的占位；默认与官方一致：不拒绝，继续链路由业务侧鉴权处理。 */
    private static final String EMPTY_KEY = "______EMPTY_KEY______";

    private final RateLimiter<?> defaultRateLimiter;
    private final KeyResolver defaultKeyResolver;

    public ShopRequestRateLimiterGatewayFilterFactory(
            RateLimiter<?> defaultRateLimiter, KeyResolver defaultKeyResolver) {
        super(Config.class);
        this.defaultRateLimiter = defaultRateLimiter;
        this.defaultKeyResolver = defaultKeyResolver;
    }

    @Override
    @SuppressWarnings("unchecked")
    public GatewayFilter apply(Config config) {
        KeyResolver keyResolver = config.getKeyResolver() != null
                ? config.getKeyResolver() : defaultKeyResolver;
        RateLimiter<Object> rateLimiter = (RateLimiter<Object>)
                (config.getRateLimiter() != null ? config.getRateLimiter() : defaultRateLimiter);
        HttpStatus deniedStatus = config.getStatusCode() != null
                ? config.getStatusCode() : HttpStatus.TOO_MANY_REQUESTS;

        return (exchange, chain) -> keyResolver.resolve(exchange).defaultIfEmpty(EMPTY_KEY)
                .flatMap(key -> {
                    if (EMPTY_KEY.equals(key)) {
                        return chain.filter(exchange);
                    }
                    String routeId = exchange.getAttribute(
                            ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR);
                    return decide(rateLimiter, exchange, chain, routeId, key, deniedStatus);
                });
    }

    private Mono<Void> decide(RateLimiter<Object> rateLimiter,
                              ServerWebExchange exchange,
                              org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                              String routeId, String key, HttpStatus deniedStatus) {
        return rateLimiter.isAllowed(routeId, key)
                .flatMap(response -> {
                    applyRateLimitHeaders(exchange.getResponse(), response);
                    if (response.isAllowed()) {
                        return chain.filter(exchange);
                    }
                    return writeDeniedEnvelope(exchange, deniedStatus);
                });
    }

    private void applyRateLimitHeaders(ServerHttpResponse response, RateLimiter.Response decision) {
        if (decision.getHeaders() == null) {
            return;
        }
        decision.getHeaders().forEach((name, value) -> {
            if (name != null && value != null) {
                response.getHeaders().add(name, value);
            }
        });
    }

    /**
     * 拒绝响应：429 + X-RateLimit-* + 统一包裹体（code=10007）。
     * 必须在 setComplete 之前 writeWith，reactor-netty 才会连同 body 一起提交响应头。
     */
    private Mono<Void> writeDeniedEnvelope(ServerWebExchange exchange, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] payload = GatewayErrorBody.envelope(
                ErrorCode.TOO_MANY_REQUESTS.getCode(),
                ErrorCode.TOO_MANY_REQUESTS.getMessage());
        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    @Getter
    @Setter
    public static class Config {
        private KeyResolver keyResolver;
        @SuppressWarnings("rawtypes")
        private RateLimiter rateLimiter;
        private HttpStatus statusCode = HttpStatus.TOO_MANY_REQUESTS;
    }
}
