package com.shop.framework.datasource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Druid 有界基线装配（C12）：业务服务无需任何改动即获得有界连接池。
 */
@Configuration
@EnableConfigurationProperties(DataSourceTuningProperties.class)
public class DruidBaselineConfig {

    @Bean
    public DruidBaselinePostProcessor druidBaselinePostProcessor(Environment environment,
                                                                 DataSourceTuningProperties properties) {
        return new DruidBaselinePostProcessor(environment, properties);
    }
}
