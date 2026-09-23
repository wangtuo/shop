package com.shop.framework.bootstrap;

import com.shop.framework.feign.FeignRequestInterceptor;
import com.shop.framework.job.ShedLockConfig;
import com.shop.framework.outbox.OutboxConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务服务统一入口注解：
 * <ul>
 *   <li>扫描 com.shop 下全部 Bean（含 framework 自动配置）</li>
 *   <li>Mapper 必须标注 {@code @org.apache.ibatis.annotations.Mapper}，按注解自动扫描</li>
 *   <li>Feign 客户端统一从 com.shop.api 装载</li>
 *   <li>开启 Nacos 服务发现、定时任务、异步、声明式事务</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootApplication(scanBasePackages = "com.shop")
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.shop.api")
@EnableScheduling
@EnableAsync
@EnableTransactionManagement
@Import({ShedLockConfig.class, FeignRequestInterceptor.class, OutboxConfig.class})
public @interface ShopService {
}
