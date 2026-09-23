package com.shop.pay.channel.adapter.real;

import com.shop.pay.channel.ChannelSecretProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 支付宝真实渠道骨架（openapi 网关 + RSA2 应用私钥签名/公钥回调验签）。
 * 双轨：{@code shop.pay.channel.MOCK_ALIPAY.impl=real} 时取代 mock 承接 MOCK_ALIPAY。
 */
@Component
@Order(0)
@ConditionalOnProperty(value = "shop.pay.real-channels-enabled", havingValue = "true")
public class AlipayPayChannelClient extends AbstractRealChannelClient {

    public AlipayPayChannelClient(RealChannelProperties properties,
                                  RealChannelHttpClient httpClient,
                                  RealChannelSigner signer,
                                  RealResponseParser parser,
                                  ChannelSecretProvider secretProvider,
                                  @Value("${shop.pay.channel.MOCK_ALIPAY.impl:mock}") String implMode) {
        super(properties, httpClient, signer, parser, secretProvider, implMode);
    }

    @Override
    protected String channelCode() {
        return ChannelSecretProvider.MOCK_ALIPAY;
    }
}
