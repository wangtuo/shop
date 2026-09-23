package com.shop.pay.channel.adapter.real;

import com.shop.pay.channel.ChannelSecretProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 白条真实渠道骨架（京东金融收单/资金产品，签名协议按对接产品联调）。
 * 双轨：{@code shop.pay.channel.MOCK_BAITIAO.impl=real} 时取代 mock 承接 MOCK_BAITIAO。
 */
@Component
@Order(0)
@ConditionalOnProperty(value = "shop.pay.real-channels-enabled", havingValue = "true")
public class BaitiaoPayChannelClient extends AbstractRealChannelClient {

    public BaitiaoPayChannelClient(RealChannelProperties properties,
                                   RealChannelHttpClient httpClient,
                                   RealChannelSigner signer,
                                   RealResponseParser parser,
                                   ChannelSecretProvider secretProvider,
                                   @Value("${shop.pay.channel.MOCK_BAITIAO.impl:mock}") String implMode) {
        super(properties, httpClient, signer, parser, secretProvider, implMode);
    }

    @Override
    protected String channelCode() {
        return ChannelSecretProvider.MOCK_BAITIAO;
    }
}
