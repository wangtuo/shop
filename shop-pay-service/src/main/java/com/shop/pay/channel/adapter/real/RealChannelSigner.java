package com.shop.pay.channel.adapter.real;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.pay.feature.payment.support.SignVerifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * 真实渠道签名器（B8 骨架）：按渠道归一化参数后签名。
 *
 * <p>接线边界：</p>
 * <ul>
 *   <li>HMAC 系渠道（云闪付/花呗/白条 mock 协议）：复用回调验签同款 HMAC-SHA256，已可工作；</li>
 *   <li>RSA2 系（微信商户证书 / 支付宝应用私钥）：真实签名算法、证书加载、序列号头
 *       （Wechatpay-Serial/Authorization）属联调环境残留，方法内 TODO 边界明确，
 *       未联调一律抛 {@link ErrorCode#DEPENDENCY_FAIL}，禁止返回伪签名。</li>
 * </ul>
 */
@Component
public class RealChannelSigner {

    /** HMAC 系渠道（与 mock 回调 HMAC-SHA256 同算法，真实联调以渠道协议替换）。 */
    public String signHmac(String channelCode, Map<String, String> params, String secret) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            sorted.putAll(params);
        }
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            canonical.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue()).append('&');
        }
        if (canonical.length() > 0) {
            canonical.deleteCharAt(canonical.length() - 1);
        }
        return SignVerifier.hmacSha256Hex(canonical.toString(), secret);
    }

    /**
     * RSA2（SHA256withRSA）签名：微信/支付宝真实协议。
     * TODO(真实渠道联调): 从 RealChannelProperties.Endpoint.certPath 加载商户私钥（PEM/P12），
     *   按各渠道签名串规范组装后 Signature.getInstance("SHA256withRSA") 签名并 Base64。
     */
    public String signRsa(String channelCode, String canonical, String certPath) {
        throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                "真实渠道 " + channelCode + " RSA 签名能力未联调（certPath=" + certPath + "）");
    }

    /**
     * RSA 回调验签（微信平台证书/支付宝公钥）。
     * TODO(真实渠道联调): 平台证书轮换下载（微信 /v3/certificates）、公钥验签、时间戳/nonce 重放校验。
     */
    public boolean verifyRsa(String channelCode, String canonical, String sign, String publicCertPath) {
        throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                "真实渠道 " + channelCode + " RSA 验签能力未联调（publicCertPath=" + publicCertPath + "）");
    }
}
