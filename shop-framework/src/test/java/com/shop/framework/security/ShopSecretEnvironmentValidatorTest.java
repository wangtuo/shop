package com.shop.framework.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 密钥 fail-fast 校验单测（H-3 / C-1）：prod 或 require-env-secrets 下
 * 密钥为空/内置默认值即启动失败；dev 默认 profile 允许内置值单机启动。
 */
class ShopSecretEnvironmentValidatorTest {

    private ShopSecretEnvironmentValidator validator(boolean requireEnvSecrets, String... profiles) {
        ShopSecurityProperties properties = new ShopSecurityProperties();
        properties.setRequireEnvSecrets(requireEnvSecrets);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        ShopSecretEnvironmentValidator validator =
                new ShopSecretEnvironmentValidator(properties, environment);
        return validator;
    }

    @Test
    void prod下使用内置默认JWT密钥_启动失败() {
        var v = validator(false, "prod");
        ReflectionTestUtils.setField(v, "jwtSecret", ShopSecretEnvironmentValidator.DEFAULT_JWT_SECRET);
        ReflectionTestUtils.setField(v, "internalToken", "strong-internal-token-value");
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void prod下内置默认internalToken_启动失败() {
        var v = validator(false, "prod");
        ReflectionTestUtils.setField(v, "jwtSecret", "a-strong-jwt-secret-0123456789abcdef-xyz");
        ReflectionTestUtils.setField(v, "internalToken", ShopSecretEnvironmentValidator.DEFAULT_INTERNAL_TOKEN);
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void prod下密钥为空_启动失败() {
        var v = validator(false, "prod");
        ReflectionTestUtils.setField(v, "jwtSecret", " ");
        ReflectionTestUtils.setField(v, "internalToken", "");
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void prod下均为外部注入强随机值_正常启动() {
        var v = validator(false, "prod");
        ReflectionTestUtils.setField(v, "jwtSecret", "env-injected-strong-jwt-secret-9f8a7b6c");
        ReflectionTestUtils.setField(v, "internalToken", "env-injected-strong-internal-token");
        ReflectionTestUtils.setField(v, "dbPassword", "Strong#Db_2026!");
        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    void prod下占位符模板值_启动失败() {
        var v = validator(false, "prod");
        ReflectionTestUtils.setField(v, "jwtSecret", "CHANGE_ME_REPLACE_WITH_REAL_JWT_SECRET");
        ReflectionTestUtils.setField(v, "internalToken", "env-injected-strong-internal-token");
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void dev默认profile使用内置默认值_允许单机启动() {
        var v = validator(false);
        ReflectionTestUtils.setField(v, "jwtSecret", ShopSecretEnvironmentValidator.DEFAULT_JWT_SECRET);
        ReflectionTestUtils.setField(v, "internalToken", ShopSecretEnvironmentValidator.DEFAULT_INTERNAL_TOKEN);
        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    void 显式requireEnvSecrets开关在非prod下同样生效() {
        var v = validator(true);
        ReflectionTestUtils.setField(v, "jwtSecret", ShopSecretEnvironmentValidator.DEFAULT_JWT_SECRET);
        ReflectionTestUtils.setField(v, "internalToken", "strong-internal-token-value");
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    // ===== C16 / R-Z2：DB 弱口令四用例 =====

    /** 构造仅开启 DB 校验（require-env-db）、JWT/internal 留空的校验器。 */
    private ShopSecretEnvironmentValidator dbValidator(String... profiles) {
        ShopSecurityProperties properties = new ShopSecurityProperties();
        properties.setRequireEnvDb(profiles.length == 0);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new ShopSecretEnvironmentValidator(properties, environment);
    }

    @Test
    void db口令为空_prod启动失败() {
        var v = dbValidator("prod");
        ReflectionTestUtils.setField(v, "dbPassword", "  ");
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void db口令为root_prod启动失败() {
        var v = dbValidator("prod");
        ReflectionTestUtils.setField(v, "dbPassword", "root");
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void db口令长度不足8位_prod启动失败() {
        var v = dbValidator("prod");
        ReflectionTestUtils.setField(v, "dbPassword", "Ab1!xyz");
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }

    @Test
    void db口令为强口令_prod正常启动() {
        var v = dbValidator("prod");
        // prod 下 JWT/internal 校验同时生效，一并注入强值
        ReflectionTestUtils.setField(v, "jwtSecret", "env-injected-strong-jwt-secret-9f8a7b6c");
        ReflectionTestUtils.setField(v, "internalToken", "env-injected-strong-internal-token");
        ReflectionTestUtils.setField(v, "dbPassword", "Strong#Db_2026!");
        assertDoesNotThrow(v::afterPropertiesSet);
    }

    @Test
    void dev默认profile弱口令root_不拦截() {
        ShopSecurityProperties properties = new ShopSecurityProperties();
        MockEnvironment environment = new MockEnvironment();
        var v = new ShopSecretEnvironmentValidator(properties, environment);
        ReflectionTestUtils.setField(v, "jwtSecret", ShopSecretEnvironmentValidator.DEFAULT_JWT_SECRET);
        ReflectionTestUtils.setField(v, "internalToken", ShopSecretEnvironmentValidator.DEFAULT_INTERNAL_TOKEN);
        ReflectionTestUtils.setField(v, "dbPassword", "root");
        assertDoesNotThrow(v::afterPropertiesSet);
    }

    /** H-3 回归：prod profile 下 JWT/internal/DB 全部缺失（未注入任何 Secret）必须 fail-fast。 */
    @Test
    void prod下全部密钥缺失_上下文启动失败_H3回归() {
        var v = dbValidator("prod");
        // jwtSecret/internalToken/dbPassword 均不赋值（模拟 K8s Secret 未挂载/未注入）
        assertThrows(IllegalStateException.class, v::afterPropertiesSet);
    }
}
