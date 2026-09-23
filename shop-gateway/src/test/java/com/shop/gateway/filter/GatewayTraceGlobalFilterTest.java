package com.shop.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GatewayTraceGlobalFilter：回显 X-Request-Id、向下游透传可信 ID、非法入站 ID 重生成。
 */
class GatewayTraceGlobalFilterTest {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final GatewayTraceGlobalFilter filter = new GatewayTraceGlobalFilter();

    @Test
    void generatesIdsAndEchoesAndPropagatesWhenHeadersAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/1").build());
        AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, ex -> {
                    downstream.set(ex.getRequest().getHeaders());
                    return Mono.empty();
                }))
                .verifyComplete();

        String echoed = exchange.getResponse().getHeaders().getFirst(GatewayTraceGlobalFilter.HEADER_REQUEST_ID);
        assertNotNull(echoed);
        assertTrue(SAFE_ID.matcher(echoed).matches());
        assertEquals(echoed, downstream.get().getFirst(GatewayTraceGlobalFilter.HEADER_REQUEST_ID));
        String traceId = downstream.get().getFirst(GatewayTraceGlobalFilter.HEADER_TRACE_ID);
        assertNotNull(traceId);
        assertTrue(SAFE_ID.matcher(traceId).matches());
        // 异常处理器/429 包裹体复用的 exchange 属性同步为已校验 ID
        assertEquals(echoed, exchange.getAttribute(GatewayRequestPrepareFilter.REQUEST_ID_ATTR));
    }

    @Test
    void adoptsValidInboundIds() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/2")
                        .header(GatewayTraceGlobalFilter.HEADER_REQUEST_ID, "req-inbound-0001")
                        .header(GatewayTraceGlobalFilter.HEADER_TRACE_ID, "trace-inbound-0001")
                        .build());
        AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, ex -> {
                    downstream.set(ex.getRequest().getHeaders());
                    return Mono.empty();
                }))
                .verifyComplete();

        assertEquals("req-inbound-0001",
                exchange.getResponse().getHeaders().getFirst(GatewayTraceGlobalFilter.HEADER_REQUEST_ID));
        assertEquals("req-inbound-0001",
                downstream.get().getFirst(GatewayTraceGlobalFilter.HEADER_REQUEST_ID));
        assertEquals("trace-inbound-0001",
                downstream.get().getFirst(GatewayTraceGlobalFilter.HEADER_TRACE_ID));
    }

    @Test
    void regeneratesWhenInboundIdFailsWhitelist() {
        String injected = "bad id\nX-Fake: 1";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/3")
                        .header(GatewayTraceGlobalFilter.HEADER_REQUEST_ID, injected)
                        .build());
        AtomicReference<HttpHeaders> downstream = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange, ex -> {
                    downstream.set(ex.getRequest().getHeaders());
                    return Mono.empty();
                }))
                .verifyComplete();

        String echoed = exchange.getResponse().getHeaders().getFirst(GatewayTraceGlobalFilter.HEADER_REQUEST_ID);
        assertNotNull(echoed);
        assertTrue(SAFE_ID.matcher(echoed).matches());
        assertEquals(echoed, downstream.get().getFirst(GatewayTraceGlobalFilter.HEADER_REQUEST_ID));
        // 非法入站值绝不透传下游，且只有一个同名头（mutate 先 remove 再 set）
        assertEquals(1, downstream.get().getValuesAsList(GatewayTraceGlobalFilter.HEADER_REQUEST_ID).size());
        assertTrue(downstream.get().getValuesAsList(GatewayTraceGlobalFilter.HEADER_REQUEST_ID)
                .stream().noneMatch(injected::equals));
    }
}
