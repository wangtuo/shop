package com.shop.settlement.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 清算域 MyBatis 配置：按约定扫描所有标注 @Mapper 的接口。
 */
@Configuration
@MapperScan("com.shop.settlement.**.mapper")
public class SettlementMybatisConfig {
}
