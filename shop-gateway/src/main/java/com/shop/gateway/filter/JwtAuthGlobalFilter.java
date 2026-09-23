package com.shop.gateway.filter;

import com.shop.common.constant.SecurityHeaders;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网关统一鉴权：
 * <ol>
 *   <li>无条件清洗外部伪造的身份头与链路头，内部服务只信任网关注入；</li>
 *   <li>路径先重复解码 + 归一化（去矩阵参数/解析 . 与 ..），再做一切判定，
 *       杜绝 %2e、%25、;、.. 等绕过形态；</li>
 *   <li>/inner/** 内网接口对外一律 404；</li>
 *   <li>白名单仅收敛到具体公开路径（登录注册、商品公开浏览、领券中心、健康检查）；</li>
 *   <li>其余路径必须携带合法 JWT，解析后以请求头透传用户身份。</li>
 * </ol>
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    /** 服务间 Feign 调用的内网路径段；出现在归一化后的任一段中即拒绝。 */
    private static final String INNER_SEGMENT = "inner";

    /**
     * 文件级白名单（禁止整域放行）。新增公开接口时在此显式登记。
     */
    private static final List<String> WHITELIST = List.of(
            "/api/user/auth/login",
            "/api/user/auth/register",
            // 首个平台账号一次性引导（X-Bootstrap-Token 把关，已有平台账号后服务端永久关闭）
            "/api/user/auth/bootstrap-admin",
            // 商品域公开浏览：详情/搜索、品牌类目树、评价列表
            "/api/product/products/**",
            "/api/product/brands/**",
            "/api/product/categories/**",
            "/api/product/comments/**",
            // 营销域公开浏览：领券中心
            "/api/marketing/coupons/center",
            // K8s 探针；prometheus/swagger 不在网关放行
            "/actuator/health/**",
            "/favicon.ico"
    );

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final byte[] secretBytes;

    public JwtAuthGlobalFilter(
            @Value("${shop.jwt.secret:dev-local-only-jwt-secret-key-0123456789abcdef}") String secret) {
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String rawPath = request.getURI().getRawPath();
        String path = canonicalPath(rawPath);

        if (containsInnerSegment(path)) {
            return notFound(exchange.getResponse());
        }

        ServerHttpRequest.Builder builder = request.mutate()
                .headers(headers -> {
                    headers.remove(SecurityHeaders.USER_ID);
                    headers.remove(SecurityHeaders.USER_NAME);
                    headers.remove(SecurityHeaders.USER_TYPE);
                    headers.remove(SecurityHeaders.MERCHANT_ID);
                    headers.remove(SecurityHeaders.TRACE_ID);
                    headers.remove(SecurityHeaders.INTERNAL_TOKEN);
                    // M-2：清洗客户端伪造的转发链头。若直接放行外部 XFF，下游按 XFF 首段
                    // 做 IP 限流器会被攻击者用随机伪造 IP 完全绕过；剥离后由 Gateway 的
                    // XForwardedHeadersFilter 只追加真实 TCP 对端地址，内部服务才能信任。
                    headers.remove("X-Forwarded-For");
                    headers.remove("X-Real-IP");
                    headers.remove("X-Forwarded-Proto");
                    headers.remove("X-Forwarded-Host");
                    headers.remove("X-Forwarded-Port");
                    headers.remove("X-Forwarded-Prefix");
                    headers.remove("Forwarded");
                });

        String token = resolveToken(request.getHeaders());
        if (token != null) {
            try {
                Claims claims = Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(secretBytes))
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
                builder.header(SecurityHeaders.USER_ID, String.valueOf(claims.get("uid")));
                Object name = claims.get("name");
                if (name != null) {
                    builder.header(SecurityHeaders.USER_NAME, URLEncoder.encode(name.toString(), StandardCharsets.UTF_8));
                }
                Object utype = claims.get("utype");
                if (utype != null) {
                    builder.header(SecurityHeaders.USER_TYPE, String.valueOf(utype));
                }
                Object mid = claims.get("mid");
                if (mid != null) {
                    builder.header(SecurityHeaders.MERCHANT_ID, String.valueOf(mid));
                }
            } catch (Exception e) {
                if (!isWhitelisted(path)) {
                    return unauthorized(exchange.getResponse(), "登录已过期，请重新登录");
                }
            }
        } else if (!isWhitelisted(path)) {
            return unauthorized(exchange.getResponse(), "未登录");
        }
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    /**
     * 原始路径归一化：重复 URL 解码至稳定（最多 4 次，覆盖双重编码）→ 反斜杠转正斜杠
     * → 逐段去 ; 矩阵参数 → 解析 . 与 ..。返回以 / 开头、不以 / 结尾（根除外）的规范路径。
     */
    static String canonicalPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String decoded = rawPath;
        for (int i = 0; i < 4; i++) {
            String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        decoded = decoded.replace('\\', '/');
        String[] rawSegments = decoded.split("/");
        List<String> normalized = new ArrayList<>();
        for (String seg : rawSegments) {
            int semi = seg.indexOf(';');
            if (semi >= 0) {
                seg = seg.substring(0, semi);
            }
            if (seg.isEmpty() || ".".equals(seg)) {
                continue;
            }
            if ("..".equals(seg)) {
                if (!normalized.isEmpty()) {
                    normalized.remove(normalized.size() - 1);
                }
                continue;
            }
            normalized.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (String seg : normalized) {
            sb.append('/').append(seg);
        }
        return sb.length() == 0 ? "/" : sb.toString();
    }

    static boolean containsInnerSegment(String canonicalPath) {
        if (canonicalPath == null || canonicalPath.length() < 2) {
            return false;
        }
        for (String seg : canonicalPath.substring(1).split("/")) {
            if (INNER_SEGMENT.equals(seg.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.stream().anyMatch(pattern -> matcher.match(pattern, path));
    }

    private String resolveToken(HttpHeaders headers) {
        String auth = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":10002,\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> notFound(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":10404,\"message\":\"Not Found\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
