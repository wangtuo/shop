package com.shop.pay.channel;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 支付渠道密钥源（M-5）：密钥一律来自配置 shop.pay.channel.&lt;channel&gt;.secret，
 * 可由环境变量 SHOP_PAY_CHANNEL_&lt;CHANNEL&gt;_SECRET / -D 覆盖；仓库中的值仅为本地开发默认值。
 *
 * <p>生产（spring.profiles.active 含 prod）启动 fail-fast：
 * 仍启用 mock 渠道（shop.pay.mock-channels-enabled 未显式置 false）或任一密钥仍为内置默认值时拒绝启动。
 * 唯一例外：本地/预发 HA 验收必须保留 mock 渠道时，需同时显式配置
 * shop.pay.allow-mock-under-prod=true 且六渠道密钥全部外置为非内置值（双开关，真实生产严禁豁免）。</p>
 */
@Component
public class ChannelSecretProvider {

    public static final String MOCK_WECHAT = "MOCK_WECHAT";
    public static final String MOCK_ALIPAY = "MOCK_ALIPAY";
    public static final String MOCK_BANK = "MOCK_BANK";
    public static final String MOCK_UQR = "MOCK_UQR";
    public static final String MOCK_HUABEI = "MOCK_HUABEI";
    public static final String MOCK_BAITIAO = "MOCK_BAITIAO";

    /** 仅本地开发使用的内置默认密钥，生产禁止使用。 */
    public static final Map<String, String> DEV_DEFAULT_SECRETS = Map.of(
            MOCK_WECHAT, "mock_wechat_secret_2026",
            MOCK_ALIPAY, "mock_alipay_secret_2026",
            MOCK_BANK, "mock_bank_secret_2026",
            MOCK_UQR, "mock_uqr_secret_2026",
            MOCK_HUABEI, "mock_huabei_secret_2026",
            MOCK_BAITIAO, "mock_baitiao_secret_2026");

    private final Map<String, String> secrets;
    private final boolean mockChannelsEnabled;
    private final boolean prodProfile;
    /**
     * 本地/预发 HA 验收显式豁免：prod profile 下默认绝不允许 mock 渠道；
     * 仅当同时显式配置 {@code shop.pay.allow-mock-under-prod=true} 且六渠道密钥全部外置为非内置值时放行。
     * 真实生产环境永远不得配置该豁免（两个开关必须同时显式打开，互为防线）。
     */
    private final boolean allowMockUnderProd;

    @Autowired
    public ChannelSecretProvider(
            @Value("${shop.pay.channel.MOCK_WECHAT.secret:mock_wechat_secret_2026}") String wechatSecret,
            @Value("${shop.pay.channel.MOCK_ALIPAY.secret:mock_alipay_secret_2026}") String alipaySecret,
            @Value("${shop.pay.channel.MOCK_BANK.secret:mock_bank_secret_2026}") String bankSecret,
            @Value("${shop.pay.channel.MOCK_UQR.secret:mock_uqr_secret_2026}") String uqrSecret,
            @Value("${shop.pay.channel.MOCK_HUABEI.secret:mock_huabei_secret_2026}") String huabeiSecret,
            @Value("${shop.pay.channel.MOCK_BAITIAO.secret:mock_baitiao_secret_2026}") String baitiaoSecret,
            @Value("${shop.pay.mock-channels-enabled:true}") boolean mockChannelsEnabled,
            @Value("${shop.pay.allow-mock-under-prod:false}") boolean allowMockUnderProd,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        this(buildSecrets(wechatSecret, alipaySecret, bankSecret, uqrSecret, huabeiSecret, baitiaoSecret),
                mockChannelsEnabled, allowMockUnderProd, activeProfiles);
    }

    /** 测试 / 自定义密钥源构造（默认不豁免 mock 渠道）。 */
    public ChannelSecretProvider(Map<String, String> secrets, boolean mockChannelsEnabled, String activeProfiles) {
        this(secrets, mockChannelsEnabled, false, activeProfiles);
    }

    /** 测试 / 自定义密钥源构造：可显式给出 prod 下 mock 豁免开关。 */
    public ChannelSecretProvider(Map<String, String> secrets, boolean mockChannelsEnabled,
                                 boolean allowMockUnderProd, String activeProfiles) {
        this.secrets = new LinkedHashMap<>(secrets);
        this.mockChannelsEnabled = mockChannelsEnabled;
        this.allowMockUnderProd = allowMockUnderProd;
        this.prodProfile = activeProfiles != null
                && Set.of(activeProfiles.split(",")).stream().map(String::trim).anyMatch("prod"::equals);
        validate();
    }

    /** 纯单测构造：开发默认密钥、非生产环境。 */
    public static ChannelSecretProvider devDefaults() {
        return new ChannelSecretProvider(DEV_DEFAULT_SECRETS, true, "");
    }

    private static Map<String, String> buildSecrets(String wechat, String alipay, String bank,
                                                   String uqr, String huabei, String baitiao) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(MOCK_WECHAT, wechat);
        map.put(MOCK_ALIPAY, alipay);
        map.put(MOCK_BANK, bank);
        map.put(MOCK_UQR, uqr);
        map.put(MOCK_HUABEI, huabei);
        map.put(MOCK_BAITIAO, baitiao);
        return map;
    }

    /** 取渠道 HMAC 密钥；未知渠道抛 IllegalArgumentException。 */
    public String secret(String channelCode) {
        String secret = secrets.get(channelCode);
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("未知渠道编码: " + channelCode);
        }
        return secret;
    }

    /** 渠道合法性校验（供对账等场景复用）。 */
    public void requireKnownChannel(String channelCode) {
        if (!secrets.containsKey(channelCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知支付渠道");
        }
    }

    /**
     * 生产环境 fail-fast：
     * <ol>
     *   <li>启用 mock 渠道即拒绝启动；唯一例外是本地/预发 HA 验收显式豁免
     *       （{@code shop.pay.allow-mock-under-prod=true}），真实生产严禁配置；</li>
     *   <li>无论是否启用 mock、是否豁免，任一渠道密钥仍等于内置默认值一律拒绝启动——
     *       豁免只解除“mock 渠道”禁令，不解除“密钥必须外置”要求。</li>
     * </ol>
     *
     * @throws IllegalStateException 生产配置不安全
     */
    public final void validate() {
        if (!prodProfile) {
            return;
        }
        if (mockChannelsEnabled && !allowMockUnderProd) {
            throw new IllegalStateException("生产环境禁止启用 MOCK 支付渠道：请配置 shop.pay.mock-channels-enabled=false "
                    + "并接入真实渠道实现（本地/预发 HA 验收需保留 mock 时，必须同时显式配置 "
                    + "shop.pay.allow-mock-under-prod=true 且全部渠道密钥外置，真实生产不得配置该豁免）");
        }
        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            if (DEV_DEFAULT_SECRETS.containsValue(entry.getValue())) {
                throw new IllegalStateException("生产环境渠道 " + entry.getKey()
                        + " 仍在使用内置默认密钥：请通过环境变量 SHOP_PAY_CHANNEL_"
                        + entry.getKey().replace('-', '_') + "_SECRET 注入真实密钥");
            }
        }
    }
}
