package com.shop.settlement.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M-1 AES/GCM 字段加密。 */
class DataCipherTest {

    private final DataCipher cipher = DataCipher.forTest(DataCipher.DEV_DEFAULT_KEY, "");

    @Test
    void 加密解密往返一致() {
        String plain = "6222020200001234";
        String enc = cipher.encrypt(plain);
        assertTrue(enc.startsWith(DataCipher.CIPHER_PREFIX));
        assertNotEquals(plain, enc);
        assertEquals(plain, cipher.decrypt(enc));
    }

    @Test
    void 同一明文每次密文不同_随机IV() {
        String enc1 = cipher.encrypt("alipay-account-001");
        String enc2 = cipher.encrypt("alipay-account-001");
        assertNotEquals(enc1, enc2);
        assertEquals("alipay-account-001", cipher.decrypt(enc1));
        assertEquals("alipay-account-001", cipher.decrypt(enc2));
    }

    @Test
    void 密文被篡改解密失败() {
        String enc = cipher.encrypt("6222020200001234");
        int idx = enc.length() - 4; // 落在 base64 密文区
        char flip = enc.charAt(idx) == 'A' ? 'B' : 'A';
        String tampered = enc.substring(0, idx) + flip + enc.substring(idx + 1);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void 错误密钥解密失败() {
        String enc = cipher.encrypt("6222020200001234");
        DataCipher other = DataCipher.forTest("another-32-byte-secret-key-abcdefg90", "");
        assertThrows(IllegalStateException.class, () -> other.decrypt(enc));
    }

    @Test
    void 自定义密钥可正常加解密() {
        DataCipher custom = DataCipher.forTest("my-rotated-production-key-2026-32b", "prod");
        String enc = custom.encrypt("张三的账号");
        assertEquals("张三的账号", custom.decrypt(enc));
    }

    @Test
    void 历史明文行无前缀原样返回_渐进迁移() {
        assertFalse(cipher.isEncrypted("legacy-plain-account"));
        assertEquals("legacy-plain-account", cipher.decrypt("legacy-plain-account"));
    }

    @Test
    void 空值与null原样透传() {
        assertEquals("", cipher.encrypt(""));
        assertEquals("", cipher.decrypt(""));
        assertNull(cipher.encrypt(null));
        assertNull(cipher.decrypt(null));
    }

    @Test
    void 短密钥经sha256派生也可往返() {
        DataCipher shortKey = DataCipher.forTest("short", "");
        String enc = shortKey.encrypt("acct");
        assertEquals("acct", shortKey.decrypt(enc));
        // 同短密钥两次构造（同派生结果）可互解
        DataCipher shortKey2 = DataCipher.forTest("short", "");
        assertEquals("acct", shortKey2.decrypt(enc));
    }

    @Test
    void 生产环境使用内置默认密钥failFast() {
        assertThrows(IllegalStateException.class,
                () -> DataCipher.forTest(DataCipher.DEV_DEFAULT_KEY, "prod"));
    }

    @Test
    void 生产环境占位符密钥failFast() {
        assertThrows(IllegalStateException.class,
                () -> DataCipher.forTest("CHANGE_ME_PUT_REAL_KEY_HERE_32B", "prod"));
    }

    @Test
    void 生产环境强随机外部密钥通过校验() {
        assertDoesNotThrow(() -> DataCipher.forTest("u7R2mK9xQ4vN8pL1sT6wY3bH5jF0dZ8c", "prod"));
    }

    @Test
    void 空密钥构造失败() {
        assertThrows(IllegalStateException.class, () -> DataCipher.forTest("  ", ""));
    }
}
