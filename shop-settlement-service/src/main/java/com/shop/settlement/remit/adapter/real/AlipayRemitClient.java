package com.shop.settlement.remit.adapter.real;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import com.shop.settlement.enums.WithdrawChannels;
import com.shop.settlement.remit.RemitChannelClient;
import com.shop.settlement.remit.RemitQueryRequest;
import com.shop.settlement.remit.RemitQueryResult;
import com.shop.settlement.remit.RemitRequest;
import com.shop.settlement.remit.RemitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 支付宝单笔转账真实渠道骨架（代发产品签约/应用授权属环境残留，本地 kind 不可联调）。
 *
 * <p>仅在 {@code shop.settle.remit.mock-enabled=false} 装载。任何方法<b>不返回伪成功</b>：
 * 未联调期间统一抛 DEPENDENCY_FAIL。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "shop.settle.remit.mock-enabled", havingValue = "false")
public class AlipayRemitClient implements RemitChannelClient {

    private static final String CHANNEL_CODE = "ALIPAY";

    private final RemitSecretProvider secretProvider;

    public AlipayRemitClient(RemitSecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    @Override
    public boolean supports(Integer channel) {
        return channel != null && channel == WithdrawChannels.ALIPAY;
    }

    @Override
    public RemitResult remit(RemitRequest request) {
        secretProvider.requireSecret(CHANNEL_CODE);
        log.error("支付宝单笔转账未联调，拒绝提交 bizNo={}，请先完成真实渠道接入", request.getBizNo());
        throw new BizException(ErrorCode.DEPENDENCY_FAIL, "支付宝真实代发渠道未联调，禁止伪成功");
    }

    @Override
    public RemitQueryResult query(RemitQueryRequest request) {
        secretProvider.requireSecret(CHANNEL_CODE);
        log.error("支付宝代发查询未联调 bizNo={}", request.getBizNo());
        throw new BizException(ErrorCode.DEPENDENCY_FAIL, "支付宝真实代发查询未联调，禁止伪成功");
    }
}
