package com.shop.pay.channel.adapter.real;

import com.shop.pay.channel.ChannelSecretProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 银行卡快捷支付真实渠道骨架（银行收单网关，签名协议按对接银行联调）。
 * 双轨：{@code shop.pay.channel.MOCK_BANK.impl=real} 时取代 mock 承接 MOCK_BANK。
 */
@Component
@Order(0)
@ConditionalOnProperty(value = "shop.pay.real-channels-enabled", havingValue = "true")
public class BankPayChannelClient extends AbstractRealChannelClient {

    public BankPayChannelClient(RealChannelProperties properties,
                                RealChannelHttpClient httpClient,
                                RealChannelSigner signer,
                                RealResponseParser parser,
                                ChannelSecretProvider secretProvider,
                                @Value("${shop.pay.channel.MOCK_BANK.impl:mock}") String implMode) {
        super(properties, httpClient, signer, parser, secretProvider, implMode);
    }

    @Override
    protected String channelCode() {
        return ChannelSecretProvider.MOCK_BANK;
    }
}
