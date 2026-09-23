package com.shop.framework.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 入口链路 MDC 过滤器（O1/C50），优先级 HIGHEST（先于 AuthInterceptor 与 Micrometer
 * observation 过滤器）：
 * <ol>
 *   <li>读入站 {@code X-Request-Id}/{@code X-Trace-Id}，白名单 {@code [A-Za-z0-9-]{8,64}}
 *       采纳（防日志注入/CRLF 伪造），缺失或非法时各自生成 32 位 UUID（去横线）；</li>
 *   <li>写 MDC：traceId、requestId、clientIp；userId 由 AuthInterceptor 鉴权成功后补写；</li>
 *   <li>finally 无条件清理本过滤器写入的全部键——Tomcat 线程池复用，残留 userId/traceId
 *       会造成请求间串号。</li>
 * </ol>
 *
 * <p>引入 micrometer-tracing-bridge-otel 后，bridge 在请求 span 生命周期内自行管理
 * traceId/spanId MDC；本过滤器位于最外层，finally 清理发生在 bridge 恢复其 MDC 之后，
 * 不影响 bridge；无 bridge 时 {@code %} 退化为 "-"}，日志不报错。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceMdcFilter extends OncePerRequestFilter {

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_CLIENT_IP = "clientIp";

    /** 入站 ID 白名单：仅字母数字与短横线、8-64 位，挡掉换行/空格/括号等日志注入字符。 */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private static final String UNKNOWN_IP = "unknown";

    /** 生成 32 位无横线 ID（UUID hex，长度 32，同时兼容 OTel traceId 格式）。 */
    public static String generateId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 白名单校验：合法原样返回，否则返回 null（调用方生成）。 */
    public static String acceptOrNull(String inbound) {
        if (inbound == null) {
            return null;
        }
        String trimmed = inbound.trim();
        return SAFE_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = acceptOrNull(request.getHeader(HEADER_REQUEST_ID));
        if (requestId == null) {
            requestId = generateId();
        }
        String traceId = acceptOrNull(request.getHeader(HEADER_TRACE_ID));
        if (traceId == null) {
            traceId = generateId();
        }
        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_CLIENT_IP, clientIp(request));
        try {
            chain.doFilter(request, response);
        } finally {
            clearRequestMdc();
        }
    }

    /**
     * 消费端 MQ 线程恢复链路上下文（MqConsumerRegistrar 调用）；只放 traceId/requestId，
     * 消费线程无 HTTP 身份/IP 概念，消费结束同样必须 {@link #clearRequestMdc()}。
     */
    public static void restoreMqTrace(String traceId, String requestId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
        if (requestId != null && !requestId.isBlank()) {
            MDC.put(MDC_REQUEST_ID, requestId);
        }
    }

    /** 清理请求/消费上下文的全部 MDC 键（含 AuthInterceptor 补写的 userId）。 */
    public static void clearRequestMdc() {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
        MDC.remove(MDC_REQUEST_ID);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_CLIENT_IP);
    }

    /** XFF 首段（网关已清洗外部伪造 XFF）→ X-Real-IP → servlet 对端地址。 */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty() && !UNKNOWN_IP.equalsIgnoreCase(first)) {
                return first;
            }
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank() && !UNKNOWN_IP.equalsIgnoreCase(real)) {
            return real.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : UNKNOWN_IP;
    }
}
