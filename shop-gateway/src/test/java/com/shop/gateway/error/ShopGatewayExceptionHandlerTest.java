package com.shop.gateway.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.gateway.filter.GatewayRequestPrepareFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShopGatewayExceptionHandler} 故障分类与包裹体契约（R-B3 验收）：
 * 429→10007、503→10008、504→10010、其他 500→10009、404→10004；
 * 响应体严格三字段且不得泄漏 IP/端口/异常原文。
 */
class ShopGatewayExceptionHandlerTest {

    private final ShopGatewayExceptionHandler handler = new ShopGatewayExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode handle(Throwable ex) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/orders/123").build());
        handler.handle(exchange, ex).block();
        return readEnvelope(exchange);
    }

    private JsonNode readEnvelope(MockServerWebExchange exchange) {
        try {
            String body = exchange.getResponse().getBodyAsString().block();
            JsonNode node = mapper.readTree(body);
            assertThat(node.fieldNames()).toIterable()
                    .containsExactly("code", "message", "data");
            assertThat(node.get("data").isNull()).isTrue();
            return node;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 限流429_包裹10007() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/orders").build());
        exchange.getAttributes().put(GatewayRequestPrepareFilter.REQUEST_ID_ATTR, "req-429");

        handler.handle(exchange, new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(GatewayRequestPrepareFilter.REQUEST_ID_HEADER))
                .isEqualTo("req-429");
        assertThat(readEnvelope(exchange).get("code").asInt()).isEqualTo(10007);
    }

    @Test
    void 连接拒绝_503包裹10008_且无IP端口泄漏() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/orders").build());
        // PERF §3 的真实裸异常形态：/10.244.0.114:8084 不得出现在响应体
        Throwable ex = new ConnectException(
                "io.netty.channel.AbstractChannel$AnnotatedConnectException: Connection refused: /10.244.0.114:8084");

        handler.handle(exchange, ex).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        String body = exchange.getResponse().getBodyAsString().block();
        JsonNode node = readEnvelope(exchange);
        assertThat(node.get("code").asInt()).isEqualTo(10008);
        assertThat(body).doesNotContain("10.244", "8084", "Netty", "ConnectException");
    }

    @Test
    void 响应超时_504包裹10010_且无IP泄漏() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/pay/pays").build());
        handler.handle(exchange, new TimeoutException("read timeout to /10.244.0.9:8085 after 5000ms")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(readEnvelope(exchange).get("code").asInt()).isEqualTo(10010);
        assertThat(body).doesNotContain("10.244", "8085", "timeout");
    }

    @Test
    void 未知500_包裹10009() {
        JsonNode node = handle(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "boom /1.2.3.4:9"));
        assertThat(node.get("code").asInt()).isEqualTo(10009);
    }

    @Test
    void 无路由404_包裹10004() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/not-exist").build());
        handler.handle(exchange, new ResponseStatusException(HttpStatus.NOT_FOUND, "No route")).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(readEnvelope(exchange).get("code").asInt()).isEqualTo(10004);
    }

    @Test
    void 状态500但cause链为连接拒绝_升级为503() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/orders").build());
        Throwable wrapped = new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "connect failed", new ConnectException("refused /10.9.9.9:8084"));

        handler.handle(exchange, wrapped).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(readEnvelope(exchange).get("code").asInt()).isEqualTo(10008);
    }
}
