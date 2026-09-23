package com.shop.gateway.filter;

import com.shop.common.constant.SecurityHeaders;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 限流主体解析（C14/R-B3）：
 * <ol>
 *   <li>已登录：{@code X-User-Id}（JwtAuthGlobalFilter 清洗外部伪造头后由 JWT claims 注入，
 *       内部头外部无法伪造），键形态 {@code u:<userId>}；</li>
 *   <li>匿名：{@code X-Forwarded-For} 首段，键形态 {@code ip:<ip>}。入站 XFF 已被
 *       JwtAuthGlobalFilter 无条件剥离，此处能读到的 XFF 只可能来自网关自身
 *       XForwardedHeadersFilter 对真实 TCP 对端的覆写追加，攻击者无法用随机 XFF 绕过；</li>
 *   <li>XFF 缺失（过滤器链阶段尚未追加）时回退真实 TCP 对端地址；再缺失用固定匿名桶
 *       （宁可共用桶也不返回空键触发 denyEmptyKey 的 403 语义偏差）。</li>
 * </ol>
 * 完整 Redis 键由 {@code ShopRedisRateLimiter} 拼成
 * {@code rl:{shop.env}:{routeId}:{principal}}。
 */
@Component("shopKeyResolver")
public class ShopKeyResolver implements KeyResolver {

    static final String ANONYMOUS = "ip:unknown";

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();

        String userId = request.getHeaders().getFirst(SecurityHeaders.USER_ID);
        if (userId != null && !userId.isBlank()) {
            return Mono.just("u:" + userId.trim());
        }

        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) {
                return Mono.just("ip:" + first);
            }
        }

        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null
                && remote.getAddress().getHostAddress() != null
                && !remote.getAddress().getHostAddress().isBlank()) {
            return Mono.just("ip:" + remote.getAddress().getHostAddress());
        }
        return Mono.just(ANONYMOUS);
    }
}
