package com.shop.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ShopRequestRateLimiterGatewayFilterFactory} 回归：
 * W7 CHAOS 实证官方 RequestRateLimiter deny 时空体 429，R-B4 要求 429+10007 包裹体。
 */
class ShopRequestRateLimiterGatewayFilterFactoryTest {

    private final KeyResolver keyResolver = exchange -> Mono.just("u:1001");

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RateLimiter<Object> limiterReturning(RateLimiter.Response response) {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.isAllowed(any(), any())).thenReturn(Mono.just(response));
        return limiter;
    }

    private ShopRequestRateLimiterGatewayFilterFactory factory(RateLimiter<?> limiter) {
        return new ShopRequestRateLimiterGatewayFilterFactory(limiter, keyResolver);
    }

    @Test
    void 放行决策_透传过滤器链并回写限流头() {
        RateLimiter.Response decision = new RateLimiter.Response(true, Map.of(
                org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.REMAINING_HEADER, "39",
                org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.REPLENISH_RATE_HEADER, "20"));
        ShopRequestRateLimiterGatewayFilterFactory f = factory(limiterReturning(decision));
        GatewayFilter filter = f.apply(new ShopRequestRateLimiterGatewayFilterFactory.Config());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/order/orders").build());
        GatewayFilterChain chain = ex -> Mono.empty();

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.REMAINING_HEADER))
                .isEqualTo("39");
    }

    @Test
    void 限流拒绝_429且写出10007统一包裹体_非空体() {
        RateLimiter.Response decision = new RateLimiter.Response(false, Map.of(
                org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.REMAINING_HEADER, "0",
                org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.BURST_CAPACITY_HEADER, "40"));
        ShopRequestRateLimiterGatewayFilterFactory f = factory(limiterReturning(decision));
        GatewayFilter filter = f.apply(new ShopRequestRateLimiterGatewayFilterFactory.Config());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/user/auth/login").build());
        GatewayFilterChain chain = ex -> {
            throw new AssertionError("deny 时不得继续过滤器链");
        };

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter.BURST_CAPACITY_HEADER))
                .isEqualTo("40");
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"code\":10007").contains("\"message\"").contains("\"data\":null");
    }

    @Test
    void 解析不到限流键_放行且不查询限流器() {
        @SuppressWarnings("rawtypes")
        RateLimiter limiter = mock(RateLimiter.class);
        KeyResolver emptyKey = exchange -> Mono.empty();
        ShopRequestRateLimiterGatewayFilterFactory f =
                new ShopRequestRateLimiterGatewayFilterFactory(limiter, emptyKey);
        GatewayFilter filter = f.apply(new ShopRequestRateLimiterGatewayFilterFactory.Config());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/product/products").build());
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(limiter, never()).isAllowed(any(), any());
        verify(chain).filter(eq(exchange));
    }
}
