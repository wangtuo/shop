package com.shop.gateway.error;

import com.shop.common.exception.ErrorCode;
import com.shop.gateway.filter.GatewayRequestPrepareFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * 网关统一异常处理（C14/R-B3，WebFlux）。
 *
 * <p>优先级高于默认 {@code DefaultErrorWebExceptionHandler}（该 Bean 因本类存在而退避），
 * 把连接拒绝/连接过早关闭/下游 5xx/超时/无路由/限流等故障统一转换为业务包裹体
 * {@code {"code","message","data":null}} + {@code X-Request-Id}：
 * <ul>
 *   <li>429 限流 → {@code 10007}（TOO_MANY_REQUESTS）；</li>
 *   <li>503 下游不可达（Connect refused / 无可用实例 / PrematureClose）→ {@code 10008}；</li>
 *   <li>504 超时（response-timeout/读超时）→ {@code 10010}；</li>
 *   <li>其他 500 → {@code 10009}；404 无路由 → {@code 10004}。</li>
 * </ul>
 *
 * <p><b>不二次包裹</b>：下游业务服务正常返回的 {@code Result}（含 HTTP 200 的业务失败码，
 * 以及可被代理写出的响应体）不经过本处理器——只有网关侧抛异常（未产生任何响应）时才进入；
 * 因此 code/message/data 原样透传。message 一律使用枚举固定文案，绝不拼接异常原文，
 * PERF §3 中 {@code /10.244.x.x:8084} 这类 IP/端口/Netty 堆栈禁止出现在响应体。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ShopGatewayExceptionHandler implements ErrorWebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ShopGatewayExceptionHandler.class);
    private static final int MAX_CAUSE_DEPTH = 6;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // 已有字节（含部分下游响应）发出：无法改写成包裹体，交底层断连处理。
            return Mono.error(ex);
        }

        String requestId = exchange.getAttribute(GatewayRequestPrepareFilter.REQUEST_ID_ATTR);
        Classification c = classify(ex);

        // 完整故障细节只进服务端日志（含 IP/端口），不进入响应体。
        log.warn("gateway error requestId={} route={} httpStatus={} code={} type={}: {}",
                requestId,
                exchange.getAttribute("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRoute"),
                c.status, c.code, ex.getClass().getName(), ex.getMessage());

        response.setStatusCode(c.status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // 错误响应禁止被中间层缓存复用。
        response.getHeaders().setCacheControl("no-store");
        if (requestId != null) {
            response.getHeaders().set(GatewayRequestPrepareFilter.REQUEST_ID_HEADER, requestId);
        }
        byte[] payload = GatewayErrorBody.envelope(c.code, c.message);
        DataBuffer buffer = response.bufferFactory().wrap(payload);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private Classification classify(Throwable ex) {
        HttpStatusCode explicit = (ex instanceof ResponseStatusException rse) ? rse.getStatusCode() : null;
        boolean connectFailure = chainMatches(ex, this::isConnectFailure);
        boolean timeout = !connectFailure && chainMatches(ex, this::isTimeout);

        if (explicit != null) {
            int sc = explicit.value();
            if (sc == 429) {
                return Classification.of(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.TOO_MANY_REQUESTS);
            }
            if (connectFailure) {
                return Classification.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_FAIL);
            }
            if (timeout && sc >= 500) {
                return Classification.of(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.DEPENDENCY_TIMEOUT);
            }
            if (sc == 503) {
                return Classification.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_FAIL);
            }
            if (sc == 504) {
                return Classification.of(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.DEPENDENCY_TIMEOUT);
            }
            if (sc == 502) {
                return Classification.of(HttpStatus.BAD_GATEWAY, ErrorCode.DEPENDENCY_FAIL);
            }
            if (sc >= 500) {
                return Classification.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SYSTEM_ERROR);
            }
            if (sc == 404) {
                return Classification.of(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND);
            }
            // 其余 4xx（如空限流键 403、405）：保留 HTTP 语义，码段 1xxxx 末三位取 HTTP 状态。
            return new Classification(HttpStatusCode.valueOf(sc), 10000 + sc, "请求无法处理，请检查后重试");
        }

        if (connectFailure) {
            return Classification.of(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.DEPENDENCY_FAIL);
        }
        if (timeout) {
            return Classification.of(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.DEPENDENCY_TIMEOUT);
        }
        return Classification.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.SYSTEM_ERROR);
    }

    private boolean isConnectFailure(Throwable t) {
        if (t instanceof ConnectException
                || t instanceof PrematureCloseException) {
            return true;
        }
        String name = t.getClass().getName();
        // io.netty.channel.AbstractChannel$AnnotatedConnectException 是 private 嵌套类，
        // 无法直接 instanceof（且 Connect refused 的裸异常常已被包成 java.net.ConnectException）；
        // 改为类名后缀判定：AnnotatedConnectException、reactor.netty.channel.ConnectTimeoutException
        // （连接建立阶段超时按不可达处理）、Spring Cloud Gateway 的 NotFoundException（lb:// 无实例）。
        return name.endsWith("AnnotatedConnectException")
                || name.endsWith(".ConnectTimeoutException")
                || (name.endsWith(".NotFoundException") && name.contains("cloud.gateway"));
    }

    private boolean isTimeout(Throwable t) {
        // 只显式引用 java.util.concurrent.TimeoutException（netty 同名类型不做单类型导入，
        // 避免 ambiguous）；netty/io 系超时异常类名均以 TimeoutException 结尾，统一后缀判定。
        if (t instanceof TimeoutException) {
            return true;
        }
        String name = t.getClass().getName();
        // gateway ResponseTimeoutException（httpclient.response-timeout=5s）、
        // netty ReadTimeoutException/WriteTimeoutException、java.net.SocketTimeoutException。
        // 注意：连接建立期超时（ConnectTimeoutException）在分类入口已按「连接优先」归入 503。
        return name.endsWith("ResponseTimeoutException")
                || name.endsWith("ReadTimeoutException")
                || name.endsWith("WriteTimeoutException")
                || name.endsWith("SocketTimeoutException");
    }

    /**
     * 沿 cause 链（含自身）匹配；调用方保证连接故障优先于超时（ConnectTimeout 不允许被泛化为 504）。
     */
    private boolean chainMatches(Throwable ex, java.util.function.Predicate<Throwable> p) {
        Throwable cur = ex;
        for (int i = 0; i < MAX_CAUSE_DEPTH && cur != null; i++) {
            if (p.test(cur)) {
                return true;
            }
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private record Classification(HttpStatusCode status, int code, String message) {
        static Classification of(HttpStatus status, ErrorCode errorCode) {
            return new Classification(status, errorCode.getCode(), errorCode.getMessage());
        }
    }
}
