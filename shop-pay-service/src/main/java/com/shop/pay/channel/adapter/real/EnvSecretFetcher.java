package com.shop.pay.channel.adapter.real;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * 默认密钥获取：环境变量 {@code SHOP_PAY_CHANNEL_<CHANNEL>_SECRET}。
 * 生产以 KMS/Vault 实现替换本 Bean（同类型覆盖即可），属联调环境残留。
 */
@Component
@ConditionalOnMissingBean(SecretFetcher.class)
public class EnvSecretFetcher implements SecretFetcher {

    @Override
    public String fetch(String channelCode) {
        if (channelCode == null) {
            return null;
        }
        return System.getenv("SHOP_PAY_CHANNEL_" + channelCode.replace('-', '_').toUpperCase() + "_SECRET");
    }
}
