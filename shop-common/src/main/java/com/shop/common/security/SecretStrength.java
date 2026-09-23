package com.shop.common.security;

import java.util.List;
import java.util.Locale;

/**
 * 密钥强度（弱值/占位符）判定，供各启动期 fail-fast 校验器共用（framework、gateway
 * 以及不依赖 framework 的服务均可使用，故下沉到 shop-common）。
 *
 * <p>仅拦截「显然不是真实强密钥」的值：空、过短，或包含仓库清单/模板中常见的
 * 占位符标记（如 {@code CHANGE_ME_...}、{@code <your-secret>}）。这弥补了
 * 「只与某一个已知默认串做等值比较」的缺口——清单里若保留 {@code CHANGE_ME...}
 * 模板值不替换，等值校验会误判通过。真实随机密钥（hex/base64/随机串）不会命中
 * 这些字典词，误报概率可忽略。
 */
public final class SecretStrength {

    /** 小写子串形式的占位符/模板标记（命中即视为弱值）。 */
    private static final List<String> PLACEHOLDER_TOKENS = List.of(
            "change_me", "changeme", "change-me",
            "replace_me", "replace-me", "replacethis",
            "placeholder", "<your", "your_", "your-",
            "todo", "fixme", "example", "sample",
            "dummy", "fake", "secret_here", "xxx");

    private SecretStrength() {
    }

    /** 用默认最小长度 16 判定。 */
    public static boolean isWeak(String secret) {
        return isWeak(secret, 16);
    }

    /**
     * @param secret           待检测密钥（原样，允许 null）
     * @param minEntropyLength 去空白后允许的最小字符长度
     * @return true 表示空/过短/命中占位符标记，应拒绝用于生产
     */
    public static boolean isWeak(String secret, int minEntropyLength) {
        if (secret == null) {
            return true;
        }
        String trimmed = secret.trim();
        if (trimmed.isEmpty() || trimmed.length() < minEntropyLength) {
            return true;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String token : PLACEHOLDER_TOKENS) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
