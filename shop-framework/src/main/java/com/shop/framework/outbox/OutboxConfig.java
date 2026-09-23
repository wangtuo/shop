package com.shop.framework.outbox;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * outbox 装配。
 *
 * <p>显式 MapperScan 会关闭 mybatis-spring-boot 的自动扫描，因此这里一次性扫描
 * {@code com.shop} 下所有 {@code @Mapper} 接口（各业务服务既有 mapper 与框架 outbox mapper
 * 均覆盖；Feign 客户端不带 @Mapper 不受影响）。
 */
@Configuration
@MapperScan(basePackages = "com.shop", annotationClass = Mapper.class)
public class OutboxConfig {
}
