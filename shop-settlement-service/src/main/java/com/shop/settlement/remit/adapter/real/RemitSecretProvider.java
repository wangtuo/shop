package com.shop.settlement.remit.adapter.real;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 真实代发渠道密钥供给：环境变量 {@code SHOP_SETTLE_REMIT_<CHANNEL>_SECRET}
 * （BANK/ALIPAY）。生产以 KMS/Vault 实现同类型覆盖本 Bean 即可。
 *
 * <p>prod fail-fast：应用启动（且 mock 关闭）时即校验密钥存在，缺失直接拒绝启动，
 * 避免流量到达才暴露配置缺口；非 prod 环境延迟到调用时报错（本卡真实渠道未联调，调用必然失败）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "shop.settle.remit.mock-enabled", havingValue = "false")
public class RemitSecretProvider {

    /** 渠道码 -> 环境变量名（仅内置银行卡/支付宝） */
    public static String envName(String channelCode) {
        return "SHOP_SETTLE_REMIT_" + channelCode.replace('-', '_').toUpperCase() + "_SECRET";
    }

    private final Environment environment;

    @Value("${shop.settle.remit.real.channels:BANK,ALIPAY}")
    private String requiredChannels;

    public RemitSecretProvider(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void failFastInProd() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        for (String channel : requiredChannels.split(",")) {
            String code = channel.trim();
            if (code.isEmpty()) {
                continue;
            }
            requireSecret(code);
        }
        log.info("prod 真实代发密钥校验通过 channels={}", requiredChannels);
    }

    /**
     * 取渠道密钥；缺失抛 IllegalStateException（启动期 fail-fast / 调用期显式失败，绝不匿名放行）。
     */
    public String requireSecret(String channelCode) {
        String secret = System.getenv(envName(channelCode));
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("真实代发渠道密钥未配置: " + envName(channelCode)
                    + "（prod 必须通过环境变量/KMS 注入）");
        }
        return secret;
    }
}
