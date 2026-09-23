package com.shop.pay.feature.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 退款渠道异步回调归一化参数（B8，端点 POST /notify/refund/{channel}）。
 * 风格对齐 {@link ChannelNotifyParams}：先验签、再 notify_log 幂等、再金额/状态校验。
 * 签名字段集：channelCode/notifyId/refundNo/channelRefundNo/amountFen/status（+finishTime）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundNotifyParams {

    private String channelCode;
    /** 渠道通知 ID（与 channelCode 联合唯一，幂等键） */
    private String notifyId;
    private String refundNo;
    private String channelRefundNo;
    /** 回调退款金额（分），与退款单 amount_fen 一致校验 */
    private Long amountFen;
    /** SUCCESS / FAIL */
    private String status;
    /** HMAC-SHA256 签名 */
    private String sign;
    private LocalDateTime finishTime;
    /** 固定 2 退款回调 */
    private Integer notifyType;
}
