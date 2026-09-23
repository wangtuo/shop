package com.shop.framework.mq;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class MqAutoConfiguration {
}
