package com.shop.pay.channel.adapter.real;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.pay.channel.ChannelBillRecord;
import com.shop.pay.channel.ChannelPayRequest;
import com.shop.pay.channel.ChannelPayResult;
import com.shop.pay.channel.ChannelQueryResult;
import com.shop.pay.channel.ChannelRefundQueryRequest;
import com.shop.pay.channel.ChannelRefundQueryResult;
import com.shop.pay.channel.ChannelRefundRequest;
import com.shop.pay.channel.ChannelRefundResult;
import com.shop.pay.channel.ChannelSecretProvider;
import com.shop.pay.channel.PayChannelClient;

import java.time.LocalDate;
import java.util.List;

/**
 * 真实渠道适配器骨架基类（B8）：六个真实渠道（微信/支付宝/银行/云闪付/花呗/白条）共用。
 *
 * <p>骨架职责已完整：配置装配（{@link RealChannelProperties}）、密钥读取（只经
 * {@link ChannelSecretProvider}/{@link SecretFetcher}，不落明文）、签名接线（{@link RealChannelSigner}）、
 * HTTP 超时/重试/脱敏接线（{@link RealChannelHttpClient}）、响应错误码映射（{@link RealResponseParser}）。</p>
 *
 * <p>真实 endpoint/证书/签名细节/账单字段/webhook 注册/沙箱联调为环境残留：所有能力方法在
 * 凭证缺失时抛明确的 {@code BizException("渠道 X 未配置生产凭证")}，未联调能力抛
 * {@code DEPENDENCY_FAIL "真实渠道 X 能力未联调: ..."}，<b>禁止返回伪成功</b>。</p>
 */
public abstract class AbstractRealChannelClient implements PayChannelClient {

    protected final RealChannelProperties properties;
    protected final RealChannelHttpClient httpClient;
    protected final RealChannelSigner signer;
    protected final RealResponseParser parser;
    protected final ChannelSecretProvider secretProvider;
    /** shop.pay.channel.<CODE>.impl=mock|real，默认 mock（双轨开关）。 */
    private final String implMode;

    protected AbstractRealChannelClient(RealChannelProperties properties,
                                        RealChannelHttpClient httpClient,
                                        RealChannelSigner signer,
                                        RealResponseParser parser,
                                        ChannelSecretProvider secretProvider,
                                        String implMode) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.signer = signer;
        this.parser = parser;
        this.secretProvider = secretProvider;
        this.implMode = implMode;
    }

    /** 本适配器对应的渠道码（MOCK_WECHAT ... MOCK_BAITIAO）。 */
    protected abstract String channelCode();

    @Override
    public boolean supports(String code) {
        return channelCode().equals(code) && "real".equalsIgnoreCase(implMode);
    }

    /**
     * 生产凭证校验：impl=real 时 endpoint/merchantId 必须配置，密钥必须经 ChannelSecretProvider
     * 外置（prod 内置默认密钥已由 ChannelSecretProvider.validate() fail-fast）。
     * 任一缺失：明确报"渠道 X 未配置生产凭证"，绝不静默回退 mock / 伪成功。
     */
    protected void ensureConfigured() {
        RealChannelProperties.Endpoint ep = properties.endpoint(channelCode());
        if (ep == null || isBlank(ep.getEndpoint()) || isBlank(ep.getMerchantId())) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "渠道 " + channelCode() + " 未配置生产凭证（shop.pay.channel.real.endpoints."
                            + channelCode() + ".endpoint/merchant-id 必填）");
        }
        try {
            String secret = secretProvider.secret(channelCode());
            if (isBlank(secret)) {
                throw new IllegalArgumentException("empty secret");
            }
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.DEPENDENCY_FAIL,
                    "渠道 " + channelCode() + " 未配置生产凭证（密钥须经 SHOP_PAY_CHANNEL_"
                            + channelCode() + "_SECRET / KMS 注入）");
        }
    }

    /** TODO(真实渠道联调): 组装下单报文 → signer 签名 → httpClient POST /pay/... → parser 映射。 */
    @Override
    public ChannelPayResult createOrder(ChannelPayRequest request) {
        ensureConfigured();
        throw notWired("createOrder 下单");
    }

    /** TODO(真实渠道联调): GET 订单查询 → parser 映射 PAYING/SUCCESS/FAIL/CLOSED。 */
    @Override
    public ChannelQueryResult query(String code, String channelOrderNo) {
        ensureConfigured();
        throw notWired("query 支付查询");
    }

    /** TODO(真实渠道联调): POST 退款申请（幂等号 refundNo-index）→ parser 映射 10/20/30。 */
    @Override
    public ChannelRefundResult refund(ChannelRefundRequest request) {
        ensureConfigured();
        throw notWired("refund 原路退款");
    }

    /** TODO(真实渠道联调): POST/GET 退款查询 → parser 映射 10/20/30。 */
    @Override
    public ChannelRefundQueryResult queryRefund(ChannelRefundQueryRequest request) {
        ensureConfigured();
        throw notWired("queryRefund 退款查询");
    }

    /** TODO(真实渠道联调): 按渠道账单协议下载（CSV/JSON/ZIP 解压），解析为 ChannelBillRecord 三差异输入。 */
    @Override
    public List<ChannelBillRecord> downloadBill(String code, LocalDate billDate) {
        ensureConfigured();
        throw notWired("downloadBill 对账单下载");
    }

    protected BizException notWired(String ability) {
        return new BizException(ErrorCode.DEPENDENCY_FAIL,
                "真实渠道 " + channelCode() + " 能力未联调: " + ability);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
