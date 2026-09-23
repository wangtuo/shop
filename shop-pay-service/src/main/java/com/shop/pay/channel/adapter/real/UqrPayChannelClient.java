package com.shop.pay.channel.adapter.real;

import com.shop.pay.channel.ChannelSecretProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 云闪付（UQR，PayMethods.UNIONPAY）真实渠道骨架（银联全渠道平台，HMAC/RSA 按产品联调）。
 * 双轨：{@code shop.pay.channel.MOCK_UQR.impl=real} 时取代 mock 承接 MOCK_UQR。
 */
@Component
@Order(0)
@ConditionalOnProperty(value = "shop.pay.real-channels-enabled", havingValue = "true")
public class UqrPayChannelClient extends AbstractRealChannelClient {

    public UqrPayChannelClient(RealChannelProperties properties,
                               RealChannelHttpClient httpClient,
                               RealChannelSigner signer,
                               RealResponseParser parser,
                               ChannelSecretProvider secretProvider,
                               @Value("${shop.pay.channel.MOCK_UQR.impl:mock}") String implMode) {
        super(properties, httpClient, signer, parser, secretProvider, implMode);
    }

    @Override
    protected String channelCode() {
        return ChannelSecretProvider.MOCK_UQR;
    }
}
