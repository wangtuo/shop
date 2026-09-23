package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 渠道侧账单记录（T+1 对账单拉取结果的归一化模型，design 6.5）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelBillRecord {

    private String channelCode;
    /** 渠道交易流水号 */
    private String channelTxnNo;
    /** 渠道下单流水号 */
    private String channelOrderNo;
    /** 渠道回传的支付单号 */
    private String payNo;
    private String orderNo;
    /** 成交金额，单位分 */
    private Long amountFen;
    /** 渠道记账时间 */
    private LocalDateTime paidTime;
    /** 渠道账单中该笔是否成功 */
    private boolean success;
}
