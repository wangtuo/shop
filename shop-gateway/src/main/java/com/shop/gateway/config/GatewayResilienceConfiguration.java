package com.shop.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 网关韧性配置（C14/R-B3）：限流参数绑定。 */
@Configuration
@EnableConfigurationProperties(GatewayRateLimitProperties.class)
public class GatewayResilienceConfiguration {
}
