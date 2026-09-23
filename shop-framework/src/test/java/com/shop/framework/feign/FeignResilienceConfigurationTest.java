package com.shop.framework.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Client;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FeignResilienceConfiguration} Spring 装配回归。
 *
 * <p>W7 E2E 实证根因：本配置曾直接暴露 {@code feign.Client}（裸 ApacheHttp5Client）。
 * Spring Cloud OpenFeign 的 {@code HttpClient5FeignLoadBalancerConfiguration} 在每个
 * FeignClient 子上下文以 {@code @ConditionalOnMissingBean(feign.Client)} 注册
 * {@code FeignBlockingLoadBalancerClient}；主上下文存在裸 Client 会让 LB 包装整体退避，
 * 运行期服务名直做 DNS（UnknownHostException → 10008），跨服务调用全灭。
 * 红线：本配置只能暴露 HC5 {@link HttpClient}，绝不能发布 {@code feign.Client}。</p>
 */
class FeignResilienceConfigurationTest {

    @Test
    void 只暴露HC5的HttpClient而绝不发布feignClient_保证LB包装不退避() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext()) {
            ctx.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
            ctx.register(FeignResilienceConfiguration.class);
            ctx.refresh();
            // 扩展点：HC5 HttpClient（连接池由 shop.feign.pool.* 定制）
            HttpClient httpClient = ctx.getBean(HttpClient.class);
            assertThat(httpClient).isInstanceOf(CloseableHttpClient.class);

            // 红线：主上下文不得存在任何 feign.Client，否则子上下文 LB 客户端 @ConditionalOnMissingBean 退避
            String[] clientBeans = ctx.getBeanNamesForType(Client.class, false, false);
            assertThat(clientBeans)
                    .as("FeignResilienceConfiguration 禁止发布 feign.Client（会压制 Spring Cloud LB 包装）")
                    .isEmpty();
        }
    }
}
