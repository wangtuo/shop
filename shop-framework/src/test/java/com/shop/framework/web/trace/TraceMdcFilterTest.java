package com.shop.framework.web.trace;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TraceMdcFilter 三例：无 header 生成、带合法 header 采纳、请求结束清理（线程复用不串号）。
 */
class TraceMdcFilterTest {

    private final TraceMdcFilter filter = new TraceMdcFilter();

    @AfterEach
    void tearDown() {
        TraceMdcFilter.clearRequestMdc();
    }

    @Test
    void generatesIdsWhenHeadersAbsentAndClearsAfterCompletion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.9");
        AtomicReference<String> requestIdInChain = new AtomicReference<>();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                requestIdInChain.set(MDC.get(TraceMdcFilter.MDC_REQUEST_ID));
                traceIdInChain.set(MDC.get(TraceMdcFilter.MDC_TRACE_ID));
                assertEquals("10.0.0.9", MDC.get(TraceMdcFilter.MDC_CLIENT_IP));
                super.doFilter(req, res);
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(requestIdInChain.get());
        assertEquals(32, requestIdInChain.get().length(), "UUID 去横线应为 32 位");
        assertNotNull(traceIdInChain.get());
        assertEquals(32, traceIdInChain.get().length());
        // finally 必须清理：Tomcat 线程复用不得残留
        assertNull(MDC.get(TraceMdcFilter.MDC_REQUEST_ID));
        assertNull(MDC.get(TraceMdcFilter.MDC_TRACE_ID));
        assertNull(MDC.get(TraceMdcFilter.MDC_CLIENT_IP));
    }

    @Test
    void adoptsValidInboundHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceMdcFilter.HEADER_REQUEST_ID, "req-abc-1234567890");
        request.addHeader(TraceMdcFilter.HEADER_TRACE_ID, "trace-xyz-0987654321");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        AtomicReference<String> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                seen.set(MDC.get(TraceMdcFilter.MDC_REQUEST_ID) + "|"
                        + MDC.get(TraceMdcFilter.MDC_TRACE_ID) + "|"
                        + MDC.get(TraceMdcFilter.MDC_CLIENT_IP));
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("req-abc-1234567890|trace-xyz-0987654321|203.0.113.7", seen.get());
    }

    @Test
    void rejectsUnsafeInboundIdAndClearsUserIdOnCompletion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 含空格的非法 ID（日志注入/CRLF 形态同样不匹配白名单）→ 生成新 ID
        request.addHeader(TraceMdcFilter.HEADER_REQUEST_ID, "bad id");
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                String id = MDC.get(TraceMdcFilter.MDC_REQUEST_ID);
                assertNotNull(id);
                assertTrue(id.matches("[A-Za-z0-9-]{8,64}"));
                // 模拟 AuthInterceptor 回填 userId
                MDC.put(TraceMdcFilter.MDC_USER_ID, "9527");
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        // userId 由 filter finally 一并清掉，下一个复用线程的请求看不到上一个用户
        assertNull(MDC.get(TraceMdcFilter.MDC_REQUEST_ID));
        assertNull(MDC.get(TraceMdcFilter.MDC_USER_ID));
    }
}
