package com.shop.pay.channel;

import com.shop.common.exception.BizException;
import com.shop.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 渠道路由：按渠道编码选择适配器。
 */
@Component
public class ChannelRouter {

    private final List<PayChannelClient> clients;

    public ChannelRouter(List<PayChannelClient> clients) {
        this.clients = clients;
    }

    public PayChannelClient route(String channelCode) {
        if (ChannelLimits.isBalance(channelCode)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "余额支付不经过渠道适配器: " + channelCode);
        }
        return clients.stream()
                .filter(c -> c.supports(channelCode))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.PAY_ERROR, "不支持的支付渠道: " + channelCode));
    }

    /** 退款状态查询委托（B8）：余额退款不经过 SPI，由同步路径终结。 */
    public ChannelRefundQueryResult queryRefund(ChannelRefundQueryRequest request) {
        return route(request.getChannelCode()).queryRefund(request);
    }
}
