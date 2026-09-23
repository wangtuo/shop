package com.shop.pay.feature.payment.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 渠道退款回调统一入参（端点 /notify/refund/{channel}），sign 为 HMAC-SHA256。
 */
@Data
public class RefundNotifyRequest {

    private String notifyId;
    private String refundNo;
    private String channelRefundNo;
    /** 回调退款金额（分） */
    private Long amountFen;
    /** SUCCESS / FAIL */
    private String status;
    /** 失败原因（status=FAIL 时） */
    private String failReason;
    private String sign;
    private LocalDateTime finishTime;
}
