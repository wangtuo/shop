package com.shop.pay.channel.adapter.real;

import com.shop.common.exception.BizException;
import com.shop.pay.channel.ChannelRefundRequest;
import com.shop.pay.channel.ChannelRouter;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.channel.MockPayChannelClient;
import com.shop.pay.channel.PayChannelClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B8：真实渠道骨架凭证 fail-fast / 未联调不伪成功 / mock 双轨开关与 ChannelRouter 选择序。
 */
class RealChannelSkeletonTest {

    private RealChannelProperties props() {
        return new RealChannelProperties();
    }

    private WechatPayChannelClient wechat(String implMode) {
        return new WechatPayChannelClient(props(),
                new RealChannelHttpClient(props()),
                new RealChannelSigner(),
                new RealResponseParser(),
                ChannelSecretProvider.devDefaults(),
                implMode);
    }

    @Test
    void supports矩阵_仅real模式承接本渠道() {
        WechatPayChannelClient real = wechat("real");
        WechatPayChannelClient mockMode = wechat("mock");
        assertTrue(real.supports("MOCK_WECHAT"));
        assertFalse(real.supports("MOCK_ALIPAY"));
        assertFalse(real.supports("BALANCE"));
        // 默认/显式 mock：real bean 存在也不承接，路由落到 MockPayChannelClient(Order=100)
        assertFalse(mockMode.supports("MOCK_WECHAT"));
    }

    @Test
    void 缺生产凭证_任何能力调用fail_fast报明确错误() {
        WechatPayChannelClient real = wechat("real");
        BizException ex = assertThrows(BizException.class,
                () -> real.refund(ChannelRefundRequest.builder()
                        .channelCode("MOCK_WECHAT").refundNo("R1-0").amountFen(1L).build()));
        assertTrue(ex.getMessage().contains("渠道 MOCK_WECHAT 未配置生产凭证"),
                "异常文案必须明确指出渠道与凭证缺失: " + ex.getMessage());
    }

    @Test
    void 凭证已装配但未联调_抛DEPENDENCY_FAIL且不伪成功() {
        RealChannelProperties properties = props();
        RealChannelProperties.Endpoint endpoint = new RealChannelProperties.Endpoint();
        endpoint.setEndpoint("https://api.mch.weixin.qq.com");
        endpoint.setMerchantId("1900000000");
        properties.getEndpoints().put("MOCK_WECHAT", endpoint);
        WechatPayChannelClient real = new WechatPayChannelClient(properties,
                new RealChannelHttpClient(properties), new RealChannelSigner(),
                new RealResponseParser(), ChannelSecretProvider.devDefaults(), "real");

        BizException ex = assertThrows(BizException.class,
                () -> real.refund(ChannelRefundRequest.builder()
                        .channelCode("MOCK_WECHAT").refundNo("R1-0").amountFen(1L).build()));
        assertEqualsCode(10008, ex);
        assertTrue(ex.getMessage().contains("能力未联调"), ex.getMessage());
    }

    @Test
    void 路由_real优先于mock_关闭real则mock兜底() {
        WechatPayChannelClient real = wechat("real");
        MockPayChannelClient mock = new MockPayChannelClient();
        // Spring 按 @Order 注入：real(0) 在 mock(100) 之前
        ChannelRouter router = new ChannelRouter(List.of(real, mock));
        assertSame(real, router.route("MOCK_WECHAT"));

        // impl=mock：real 不承接，findFirst 落到 mock（即便 real bean 已装配）
        ChannelRouter fallback = new ChannelRouter(List.of(wechat("mock"), mock));
        PayChannelClient routed = fallback.route("MOCK_WECHAT");
        assertTrue(routed instanceof MockPayChannelClient);
    }

    @Test
    void mock双轨_queryRefund受querySuccess开关切换() {
        MockPayChannelClient mock = new MockPayChannelClient();
        ReflectionTestUtils.setField(mock, "querySuccess", true);
        assertEqualsStatus(20, mock.queryRefund(com.shop.pay.channel.ChannelRefundQueryRequest.builder()
                .channelCode("MOCK_WECHAT").requestNo("R1-0").build()).getStatus());
        ReflectionTestUtils.setField(mock, "querySuccess", false);
        assertEqualsStatus(10, mock.queryRefund(com.shop.pay.channel.ChannelRefundQueryRequest.builder()
                .channelCode("MOCK_WECHAT").requestNo("R1-0").build()).getStatus());
        // 退款默认同步成功
        assertEqualsStatus(20, mock.refund(ChannelRefundRequest.builder()
                .channelCode("MOCK_WECHAT").refundNo("R1-0").amountFen(1L).build()).getStatus());
    }

    private static void assertEqualsStatus(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static void assertEqualsCode(int expected, BizException ex) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, ex.getCode());
    }
}
