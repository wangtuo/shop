package com.shop.pay.feature.recon.support;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对账比对出的差错草稿（无状态比对结果，由 Service 落 t_pay_recon_diff）。
 * diffType 1 长款 2 短款 3 金额不符。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconDiffDraft {

    private Integer diffType;
    private String payNo;
    private String orderNo;
    private String channelTxnNo;
    private Long localAmountFen;
    private Long channelAmountFen;
}
