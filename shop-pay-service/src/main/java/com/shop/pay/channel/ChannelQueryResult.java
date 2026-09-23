package com.shop.pay.channel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 主动查询渠道支付状态结果（补偿双保险使用）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelQueryResult {

    /** 渠道侧状态：WAIT / PAYING / SUCCESS / FAIL / CLOSED */
    public enum State { WAIT, PAYING, SUCCESS, FAIL, CLOSED }

    private State state;
    private String channelTxnNo;
    private Long amountFen;

    public static ChannelQueryResult of(State state) {
        return ChannelQueryResult.builder().state(state).build();
    }
}
