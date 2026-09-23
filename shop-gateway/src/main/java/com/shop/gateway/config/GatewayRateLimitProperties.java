package com.shop.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关限流配置（C14/R-B3）。
 *
 * <pre>
 * shop.gateway.ratelimit:
 *   enabled: true
 *   redis-fail-open: false        # Redis 故障默认 fail-closed deny，生产不允许打开
 *   global: {replenish-rate: 100, burst-capacity: 200, requested-tokens: 1}
 *   routes:
 *     shop-order: {replenish-rate: 20, burst-capacity: 40}   # PERF §3 拐点 20 TPS 初始值
 * </pre>
 *
 * 三维度：全局默认桶 → 路由覆盖 → 主体维度（{@code ShopKeyResolver}：登录 X-User-Id，
 * 匿名 X-Forwarded-For 首段/真实 TCP 对端）。键前缀 {@code rl:{shop.env}:{routeId}:{principal}}。
 *
 * <p>手写 getter/setter（不依赖 Lombok 注解处理），保证配置类在任意编译插件配置下均可绑定。
 */
@ConfigurationProperties(prefix = "shop.gateway.ratelimit")
public class GatewayRateLimitProperties {

    /** 限流总开关（关闭时不注册限流过滤器引用；默认开启）。 */
    private boolean enabled = true;

    /**
     * Redis 故障时是否放行（fail-open）。默认 false=fail-closed deny：
     * 拒绝优于漏限（R-B4 矩阵第 1 行，生产锁定）。
     */
    private boolean redisFailOpen = false;

    /** 全局默认令牌桶（PERF 容量压测前初始值，prod 由 ConfigMap 覆盖）。 */
    private Bucket global = new Bucket(100, 200, 1);

    /** 路由维度覆盖：key=routeId（如 shop-order）；未配置的路由回退全局桶。 */
    private Map<String, Bucket> routes = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRedisFailOpen() {
        return redisFailOpen;
    }

    public void setRedisFailOpen(boolean redisFailOpen) {
        this.redisFailOpen = redisFailOpen;
    }

    public Bucket getGlobal() {
        return global;
    }

    public void setGlobal(Bucket global) {
        this.global = global;
    }

    public Map<String, Bucket> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Bucket> routes) {
        this.routes = routes;
    }

    /**
     * 解析路由生效桶：路由覆盖优先，缺失字段逐项回退全局桶，再回退硬默认（100/200/1）。
     */
    public Bucket resolve(String routeId) {
        Bucket fallback = global != null ? global : new Bucket(100, 200, 1);
        Bucket override = routeId != null ? routes.get(routeId) : null;
        if (override == null) {
            return fallback;
        }
        return new Bucket(
                override.getReplenishRate() > 0 ? override.getReplenishRate() : fallback.getReplenishRate(),
                override.getBurstCapacity() > 0 ? override.getBurstCapacity() : fallback.getBurstCapacity(),
                override.getRequestedTokens() > 0 ? override.getRequestedTokens() : fallback.getRequestedTokens());
    }

    /** 令牌桶参数：replenishRate=每秒填充速率；burstCapacity=桶容量；requestedTokens=每请求消耗。 */
    public static class Bucket {
        private int replenishRate;
        private int burstCapacity;
        private int requestedTokens;

        public Bucket() {
        }

        public Bucket(int replenishRate, int burstCapacity, int requestedTokens) {
            this.replenishRate = replenishRate;
            this.burstCapacity = burstCapacity;
            this.requestedTokens = requestedTokens;
        }

        public int getReplenishRate() {
            return replenishRate;
        }

        public void setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public void setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public void setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
        }
    }
}
