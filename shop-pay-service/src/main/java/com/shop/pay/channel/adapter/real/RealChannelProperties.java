package com.shop.pay.channel.adapter.real;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实渠道生产配置（B8）：{@code shop.pay.channel.real.*}。
 *
 * <pre>
 * shop.pay.channel.real:
 *   connect-timeout-ms: 2000
 *   read-timeout-ms: 5000
 *   max-retries: 2
 *   endpoints:
 *     MOCK_WECHAT:   { endpoint: https://api.mch.weixin.qq.com, merchant-id: "...", cert-path: "/etc/shop/wechat.p12" }
 *     MOCK_ALIPAY:   { endpoint: https://openapi.alipay.com,    merchant-id: "...", cert-path: "/etc/shop/alipay.cert" }
 *     ...
 * </pre>
 *
 * <p>密钥<b>不</b>经本配置：只允许 {@code ChannelSecretProvider}（环境变量
 * {@code SHOP_PAY_CHANNEL_<CHANNEL>_SECRET} / KMS）注入，禁止在 yml 落明文密钥。
 * 单渠道 mock|real 双轨开关为 {@code shop.pay.channel.<CODE>.impl=mock|real}（默认 mock）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "shop.pay.channel.real")
public class RealChannelProperties {

    /** 连接超时（毫秒），骨架默认 2s。 */
    private int connectTimeoutMs = 2000;
    /** 读超时（毫秒），骨架默认 5s。 */
    private int readTimeoutMs = 5000;
    /** 渠道 5xx/网络抖动的最大重试次数（真实联调时接 Resilience4j/Spring Retry）。 */
    private int maxRetries = 2;
    /** 六渠道 endpoint / 商户号 / 证书路径；key 为渠道码（MOCK_WECHAT ...）。 */
    private Map<String, Endpoint> endpoints = new LinkedHashMap<>();

    public Endpoint endpoint(String channelCode) {
        return endpoints.get(channelCode);
    }

    @Data
    public static class Endpoint {
        /** 渠道网关基础地址 */
        private String endpoint;
        /** 商户号 / 应用 APPID（渠道身份，非密钥） */
        private String merchantId;
        /** 商户证书 / 应用私钥文件路径（K8s secret 挂载） */
        private String certPath;
        /** 退款回调地址（在渠道商户平台登记，webhook 路径 /notify/refund/{channel}） */
        private String refundNotifyUrl;
    }
}
