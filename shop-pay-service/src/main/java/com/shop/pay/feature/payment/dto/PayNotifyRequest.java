package com.shop.pay.feature.payment.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道回调统一入参（端点 /notify/pay/{channel}），sign 为 HMAC-SHA256。
 */
@Data
public class PayNotifyRequest {

    private String notifyId;
    private String payNo;
    private String channelTxnNo;
    /** 回调金额（分） */
    private Long amountFen;
    /** SUCCESS / FAIL */
    private String status;
    private String sign;
    private LocalDateTime paidTime;
}
