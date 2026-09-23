package com.shop.pay.feature.payment.support;

import com.shop.common.exception.BizException;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.feature.payment.dto.ChannelNotifyParams;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HMAC-SHA256 回调验签：合法签名通过，篡改/缺失签名以 60002 拒绝（design 6.2）。
 */
class SignVerifierTest {

    private final ChannelSecretProvider secretProvider = ChannelSecretProvider.devDefaults();
    private final SignVerifier signVerifier = new SignVerifier(secretProvider);

    private ChannelNotifyParams sample(String sign) {
        return ChannelNotifyParams.builder()
                .channelCode("MOCK_ALIPAY")
                .notifyId("N202609160001")
                .payNo("P26091600000000001")
                .channelTxnNo("MOCK_ALIPAY_T_1")
                .amountFen(12999L)
                .status("SUCCESS")
                .paidTime(LocalDateTime.of(2026, 9, 16, 10, 0, 0))
                .sign(sign)
                .build();
    }

    @Test
    void verify_合法签名_通过() {
        ChannelNotifyParams params = sample(null);
        params.setSign(signVerifier.sign(params, secretProvider.secret("MOCK_ALIPAY")));
        assertDoesNotThrow(() -> signVerifier.verify(params));
    }

    @Test
    void verify_篡改金额签名不一致_抛60002() {
        ChannelNotifyParams params = sample(null);
        String sign = signVerifier.sign(params, secretProvider.secret("MOCK_ALIPAY"));
        params.setAmountFen(1L); // 篡改金额
        params.setSign(sign);
        BizException ex = assertThrows(BizException.class, () -> signVerifier.verify(params));
        assertEquals(60002, ex.getCode());
    }

    @Test
    void verify_伪造签名_抛60002() {
        BizException ex = assertThrows(BizException.class, () -> signVerifier.verify(sample("forged")));
        assertEquals(60002, ex.getCode());
    }

    @Test
    void verify_签名缺失_抛60002() {
        BizException ex = assertThrows(BizException.class, () -> signVerifier.verify(sample(null)));
        assertEquals(60002, ex.getCode());
    }

    @Test
    void sign_确定性_同输入同签名() {
        ChannelNotifyParams p1 = sample(null);
        ChannelNotifyParams p2 = sample(null);
        String secret = secretProvider.secret("MOCK_ALIPAY");
        assertEquals(signVerifier.sign(p1, secret), signVerifier.sign(p2, secret));
    }

    @Test
    void verify_自定义渠道密钥_签名验签通过() {
        ChannelSecretProvider custom = new ChannelSecretProvider(
                java.util.Map.of("MOCK_ALIPAY", "custom-rotated-secret-xyz"), true, "dev");
        SignVerifier customVerifier = new SignVerifier(custom);
        ChannelNotifyParams params = sample(null);
        params.setSign(customVerifier.sign(params, custom.secret("MOCK_ALIPAY")));
        assertDoesNotThrow(() -> customVerifier.verify(params));
    }

    @Test
    void verify_错误密钥验签_抛60002() {
        // 用自定义密钥签名，但服务端配置为另一密钥（模拟轮换不一致/伪造）
        ChannelNotifyParams params = sample(signVerifier.sign(sample(null), "attacker-secret"));
        BizException ex = assertThrows(BizException.class, () -> signVerifier.verify(params));
        assertEquals(60002, ex.getCode());
    }
}
