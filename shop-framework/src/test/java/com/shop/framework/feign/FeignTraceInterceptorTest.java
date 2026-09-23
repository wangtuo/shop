package com.shop.framework.feign;

import com.shop.framework.web.trace.TraceMdcFilter;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Feign 链路头透传（O1/C52）：MDC 有值时注入 X-Request-Id/X-Trace-Id，无值时不伪造。
 */
class FeignTraceInterceptorTest {

    private final FeignRequestInterceptor configuration = new FeignRequestInterceptor();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void propagatesTraceHeadersWhenMdcPresent() {
        MDC.put(TraceMdcFilter.MDC_REQUEST_ID, "req-feign-1234567890");
        MDC.put(TraceMdcFilter.MDC_TRACE_ID, "trace-feign-1234567890");

        RequestTemplate template = new RequestTemplate();
        configuration.tracePropagationInterceptor().apply(template);

        assertEquals("req-feign-1234567890",
                template.headers().get(TraceMdcFilter.HEADER_REQUEST_ID).iterator().next());
        assertEquals("trace-feign-1234567890",
                template.headers().get(TraceMdcFilter.HEADER_TRACE_ID).iterator().next());
    }

    @Test
    void leavesHeadersAbsentWhenMdcEmpty() {
        RequestTemplate template = new RequestTemplate();
        configuration.tracePropagationInterceptor().apply(template);
        assertNull(template.headers().get(TraceMdcFilter.HEADER_REQUEST_ID));
        assertNull(template.headers().get(TraceMdcFilter.HEADER_TRACE_ID));
    }
}
