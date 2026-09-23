package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 渠道下单结果（mock 返回支付参数）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelPayResult {
    private String channelCode;
    private String channelOrderNo;
    /** 收银台链接 / 支付参数（mock） */
    private String payUrl;
    /** mock 下单是否受理成功 */
    private boolean accepted;
    private String failReason;
}
