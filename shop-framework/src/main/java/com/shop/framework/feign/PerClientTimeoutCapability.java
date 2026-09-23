package com.shop.framework.feign;

import feign.Capability;
import feign.Client;
import feign.Request;
import feign.Response;

import java.io.IOException;
import java.time.Duration;

/**
 * 按 {@code shop.feign.clients.<clientName>.read-timeout} 覆盖读超时的轻量 Capability。
 * 包在负载均衡 Client 之外，此时 URL 仍是 {@code http://serviceName/...}，clientName 可稳定解析。
 *
 * <p>必须声明为 {@code public}：Feign 构建期通过 {@link Capability#enrich} 以反射
 * Method.invoke 调用本类的 enrich 实现，包私有类在 JDK 反射访问检查下抛
 * IllegalAccessException（"interface feign.Capability cannot access a member ... with
 * modifiers public"），宿主形态首个带 FeignClient 的服务即启动失败。</p>
 */
public class PerClientTimeoutCapability implements Capability {

    private final FeignResilienceProperties properties;

    public PerClientTimeoutCapability(FeignResilienceProperties properties) {
        this.properties = properties;
    }

    @Override
    public Client enrich(Client client) {
        return new OverridingTimeoutClient(client, properties);
    }

    private static final class OverridingTimeoutClient implements Client {

        private final Client delegate;
        private final FeignResilienceProperties properties;

        private OverridingTimeoutClient(Client delegate, FeignResilienceProperties properties) {
            this.delegate = delegate;
            this.properties = properties;
        }

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            Request.Options effective = options;
            FeignResilienceProperties.Client override =
                    properties.getClients().get(FeignClientNames.fromRequest(request));
            if (override != null && override.getReadTimeout() != null) {
                Duration readTimeout = override.getReadTimeout();
                effective = new Request.Options(
                        Duration.ofMillis(options.connectTimeoutMillis()),
                        readTimeout,
                        options.isFollowRedirects());
            }
            return delegate.execute(request, effective);
        }
    }
}
