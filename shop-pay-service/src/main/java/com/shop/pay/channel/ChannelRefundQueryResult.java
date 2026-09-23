package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 渠道退款主动查询结果（B8）。状态码与 {@link ChannelRefundResult} 一致：
 * 10 受理中（保持本地 PROCESSING，等下轮补偿）/ 20 退款成功 / 30 退款失败。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelRefundQueryResult {
    private String channelCode;
    private String channelRefundNo;
    /** 10 受理中 20 成功 30 失败 */
    private int status;
    private String failReason;

    public boolean success() {
        return status == 20;
    }

    public boolean processing() {
        return status == 10;
    }

    public boolean failed() {
        return status == 30;
    }

    public static ChannelRefundQueryResult ok(String channelCode, String channelRefundNo) {
        return ChannelRefundQueryResult.builder()
                .channelCode(channelCode)
                .channelRefundNo(channelRefundNo)
                .status(20)
                .build();
    }

    public static ChannelRefundQueryResult processing(String channelCode) {
        return ChannelRefundQueryResult.builder()
                .channelCode(channelCode)
                .status(10)
                .build();
    }

    public static ChannelRefundQueryResult fail(String channelCode, String reason) {
        return ChannelRefundQueryResult.builder()
                .channelCode(channelCode)
                .status(30)
                .failReason(reason)
                .build();
    }
}
