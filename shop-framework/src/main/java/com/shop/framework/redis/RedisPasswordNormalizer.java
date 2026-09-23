package com.shop.framework.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

/**
 * Redis 空白密码归一化（redisson-spring-boot-starter 3.27.2 兼容修复）。
 *
 * <p>8 份业务服务 yml 统一写作 {@code password: ${SHOP_REDIS_PASSWORD:}}：未注入环境变量时
 * 占位符解析为<b>空字符串</b>（而非属性缺失的 null）。Redisson 自动装配仅判 null 不判 blank，
 * 会向无密码 Redis 发送 {@code AUTH ""}，Redis 6+ ACL 直接拒绝：
 * {@code ERR AUTH <password> called without any password configured}，服务启动失败。
 * 顺带对 Spring Data Redis（Lettuce）同口径归一，避免两条客户端路径行为分叉。</p>
 *
 * <p>此处把绑定后的空白密码统一归一为 null（standalone + sentinel 两处），真实密码原样透传。
 * 不改任何 yml、不硬编码密钥：生产仍必须由 SHOP_REDIS_PASSWORD / KMS Secret 注入。
 * 生产密钥 fail-fast 走 Environment @Value（ShopSecretEnvironmentValidator），不受本归一化影响。</p>
 *
 * <p>BeanPostProcessor 在 RedisProperties 绑定完成后、Redisson/Lettuce 客户端 Bean 创建前执行，
 * 依赖该属性 Bean 的客户端拿到的都是归一化后的值。</p>
 */
@Slf4j
@Component
public class RedisPasswordNormalizer implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RedisProperties properties) {
            if (isBlank(properties.getPassword())) {
                properties.setPassword(null);
                log.info("spring.data.redis.password 为空白字符串，已归一化为 null（无密码形态）；"
                        + "生产环境必须通过 SHOP_REDIS_PASSWORD/KMS Secret 注入真实密码");
            }
            RedisProperties.Sentinel sentinel = properties.getSentinel();
            if (sentinel != null && isBlank(sentinel.getPassword())) {
                sentinel.setPassword(null);
            }
        }
        return bean;
    }

    private static boolean isBlank(String value) {
        return value != null && value.isBlank();
    }
}
