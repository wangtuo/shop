package com.shop.pay.feature.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 渠道异步回调归一化参数（design 6.2：先验签、再幂等、金额与单状态校验）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelNotifyParams {

    private String channelCode;
    /** 渠道通知 ID（幂等键） */
    private String notifyId;
    private String payNo;
    private String channelTxnNo;
    /** 回调金额（分） */
    private Long amountFen;
    /** SUCCESS / FAIL */
    private String status;
    /** HMAC-SHA256 签名 */
    private String sign;
    private LocalDateTime paidTime;
    /** 1 支付回调 2 退款回调 */
    private Integer notifyType;
}
