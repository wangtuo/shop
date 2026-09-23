package com.shop.gateway.ratelimit;

import com.shop.gateway.config.GatewayRateLimitProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.client.RedisException;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link ShopRedisRateLimiter} 三维度桶解析、键前缀与 Redis 故障 fail-closed 行为。 */
class ShopRedisRateLimiterTest {

    @SuppressWarnings("unchecked")
    private ReactiveStringRedisTemplate templateReturning(Object result) {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        if (result instanceof Throwable t) {
            when(template.execute(any(RedisScript.class), anyList(), anyList()))
                    .thenReturn(Flux.error(t));
        } else {
            when(template.execute(any(RedisScript.class), anyList(), anyList()))
                    .thenReturn(Flux.just(result));
        }
        return template;
    }

    private ShopRedisRateLimiter limiter(ReactiveStringRedisTemplate template,
                                         GatewayRateLimitProperties props) {
        @SuppressWarnings("unchecked")
        RedisScript<List<Long>> script = mock(RedisScript.class);
        return new ShopRedisRateLimiter(template, script, props, "local");
    }

    @Test
    void 与官方redisRateLimiter并存时_本实现必须Primary否则网关启动报双Bean() {
        // W7 HOSTAPPS 实证：GatewayRedisAutoConfiguration 注册官方 redisRateLimiter，
        // RequestRateLimiter 过滤器按单 RateLimiter 注入；本实现缺 @Primary 时
        // "expected single matching bean but found 2" 直接启动失败。
        assertThat(ShopRedisRateLimiter.class
                .getAnnotation(org.springframework.context.annotation.Primary.class)).isNotNull();

        try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean("shopRedisRateLimiter", ShopRedisRateLimiter.class,
                    () -> limiter(templateReturning(Flux.just(java.util.List.of(1L, 0L))),
                            new GatewayRateLimitProperties()));
            @SuppressWarnings("rawtypes")
            RateLimiter official = mock(RateLimiter.class);
            ctx.registerBean("redisRateLimiter", RateLimiter.class, () -> official);
            ctx.refresh();
            assertThat(ctx.getBean(RateLimiter.class))
                    .isInstanceOf(ShopRedisRateLimiter.class);
        }
    }

    @Test
    void 路由桶覆盖_键前缀带环境_放行() {
        GatewayRateLimitProperties props = new GatewayRateLimitProperties();
        props.getRoutes().put("shop-order", new GatewayRateLimitProperties.Bucket(20, 40, 1));
        ReactiveStringRedisTemplate template = templateReturning(List.of(1L, 39L));
        ShopRedisRateLimiter limiter = limiter(template, props);

        RateLimiter.Response response = limiter.isAllowed("shop-order", "u:123").block();

        assertThat(response.isAllowed()).isTrue();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> args = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue().get(0)).contains("rl:local:shop-order:u:123");
        List<?> argv = args.getValue();
        assertThat(argv.get(0)).isEqualTo("20");
        assertThat(argv.get(1)).isEqualTo("40");
    }

    @Test
    void 未配置路由_回退全局桶100_200() {
        ReactiveStringRedisTemplate template = templateReturning(List.of(0L, 0L));
        ShopRedisRateLimiter limiter = limiter(template, new GatewayRateLimitProperties());

        RateLimiter.Response response = limiter.isAllowed("shop-product", "ip:1.2.3.4").block();

        assertThat(response.isAllowed()).isFalse();
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> args = ArgumentCaptor.forClass(List.class);
        verify(template).execute(any(RedisScript.class), anyList(), args.capture());
        assertThat(args.getValue().get(0)).isEqualTo("100");
        assertThat(args.getValue().get(1)).isEqualTo("200");
    }

    @Test
    void redis故障_默认failClosed拒绝() {
        ReactiveStringRedisTemplate template =
                templateReturning(new RedisException("connection refused"));
        ShopRedisRateLimiter limiter = limiter(template, new GatewayRateLimitProperties());

        RateLimiter.Response response = limiter.isAllowed("shop-order", "u:1").block();

        assertThat(response.isAllowed()).isFalse();
    }

    @Test
    void redis故障_failOpen仅演练时放行() {
        GatewayRateLimitProperties props = new GatewayRateLimitProperties();
        props.setRedisFailOpen(true);
        ReactiveStringRedisTemplate template =
                templateReturning(new RedisException("down"));
        ShopRedisRateLimiter limiter = limiter(template, props);

        RateLimiter.Response response = limiter.isAllowed("shop-order", "u:1").block();

        assertThat(response.isAllowed()).isTrue();
    }
}
