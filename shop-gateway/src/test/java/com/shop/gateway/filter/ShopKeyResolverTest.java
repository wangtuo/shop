package com.shop.gateway.filter;

import com.shop.common.constant.SecurityHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ShopKeyResolver} 三态主体解析。 */
class ShopKeyResolverTest {

    private final ShopKeyResolver resolver = new ShopKeyResolver();

    @Test
    void 已登录_按内部XUserId头取用户维度() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/orders")
                        .header(SecurityHeaders.USER_ID, "10086")
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                        .build());

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("u:10086")
                .verifyComplete();
    }

    @Test
    void 匿名_取XForwardedFor首段() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/product/products")
                        .header("X-Forwarded-For", "203.0.113.20, 10.1.0.1, 10.0.0.2")
                        .build());

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("ip:203.0.113.20")
                .verifyComplete();
    }

    @Test
    void 匿名且无XFF_回退真实TCP对端() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/product/products")
                        .remoteAddress(new InetSocketAddress("198.51.100.7", 51234))
                        .build());

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("ip:198.51.100.7")
                .verifyComplete();
    }

    @Test
    void 空白UserId头不当作登录态() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/product/products")
                        .header(SecurityHeaders.USER_ID, "  ")
                        .remoteAddress(new InetSocketAddress("198.51.100.8", 51234))
                        .build());

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("ip:198.51.100.8")
                .verifyComplete();
        assertThat(ShopKeyResolver.ANONYMOUS).isEqualTo("ip:unknown");
    }
}
