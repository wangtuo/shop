package com.shop.pay.channel.adapter.real;

/**
 * 渠道密钥外部取密接口（B8 KMS 接线点，环境残留）。
 *
 * <p>生产可提供真实实现对接 KMS SDK / Vault：从 KMS 取 {@code pay/channel/<channelCode>}
 * 密钥并做本地缓存/轮换；当前仅提供 {@link EnvSecretFetcher} 环境变量实现
 * （与 {@code ChannelSecretProvider} 的 SHOP_PAY_CHANNEL_&lt;CHANNEL&gt;_SECRET 覆盖同源）。</p>
 */
public interface SecretFetcher {

    /**
     * @param channelCode 渠道码（MOCK_WECHAT ...）
     * @return 非空密钥；不存在/未配置返回 null（由凭证校验 fail-fast）
     */
    String fetch(String channelCode);
}
