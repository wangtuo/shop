package com.shop.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SecretStrength} 弱密钥/占位符判定。
 */
class SecretStrengthTest {

    @Test
    void 空与null与过短_判为弱() {
        assertTrue(SecretStrength.isWeak(null));
        assertTrue(SecretStrength.isWeak(""));
        assertTrue(SecretStrength.isWeak("   "));
        assertTrue(SecretStrength.isWeak("short"));
        assertTrue(SecretStrength.isWeak("0123456789abcdef".substring(1), 16));
    }

    @Test
    void 内置默认值本身虽够长但由调用方等值校验_随机强值通过() {
        assertFalse(SecretStrength.isWeak("dev-local-only-jwt-secret-key-0123456789abcdef"));
        assertFalse(SecretStrength.isWeak("a9f3c7e1b2d48f60a1c5e7b9d3f20486"));
        assertFalse(SecretStrength.isWeak("cT7#kQ9$mL2vX8z!pR1nW4yB6hJ0sV"));
    }

    @Test
    void 常见占位符_大小写不敏感命中() {
        assertTrue(SecretStrength.isWeak("CHANGE_ME_IN_K8S_SECRET_PLEASE"));
        assertTrue(SecretStrength.isWeak("changeme-now"));
        assertTrue(SecretStrength.isWeak("<your-secret-here>"));
        assertTrue(SecretStrength.isWeak("YOUR_JWT_SECRET_HERE"));
        assertTrue(SecretStrength.isWeak("replace_me_with_real_value_xxx"));
        assertTrue(SecretStrength.isWeak("todo-generate-random-key"));
        assertTrue(SecretStrength.isWeak("example-secret-example"));
    }

    @Test
    void 自定义最小长度生效() {
        assertTrue(SecretStrength.isWeak("abcd", 8));
        assertFalse(SecretStrength.isWeak("abcdefgh", 8));
    }
}
