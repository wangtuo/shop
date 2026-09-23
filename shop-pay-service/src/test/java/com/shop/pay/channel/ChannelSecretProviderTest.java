package com.shop.pay.channel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * M-5：渠道密钥外置 —— 自定义密钥可取用；prod profile 下启用 mock 或内置默认密钥 fail-fast。
 */
class ChannelSecretProviderTest {

    @Test
    void devDefaults_本地开发默认密钥_非生产环境可启动() {
        assertDoesNotThrow(ChannelSecretProvider::devDefaults);
        assertEquals("mock_wechat_secret_2026",
                ChannelSecretProvider.devDefaults().secret("MOCK_WECHAT"));
    }

    @Test
    void prodProfile_仍启用Mock渠道_failFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ChannelSecretProvider(ChannelSecretProvider.DEV_DEFAULT_SECRETS, true, "prod"));
        assert ex.getMessage().contains("MOCK");
    }

    @Test
    void prodProfile_关闭Mock但仍用内置默认密钥_failFast() {
        assertThrows(IllegalStateException.class,
                () -> new ChannelSecretProvider(ChannelSecretProvider.DEV_DEFAULT_SECRETS, false, "prod"));
    }

    @Test
    void prodProfile_关闭Mock且注入真实密钥_通过() {
        Map<String, String> rotated = Map.of(
                ChannelSecretProvider.MOCK_WECHAT, "kms://wechat-rotated-0001",
                ChannelSecretProvider.MOCK_ALIPAY, "kms://alipay-rotated-0002",
                ChannelSecretProvider.MOCK_BANK, "kms://bank-rotated-00003",
                ChannelSecretProvider.MOCK_UQR, "kms://uqr-rotated-000004",
                ChannelSecretProvider.MOCK_HUABEI, "kms://huabei-rotated-0005",
                ChannelSecretProvider.MOCK_BAITIAO, "kms://baitiao-rotated-006");
        assertDoesNotThrow(() -> new ChannelSecretProvider(rotated, false, false, "prod"));
    }

    @Test
    void prodProfile_ha验收豁免但仍用内置默认密钥_failFast() {
        // 豁免开关打开，但密钥没有外置：仍必须 fail-fast（豁免不解除密钥外置要求）
        assertThrows(IllegalStateException.class,
                () -> new ChannelSecretProvider(ChannelSecretProvider.DEV_DEFAULT_SECRETS, true, true, "prod"));
    }

    @Test
    void prodProfile_ha验收豁免且六渠道密钥全部外置_通过() {
        Map<String, String> accepted = Map.of(
                ChannelSecretProvider.MOCK_WECHAT, "kind-ha-wechat-secret-0001",
                ChannelSecretProvider.MOCK_ALIPAY, "kind-ha-alipay-secret-0002",
                ChannelSecretProvider.MOCK_BANK, "kind-ha-bank-secret-00003",
                ChannelSecretProvider.MOCK_UQR, "kind-ha-uqr-secret-000004",
                ChannelSecretProvider.MOCK_HUABEI, "kind-ha-huabei-secret-0005",
                ChannelSecretProvider.MOCK_BAITIAO, "kind-ha-baitiao-secret-006");
        assertDoesNotThrow(() -> new ChannelSecretProvider(accepted, true, true, "prod"));
    }

    @Test
    void secret_未知渠道_抛异常() {
        ChannelSecretProvider provider = ChannelSecretProvider.devDefaults();
        assertThrows(IllegalArgumentException.class, () -> provider.secret("UNKNOWN"));
    }
}
