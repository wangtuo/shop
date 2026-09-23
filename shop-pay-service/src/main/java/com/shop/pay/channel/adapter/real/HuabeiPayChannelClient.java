package com.shop.pay.channel.adapter.real;

import com.shop.pay.channel.ChannelSecretProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 花呗真实渠道骨架（支付宝资金侧产品，协议与支付宝开放平台对齐）。
 * 双轨：{@code shop.pay.channel.MOCK_HUABEI.impl=real} 时取代 mock 承接 MOCK_HUABEI。
 */
@Component
@Order(0)
@ConditionalOnProperty(value = "shop.pay.real-channels-enabled", havingValue = "true")
public class HuabeiPayChannelClient extends AbstractRealChannelClient {

    public HuabeiPayChannelClient(RealChannelProperties properties,
                                  RealChannelHttpClient httpClient,
                                  RealChannelSigner signer,
                                  RealResponseParser parser,
                                  ChannelSecretProvider secretProvider,
                                  @Value("${shop.pay.channel.MOCK_HUABEI.impl:mock}") String implMode) {
        super(properties, httpClient, signer, parser, secretProvider, implMode);
    }

    @Override
    protected String channelCode() {
        return ChannelSecretProvider.MOCK_HUABEI;
    }
}
