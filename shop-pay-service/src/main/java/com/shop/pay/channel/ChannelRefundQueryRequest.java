package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 渠道退款主动查询请求（B8）：退款受理中（{@link ChannelRefundResult#getStatus()}=10）时，
 * 由 RefundQueryJob 周期性补偿查询，结果与渠道异步回调进入同一收敛漏斗。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelRefundQueryRequest {
    private String channelCode;
    /** 原渠道下单流水号 */
    private String channelOrderNo;
    /** 原渠道交易流水号 */
    private String channelTxnNo;
    /** 渠道退款流水号（可能为空：本地刚受理即宕机时尚未回写） */
    private String channelRefundNo;
    /** 本地退款单号（渠道幂等键前缀 refundNo-index） */
    private String refundNo;
    /** 渠道侧退款请求幂等号（refundNo + "-" + splitIndex） */
    private String requestNo;
    private Long amountFen;
}
