package com.shop.pay.feature.payment.support;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.feature.payment.dto.ChannelNotifyParams;
import com.shop.pay.feature.payment.dto.RefundNotifyParams;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * 回调验签：HMAC-SHA256（回调字段按 key 排序拼接 + 渠道密钥）。
 * 验签失败统一抛 {@link ErrorCode#PAY_SIGN_ERROR}（60002）拒绝。
 */
@Component
public class SignVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final ChannelSecretProvider secretProvider;

    public SignVerifier(ChannelSecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    /**
     * 验签，失败抛 BizException(60002)。
     */
    public void verify(ChannelNotifyParams params) {
        if (params == null || params.getSign() == null || params.getSign().isBlank()) {

            throw new BizException(ErrorCode.PAY_SIGN_ERROR, "回调缺少签名");
        }
        final String channelSecret;
        try {
            channelSecret = secretProvider.secret(params.getChannelCode());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PAY_SIGN_ERROR, "回调渠道不支持");
        }
        String expected = sign(params, channelSecret);
        if (!constantTimeEquals(expected, params.getSign())) {
            throw new BizException(ErrorCode.PAY_SIGN_ERROR,
                    "回调验签失败 notifyId=" + params.getNotifyId());
        }
    }

    /**
     * 按归一化字段计算签名（测试与文档共用）。
     */
    public String sign(ChannelNotifyParams params, String secret) {
        TreeMap<String, String> fields = new TreeMap<>();
        fields.put("amountFen", String.valueOf(params.getAmountFen()));
        fields.put("channelCode", params.getChannelCode());
        fields.put("channelTxnNo", nullToEmpty(params.getChannelTxnNo()));
        fields.put("notifyId", params.getNotifyId());
        fields.put("payNo", params.getPayNo());
        fields.put("status", params.getStatus());
        if (params.getPaidTime() != null) {
            fields.put("paidTime", params.getPaidTime().toString());
        }
        return hmacSha256Hex(canonical(fields), secret);
    }

    /**
     * B8：退款回调验签，失败抛 BizException(60002)。与支付回调同套 HMAC-SHA256 与密钥提供方，
     * 仅签名字段集不同：channelCode/notifyId/refundNo/channelRefundNo/amountFen/status（+finishTime）。
     */
    public void verifyRefund(RefundNotifyParams params) {
        if (params == null || params.getSign() == null || params.getSign().isBlank()) {
            throw new BizException(ErrorCode.PAY_SIGN_ERROR, "退款回调缺少签名");
        }
        final String channelSecret;
        try {
            channelSecret = secretProvider.secret(params.getChannelCode());
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PAY_SIGN_ERROR, "退款回调渠道不支持");
        }
        String expected = signRefund(params, channelSecret);
        if (!constantTimeEquals(expected, params.getSign())) {
            throw new BizException(ErrorCode.PAY_SIGN_ERROR,
                    "退款回调验签失败 notifyId=" + params.getNotifyId());
        }
    }

    /** 退款回调按归一化字段计算签名（测试与文档共用）。 */
    public String signRefund(RefundNotifyParams params, String secret) {
        TreeMap<String, String> fields = new TreeMap<>();
        fields.put("amountFen", String.valueOf(params.getAmountFen()));
        fields.put("channelCode", params.getChannelCode());
        fields.put("channelRefundNo", nullToEmpty(params.getChannelRefundNo()));
        fields.put("notifyId", params.getNotifyId());
        fields.put("refundNo", params.getRefundNo());
        fields.put("status", params.getStatus());
        if (params.getFinishTime() != null) {
            fields.put("finishTime", params.getFinishTime().toString());
        }
        return hmacSha256Hex(canonical(fields), secret);
    }

    /** 字段按 key 排序拼接：k=v&k=v（TreeMap 已排序）。 */
    private String canonical(TreeMap<String, String> fields) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('&');
        }
        if (canonical.length() > 0) {
            canonical.deleteCharAt(canonical.length() - 1);
        }
        return canonical.toString();
    }

    private String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    public static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] bytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "HMAC-SHA256 计算失败", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
