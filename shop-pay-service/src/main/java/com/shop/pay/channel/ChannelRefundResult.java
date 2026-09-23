package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 渠道退款结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelRefundResult {
    private String channelCode;
    private String channelRefundNo;
    /** 10 受理中 20 成功 30 失败 */
    private int status;
    private String failReason;

    public boolean success() {
        return status == 20;
    }

    public static ChannelRefundResult ok(String channelCode, String channelRefundNo) {
        return ChannelRefundResult.builder()
                .channelCode(channelCode)
                .channelRefundNo(channelRefundNo)
                .status(20)
                .build();
    }

    public static ChannelRefundResult fail(String channelCode, String reason) {
        return ChannelRefundResult.builder()
                .channelCode(channelCode)
                .status(30)
                .failReason(reason)
                .build();
    }
}
