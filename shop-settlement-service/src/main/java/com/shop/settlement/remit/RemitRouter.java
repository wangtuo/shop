package com.shop.settlement.remit;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 打款渠道路由：按 channel 选择第一个 {@link RemitChannelClient#supports(Integer)} 的实现。
 * mock 与 real 互斥（real 仅在 shop.settle.remit.mock-enabled=false 时装载），正常恰好一个实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemitRouter {

    private final List<RemitChannelClient> clients;

    public RemitChannelClient route(Integer channel) {
        return clients.stream()
                .filter(c -> c.supports(channel))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.DEPENDENCY_FAIL,
                        "无可用打款渠道实现 channel=" + channel));
    }
}
