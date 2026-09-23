package com.shop.settlement.remit;

import lombok.Builder;
import lombok.Data;

/** 代发终态查询请求（查询补偿 Job 使用）。 */
@Data
@Builder
public class RemitQueryRequest {

    /** 渠道幂等键：提现 withdrawNo 或保证金退还 logNo */
    private String bizNo;
    /** 渠道：1 银行卡 2 支付宝 */
    private Integer channel;
    /** 受理时返回的渠道流水号 */
    private String channelRemitNo;
}
