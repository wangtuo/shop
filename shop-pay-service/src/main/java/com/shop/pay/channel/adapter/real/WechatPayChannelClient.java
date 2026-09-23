package com.shop.pay.channel.adapter.real;

import com.shop.pay.channel.ChannelSecretProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 微信支付真实渠道骨架（v3 JSON + RSA2 商户证书签名）。
 * 双轨：{@code shop.pay.real-channels-enabled=true} 装配 bean，
 * {@code shop.pay.channel.MOCK_WECHAT.impl=real} 时取代 mock 承接 MOCK_WECHAT。
 */
@Component
@Order(0)
@ConditionalOnProperty(value = "shop.pay.real-channels-enabled", havingValue = "true")
public class WechatPayChannelClient extends AbstractRealChannelClient {

    public WechatPayChannelClient(RealChannelProperties properties,
                                  RealChannelHttpClient httpClient,
                                  RealChannelSigner signer,
                                  RealResponseParser parser,
                                  ChannelSecretProvider secretProvider,
                                  @Value("${shop.pay.channel.MOCK_WECHAT.impl:mock}") String implMode) {
        super(properties, httpClient, signer, parser, secretProvider, implMode);
    }

    @Override
    protected String channelCode() {
        return ChannelSecretProvider.MOCK_WECHAT;
    }
}
