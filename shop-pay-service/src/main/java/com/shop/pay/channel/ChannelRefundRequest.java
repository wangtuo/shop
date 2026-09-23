package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 渠道退款请求。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelRefundRequest {
    private String channelCode;
    /** 原渠道下单流水号 */
    private String channelOrderNo;
    /** 原渠道交易流水号 */
    private String channelTxnNo;
    /** 退款单号（渠道幂等键） */
    private String refundNo;
    private Long amountFen;
    private String reason;
}
