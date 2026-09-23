package com.shop.gateway.ratelimit;

import com.shop.gateway.config.GatewayRateLimitProperties;
import com.shop.gateway.config.GatewayRateLimitProperties.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关 Redis 令牌桶限流器（C14/R-B3）：
 * <ul>
 *   <li>复用 Spring Cloud Gateway 官方 RequestRateLimiter 过滤器与官方 request_rate_limiter
 *       Lua（{@code redisRequestRateLimiterScript} Bean 提供，token bucket 原子脚本），
 *       仅替换 SPI 实现以取得三项官方实现没有的能力：按 {@code shop.gateway.ratelimit.routes.*}
 *       自定义三维度桶、{@code rl:{env}:{routeId}:{principal}} 键前缀、Redis 故障策略；</li>
 *   <li><b>Redis 故障默认 fail-closed</b>：官方 {@link RedisRateLimiter} 在 Lua 执行异常时
 *       固定放行（allowed=1, remaining=-1），与 R-B4「网关桶必须 fail-closed」冲突，故此处
 *       显式 onErrorResume 为拒绝；{@code shop.gateway.ratelimit.redis-fail-open=true}
 *       可改为降级放行（仅限演练，生产锁定 false）。</li>
 * </ul>
 *
 * <p>{@code @Primary} 必需：classpath 存在 spring-data-redis 时 GatewayRedisAutoConfiguration
 * 也会注册官方 {@code redisRateLimiter}，RequestRateLimiter 过滤器按单 RateLimiter 注入，
 * 不加 @Primary 会在启动期报 "expected single matching bean but found 2"。</p>
 */
@Component("shopRedisRateLimiter")
@Primary
public class ShopRedisRateLimiter implements RateLimiter<RedisRateLimiter.Config> {

    private static final Logger log = LoggerFactory.getLogger(ShopRedisRateLimiter.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> script;
    private final GatewayRateLimitProperties properties;
    private final String env;

    public ShopRedisRateLimiter(
            ReactiveStringRedisTemplate redisTemplate,
            @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> script,
            GatewayRateLimitProperties properties,
            @Value("${shop.env:local}") String env) {
        this.redisTemplate = redisTemplate;
        this.script = script;
        this.properties = properties;
        this.env = env == null || env.isBlank() ? "local" : env;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String principal) {
        Bucket bucket = properties.resolve(routeId);
        int replenishRate = bucket.getReplenishRate();
        int burstCapacity = bucket.getBurstCapacity();
        int requestedTokens = Math.max(1, bucket.getRequestedTokens());

        // 逻辑键前缀 rl:{env}:{routeId}:{principal}；外层 request_rate_limiter.{...} 花括号
        // 是 Redis Cluster hash tag：tokens 与 timestamp 两键必须落到同一 slot。
        String composite = "rl:" + env + ":" + routeId + ":" + principal;
        String tokensKey = "request_rate_limiter.{" + composite + "}";
        String timestampKey = tokensKey + ".timestamp";
        List<String> keys = List.of(tokensKey, timestampKey);
        List<String> args = List.of(
                String.valueOf(replenishRate),
                String.valueOf(burstCapacity),
                String.valueOf(Instant.now().getEpochSecond()),
                String.valueOf(requestedTokens));

        return redisTemplate.execute(script, keys, args)
                .next()
                .map(result -> toResponse(result, replenishRate, burstCapacity, requestedTokens))
                .onErrorResume(t -> redisFailure(routeId, principal, replenishRate,
                        burstCapacity, requestedTokens, t));
    }

    private Response toResponse(List<Long> result, int replenishRate, int burstCapacity,
                                int requestedTokens) {
        boolean allowed = result != null && !result.isEmpty() && result.get(0) == 1L;
        Long tokensRemaining = result != null && result.size() > 1 ? result.get(1) : -1L;
        return new Response(allowed, headers(replenishRate, burstCapacity, requestedTokens,
                tokensRemaining));
    }

    private Mono<Response> redisFailure(String routeId, String principal, int replenishRate,
                                        int burstCapacity, int requestedTokens, Throwable t) {
        // 只记录定位所需的 routeId/主体类别与异常类型；不向下游回传任何 Redis/Netty 细节。
        log.warn("rate-limit redis failure route={} principalClass={} failOpen={} cause={}",
                routeId, principalType(principal), properties.isRedisFailOpen(),
                t.getClass().getName());
        boolean allow = properties.isRedisFailOpen();
        // fail-open 时 remaining=-1 表示「未经桶判定的放行」，与官方错误放行语义一致，便于监控识别。
        return Mono.just(new Response(allow, headers(replenishRate, burstCapacity,
                requestedTokens, allow ? -1L : 0L)));
    }

    private static String principalType(String principal) {
        if (principal == null) {
            return "null";
        }
        return principal.startsWith("u:") ? "user" : "anon";
    }

    private Map<String, String> headers(int replenishRate, int burstCapacity,
                                        int requestedTokens, long tokensRemaining) {
        Map<String, String> headers = new HashMap<>();
        headers.put(RedisRateLimiter.REMAINING_HEADER, String.valueOf(tokensRemaining));
        headers.put(RedisRateLimiter.REPLENISH_RATE_HEADER, String.valueOf(replenishRate));
        headers.put(RedisRateLimiter.BURST_CAPACITY_HEADER, String.valueOf(burstCapacity));
        headers.put(RedisRateLimiter.REQUESTED_TOKENS_HEADER, String.valueOf(requestedTokens));
        return headers;
    }

    /**
     * {@link org.springframework.cloud.gateway.support.Configurable} SPI：
     * 桶配置全部来自 {@link GatewayRateLimitProperties}，过滤器 args 不传桶参数；
     * newConfig 仅给 ConfigurationService 一个合法默认对象（全局桶值），实际解析恒走
     * {@link #isAllowed(String, String)} 内的 {@code properties.resolve(routeId)}。
     */
    @Override
    public Class<RedisRateLimiter.Config> getConfigClass() {
        return RedisRateLimiter.Config.class;
    }

    @Override
    public RedisRateLimiter.Config newConfig() {
        Bucket global = properties.getGlobal() != null
                ? properties.getGlobal() : new Bucket(100, 200, 1);
        return new RedisRateLimiter.Config()
                .setReplenishRate(global.getReplenishRate())
                .setBurstCapacity(global.getBurstCapacity())
                .setRequestedTokens(Math.max(1, global.getRequestedTokens()));
    }

    /**
     * 官方 {@link org.springframework.cloud.gateway.support.StatefulConfigurable} 接口：
     * 本实现不维护路由配置表（配置来自属性绑定），返回空 Map。
     */
    @Override
    public Map<String, RedisRateLimiter.Config> getConfig() {
        return Map.of();
    }
}
