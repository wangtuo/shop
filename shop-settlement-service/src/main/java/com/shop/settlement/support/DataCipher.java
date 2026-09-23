package com.shop.settlement.support;

import com.shop.common.security.SecretStrength;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感字段落库加密（M-1）：AES-256/GCM/NoPadding。
 *
 * <p>密文格式：{@code enc:v1:} + Base64(IV(12) || 密文 || TAG(16))。
 * 带前缀以兼容历史明文行（{@link #decrypt} 遇无前缀旧值原样返回，渐进迁移）。
 *
 * <p>密钥来源（优先级）：{@code -Dshop.data.enc-key} 系统属性
 * → 环境变量/配置 {@code SHOP_DATA_ENC_KEY} → 开发默认值（仅本地）。
 * 原文非 32 字节时以 SHA-256 派生 32 字节密钥。生产 profile 仍使用内置默认密钥时启动 fail-fast。
 */
@Slf4j
@Component
public class DataCipher {

    /** 密文版本前缀，同时作为“是否已加密”的判定标记。 */
    public static final String CIPHER_PREFIX = "enc:v1:";

    /** 仅允许本地开发使用的内置默认密钥（32 字节 ASCII）。 */
    public static final String DEV_DEFAULT_KEY = "dev-local-only-data-enc-key-32b";

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String rawKey;
    private final SecureRandom random = new SecureRandom();

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    public DataCipher(@Value("${shop.data.enc-key:${SHOP_DATA_ENC_KEY:" + DEV_DEFAULT_KEY + "}}") String rawKey) {
        this.rawKey = rawKey;
        this.key = buildKey(rawKey);
    }

    /** 测试/派生构造：显式指定密钥与 profile。 */
    public static DataCipher forTest(String rawKey, String activeProfiles) {
        DataCipher cipher = new DataCipher(rawKey);
        cipher.activeProfiles = activeProfiles;
        cipher.validateProdKey();
        return cipher;
    }

    private static SecretKeySpec buildKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalStateException("数据加密密钥 shop.data.enc-key/SHOP_DATA_ENC_KEY 未配置");
        }
        byte[] raw = rawKey.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = raw.length == 32 ? raw : sha256(raw);
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    @PostConstruct
    void validateProdKey() {
        if (activeProfiles == null || !activeProfiles.contains("prod")) {
            return;
        }
        if (MessageDigest.isEqual(key.getEncoded(), buildKey(DEV_DEFAULT_KEY).getEncoded())
                || SecretStrength.isWeak(rawKey, 16)) {
            throw new IllegalStateException(
                    "生产环境数据加密密钥不合规：仍在使用内置默认值，或为空/过短/CHANGE_ME 等占位符，"
                            + "请通过 -Dshop.data.enc-key 或 SHOP_DATA_ENC_KEY 经 KMS/Secret 注入强随机密钥");
        }
    }

    /** 加密；null/空串原样返回（NOT NULL DEFAULT '' 的空值不产生密文）。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(cipherText, 0, all, iv.length, cipherText.length);
            return CIPHER_PREFIX + Base64.getEncoder().encodeToString(all);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /**
     * 解密；null/空串原样返回；不带 {@link #CIPHER_PREFIX} 的历史明文原样返回（兼容渐进迁移）。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(CIPHER_PREFIX)) {
            return stored;
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(CIPHER_PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感字段解密失败", e);
        }
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(CIPHER_PREFIX);
    }
}
