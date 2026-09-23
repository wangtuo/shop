package com.shop.framework.security;

import com.shop.common.security.SecretStrength;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 启动期密钥 fail-fast 校验（H-3 / C-1）：
 * <ul>
 *   <li>激活 {@code prod} profile，或显式配置 {@code shop.security.require-env-secrets=true} 时生效；</li>
 *   <li>JWT 密钥（SHOP_JWT_SECRET）与服务间令牌（SHOP_INTERNAL_TOKEN）必须非空，
 *       且不得等于仓库内置开发默认值，否则 ApplicationContext 启动失败。</li>
 * </ul>
 * 默认/dev profile 允许使用内置默认值单机启动。网关（webflux，不依赖本 framework）
 * 在 shop-gateway 内有同构校验。
 */
@Component
public class ShopSecretEnvironmentValidator implements InitializingBean {

    /** 与 application.yml 占位符默认值、start-apps.sh 保持一致。 */
    public static final String DEFAULT_JWT_SECRET = "dev-local-only-jwt-secret-key-0123456789abcdef";
    public static final String DEFAULT_INTERNAL_TOKEN = "dev-local-only-internal-token";

    private final ShopSecurityProperties properties;
    private final Environment environment;

    @Value("${shop.jwt.secret:}")
    private String jwtSecret;

    @Value("${shop.internal.token:}")
    private String internalToken;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    public ShopSecretEnvironmentValidator(ShopSecurityProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        if (!prod && !properties.isRequireEnvSecrets() && !properties.isRequireEnvDb()) {
            return;
        }
        // JWT/内部 token 校验：prod 或 require-env-secrets 开启时执行（既有 H-3 口径不回退）
        if (prod || properties.isRequireEnvSecrets()) {
            requireExternalSecret("shop.jwt.secret（环境变量 SHOP_JWT_SECRET）", jwtSecret, DEFAULT_JWT_SECRET);
            requireExternalSecret("shop.internal.token（环境变量 SHOP_INTERNAL_TOKEN）", internalToken, DEFAULT_INTERNAL_TOKEN);
        }
        // DB 弱口令校验：prod 或 require-env-db 开启时执行（C16 / R-Z2）
        if (prod || properties.isRequireEnvDb()) {
            requireStrongDbPassword(dbPassword);
        }
    }

    /**
     * DB 口令红线：空/空白、等于 root（大小写不敏感）、去空白后长度 &lt; 8 一律拒绝启动。
     * 业务服务 yml 已统一为 {@code ${SHOP_DB_PASSWORD:root}}：dev 裸跑默认 root 不拦截，
     * prod 必须由 K8s Secret 注入强口令。
     */
    private void requireStrongDbPassword(String password) {
        String trimmed = password == null ? "" : password.trim();
        if (trimmed.isEmpty() || "root".equalsIgnoreCase(trimmed) || trimmed.length() < 8) {
            throw new IllegalStateException(
                    "检测到生产安全配置缺失：spring.datasource.password（环境变量 SHOP_DB_PASSWORD）"
                            + "为空、仍为弱口令 root、或长度少于 8 位，请通过 K8s Secret/密管注入强口令后再启动"
                            + "（prod profile 或 shop.security.require-env-db=true 时 fail-fast）");
        }
    }

    private void requireExternalSecret(String name, String value, String builtinDefault) {
        String trimmed = value == null ? null : value.trim();
        // 除「与内置开发默认值等值」外，再用 SecretStrength 拦截空值、过短值与
        // CHANGE_ME/your_xxx 等占位符——K8s 清单里模板值未替换时等值校验会漏过。
        if (SecretStrength.isWeak(trimmed) || builtinDefault.equals(trimmed)) {
            throw new IllegalStateException(
                    "检测到生产安全配置缺失：" + name + " 为空、强度不足（少于 16 位）、"
                            + "仍在使用内置开发默认值，或仍是 CHANGE_ME 等占位符，"
                            + "请通过环境变量/KMS Secret 注入强随机值后再启动（prod profile 强制 fail-fast）");
        }
    }
}
