package com.shop.gateway.config;

import com.shop.common.security.SecretStrength;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 网关侧 JWT 密钥 fail-fast 校验（H-3）：网关为 webflux 应用、不依赖 shop-framework，
 * 故在此做与 {@code ShopSecretEnvironmentValidator} 同构的检查——
 * prod profile 下密钥为空或仍等于内置开发默认值时，ApplicationContext 启动失败。
 */
@Component
public class GatewaySecretValidator implements InitializingBean {

    public static final String DEFAULT_JWT_SECRET = "dev-local-only-jwt-secret-key-0123456789abcdef";

    private final Environment environment;

    @Value("${shop.jwt.secret:}")
    private String jwtSecret;

    public GatewaySecretValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            return;
        }
        // 除内置开发默认值外，再拦截空值/过短值/CHANGE_ME 等占位符（模板未替换）
        if (SecretStrength.isWeak(jwtSecret) || DEFAULT_JWT_SECRET.equals(jwtSecret.trim())) {
            throw new IllegalStateException(
                    "检测到生产安全配置缺失：shop.jwt.secret（环境变量 SHOP_JWT_SECRET）为空、强度不足（少于 16 位）、"
                            + "仍在使用内置开发默认值，或仍是 CHANGE_ME 等占位符，"
                            + "请通过环境变量/KMS Secret 注入强随机值后再启动（prod profile 强制 fail-fast）");
        }
    }
}
