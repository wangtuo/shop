package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 渠道下单请求（mock 适配器入参）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelPayRequest {
    private String channelCode;
    private String payNo;
    private String orderNo;
    private Long amountFen;
    private String subject;
    private Integer terminal;
}
