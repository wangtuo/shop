package com.shop.framework.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 安全配置项。
 *
 * <p>{@code shop.security.require-env-secrets=true} 时启动即强制 JWT 密钥与服务间 internal token
 * 必须来自环境注入（非空且不等于内置开发默认值）；{@code prod} profile 下该校验自动开启，
 * 见 {@link ShopSecretEnvironmentValidator}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "shop.security")
public class ShopSecurityProperties {

    /** 强制密钥外置：缺失或仍为内置默认值时 ApplicationContext 启动失败（fail-fast）。 */
    private boolean requireEnvSecrets = false;

    /**
     * 强制 DB 口令外置与强度（C16 / R-Z2）：开启后 spring.datasource.password 为空/等于
     * root/长度 &lt; 8 即拒绝启动。{@code prod} profile 下自动开启。
     */
    private boolean requireEnvDb = false;
}
